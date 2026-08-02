package com.softwaremine.dps.data.runtime.ollama

import com.softwaremine.dps.core.concurrency.DispatcherProvider
import com.softwaremine.dps.core.error.DpsError
import com.softwaremine.dps.core.logging.DpsLogger
import com.softwaremine.dps.core.result.DpsResult
import com.softwaremine.dps.domain.ai.AiCompletion
import com.softwaremine.dps.domain.ai.CompletionChunk
import com.softwaremine.dps.domain.ai.CompletionRequest
import com.softwaremine.dps.domain.ai.FinishReason
import com.softwaremine.dps.domain.ai.TokenUsage
import com.softwaremine.dps.domain.model.ModelConfig
import com.softwaremine.dps.domain.runtime.RuntimeCapabilities
import com.softwaremine.dps.domain.runtime.RuntimeId
import com.softwaremine.dps.domain.runtime.RuntimeProvider
import com.softwaremine.dps.domain.runtime.RuntimeStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The development runtime: HTTP to an Ollama server on the developer's machine.
 *
 * ## Purpose
 * Lets the whole AI layer be exercised end to end before the NDK toolchain and
 * native build exist. Model swaps are a one-line change and require no
 * recompilation, which makes prompt-format and sampling work far faster to
 * iterate on than a native rebuild cycle allows.
 *
 * ## Development only — enforced, not merely intended
 * This runtime sends prompts over a network socket. Shipping it would breach
 * both Offline First and Privacy First, so it is excluded from release builds
 * by construction rather than by discipline:
 *
 * - `OLLAMA_BASE_URL` is compiled to `""` in release, so [isAvailable] is
 *   `false` and the runtime can never be selected;
 * - cleartext HTTP is permitted only by the debug manifest overlay;
 * - [RuntimeCapabilities.isFullyOffline] is `false`, making the property
 *   inspectable at runtime.
 *
 * Three independent mechanisms, because a single one can be edited away by
 * someone who does not know why it is there.
 *
 * ## Connecting from a physical device
 * The team tests on real hardware (ADR-009), so the phone must reach the
 * laptop's Ollama:
 *
 * ```bash
 * adb reverse tcp:11434 tcp:11434
 * ```
 *
 * The device's `localhost:11434` then resolves to the host's, over USB with no
 * shared Wi-Fi and no LAN exposure.
 *
 * ## Dependencies
 * OkHttp, kotlinx.serialization, coroutines.
 */
class OllamaRuntimeProvider(
    private val baseUrl: String,
    private val modelTag: String,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
    private val logger: DpsLogger,
) : RuntimeProvider {

    override val id: RuntimeId = RuntimeId.OLLAMA

    override val capabilities: RuntimeCapabilities = RuntimeCapabilities(
        supportsStreaming = true,
        // Ollama exposes no tokenizer endpoint. tokenCount() therefore returns a
        // deliberate over-estimate; see that method for why the direction of the
        // error matters. Declared false so callers know it is not authoritative.
        supportsTokenCount = false,
        supportsGpuOffload = true,
        isFullyOffline = false,
        supportsGrammarConstraints = false,
    )

    private val _status = MutableStateFlow<RuntimeStatus>(
        RuntimeStatus.Unavailable("Not yet probed."),
    )
    override val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    @Volatile
    private var activeConfig: ModelConfig? = null

    override suspend fun isAvailable(): Boolean = withContext(dispatchers.io) {
        if (baseUrl.isBlank()) {
            // The release-build case: no URL was compiled in.
            _status.value = RuntimeStatus.Unavailable("No Ollama base URL configured.")
            return@withContext false
        }

        val reachable = runCatching {
            val request = Request.Builder().url("$baseUrl/api/tags").get().build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        _status.value = if (reachable) {
            RuntimeStatus.Available
        } else {
            RuntimeStatus.Unavailable("Ollama not reachable at $baseUrl.")
        }
        if (!reachable) {
            logger.i(TAG, "Ollama not reachable at $baseUrl (expected outside development).")
        }
        reachable
    }

    /**
     * Ollama holds models server-side, so there is no file to load.
     *
     * [modelFile] is ignored by design. This is the one place the two runtimes
     * genuinely differ in nature, and the asymmetry is confined here rather than
     * being pushed up into the interface — a `loadFromFileOrTag` contract would
     * force every future runtime to care about a distinction only this one has.
     */
    override suspend fun load(modelFile: File, config: ModelConfig): DpsResult<Unit> =
        withContext(dispatchers.io) {
            if (baseUrl.isBlank()) {
                return@withContext DpsResult.Failure(
                    DpsError.Runtime.Unavailable(id.label, "No Ollama base URL configured."),
                )
            }
            activeConfig = config
            _status.value = RuntimeStatus.Loaded(modelTag, System.currentTimeMillis())
            logger.i(TAG, "Using Ollama model tag '$modelTag'.")
            DpsResult.Ok
        }

    override suspend fun unload(): DpsResult<Unit> {
        activeConfig = null
        _status.value = RuntimeStatus.Available
        return DpsResult.Ok
    }

    override fun generate(request: CompletionRequest): Flow<CompletionChunk> = flow {
        if (baseUrl.isBlank()) {
            emit(CompletionChunk.Failed(DpsError.Runtime.Unavailable(id.label, "No base URL.")))
            return@flow
        }

        val payload = OllamaGenerateRequest(
            model = modelTag,
            prompt = request.prompt,
            raw = true,
            stream = true,
            options = OllamaOptions(
                numCtx = request.config.contextLength,
                numPredict = request.config.maxOutputTokens,
                temperature = request.config.temperature,
                topP = request.config.topP,
                topK = request.config.topK,
                repeatPenalty = request.config.repeatPenalty,
                seed = request.config.seed ?: RANDOM_SEED,
                stop = request.stopSequences,
            ),
        )

        // The serializer is passed explicitly rather than relying on the
        // reified `encodeToString(value)` extension: without an explicit import
        // of that extension the call resolves to the member overload
        // `encodeToString(serializer, value)` and fails with a confusing type
        // mismatch. Naming the serializer is unambiguous and survives imports
        // being reorganised.
        val requestJson = json.encodeToString(OllamaGenerateRequest.serializer(), payload)

        val httpRequest = Request.Builder()
            .url("$baseUrl/api/generate")
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val startedAt = System.currentTimeMillis()
        val accumulated = StringBuilder()

        try {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(
                        CompletionChunk.Failed(
                            DpsError.Runtime.GenerationFailed("Ollama HTTP ${response.code}"),
                        ),
                    )
                    return@flow
                }

                val body = response.body ?: run {
                    emit(
                        CompletionChunk.Failed(
                            DpsError.Runtime.GenerationFailed("Empty Ollama response body."),
                        ),
                    )
                    return@flow
                }

                // NDJSON: one JSON object per line, streamed as generated.
                val reader = body.charStream().buffered()
                var finalLine: OllamaGenerateResponse? = null

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue

                    val parsed = runCatching {
                        json.decodeFromString<OllamaGenerateResponse>(line)
                    }.getOrNull() ?: continue

                    if (parsed.response.isNotEmpty()) {
                        accumulated.append(parsed.response)
                        emit(CompletionChunk.Token(parsed.response))
                    }
                    if (parsed.done) {
                        finalLine = parsed
                        break
                    }
                }

                emit(
                    CompletionChunk.Completed(
                        AiCompletion(
                            text = accumulated.toString(),
                            finishReason = finalLine?.doneReason.toFinishReason(),
                            usage = TokenUsage(
                                promptTokens = finalLine?.promptEvalCount ?: 0,
                                completionTokens = finalLine?.evalCount ?: 0,
                            ),
                            durationMillis = System.currentTimeMillis() - startedAt,
                        ),
                    ),
                )
            }
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            emit(
                CompletionChunk.Failed(
                    DpsError.Runtime.GenerationFailed(
                        reason = throwable.message ?: "Ollama request failed.",
                        cause = throwable,
                    ),
                ),
            )
        }
    }.flowOn(dispatchers.io)

    /**
     * Estimates token count. **Development approximation, not a measurement.**
     *
     * Ollama exposes no tokenizer endpoint, so a real count is unavailable
     * before generation. Rather than fail — which would make the entire prompt
     * pipeline untestable without the native runtime — this returns a
     * deliberate over-estimate, and [RuntimeCapabilities.supportsTokenCount] is
     * `false` so callers know it is not authoritative.
     *
     * ## Why over-estimating specifically
     * The direction of the error is the whole design. Over-estimating causes
     * history to be trimmed slightly too aggressively: the model sees marginally
     * less context than it could have. Under-estimating causes the context
     * window to overflow, which on the production runtime is a native crash.
     *
     * A conservative divisor of 3 characters per token is used rather than the
     * conventional 4, because Roman Urdu — the primary interaction language in
     * `user_journey.md` — tokenises considerably less efficiently than English,
     * and a divisor tuned on English would under-count exactly the input this
     * product exists to handle.
     *
     * The production runtime uses the model's real tokenizer and this path is
     * never taken in a release build.
     */
    override suspend fun tokenCount(text: String): DpsResult<Int> {
        val estimate = (text.length + CONSERVATIVE_CHARS_PER_TOKEN - 1) / CONSERVATIVE_CHARS_PER_TOKEN
        return DpsResult.Success(estimate)
    }

    private fun String?.toFinishReason(): FinishReason = when (this) {
        "stop" -> FinishReason.END_OF_TURN
        "length" -> FinishReason.MAX_TOKENS
        else -> FinishReason.END_OF_TURN
    }

    companion object {
        private const val TAG = "OllamaRuntime"
        private const val RANDOM_SEED = -1
        private const val CONSERVATIVE_CHARS_PER_TOKEN = 3
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * An [OkHttpClient] for streaming generation.
         *
         * The call timeout is disabled: it would bound the entire streamed
         * response, aborting long generations that are proceeding normally. The
         * read timeout catches genuine stalls, which is the correct instrument
         * because it measures inactivity rather than total duration.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
