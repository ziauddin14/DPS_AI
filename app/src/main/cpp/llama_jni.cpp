/*
 * Project Falcon — DPS Android Client
 * Native side of LlamaCppBridge.
 *
 * ## Purpose
 * The C++ half of the JNI seam declared in
 * `com.softwaremine.dps.data.runtime.llamacpp.LlamaCppBridge`. Every function
 * here has a matching `external fun` on the Kotlin side, and the signatures
 * were taken from `javap -s` on the compiled Day 02 class rather than inferred.
 *
 * ## Symbol naming — why `jobject` and not `jclass`
 * `LlamaCppBridge` is a Kotlin `object`, and its `external fun` members compile
 * to *instance* methods on the singleton (`public final native ...`), not static
 * ones. JNI therefore passes the singleton instance as the second argument.
 * Declaring these with `jclass` would still link, but is a lie about the ABI and
 * breaks the moment anyone touches the receiver.
 *
 * ## Threading contract (mirrors the Kotlin documentation)
 * `loadModel`, `freeModel`, `generate` and `tokenCount` all arrive on the
 * inference dispatcher, which is limited to parallelism 1, so they need no
 * internal locking against each other.
 *
 * `cancel` is the sole exception: it is called from a *different* thread while
 * `generate` is blocked inside its decode loop. It therefore touches nothing but
 * an atomic flag and makes no JNI calls of its own.
 *
 * ## Scope note (Day 03)
 * Model download and GGUF execution are explicitly out of scope for Day 03.
 * This file is the real implementation against llama.cpp's API — not a stub —
 * but it is exercised today only along paths that do not require a GGUF file on
 * device.
 *
 * llama.cpp pinned at release b10246 (39eab74a05d3e68ac822b6dc6cd78c90cb985c19).
 * API verified against that commit's `include/llama.h`.
 */

#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>

#include "llama.h"

#define LOG_TAG "DPS/llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// Finish codes — MUST mirror LlamaCppBridge.FinishCode exactly.
// Verified against `javap -constants` on the compiled Kotlin class.
// ---------------------------------------------------------------------------
constexpr jint kFinishEndOfTurn   = 0;
constexpr jint kFinishStopSeq     = 1;
constexpr jint kFinishMaxTokens   = 2;
constexpr jint kFinishCancelled   = 3;
constexpr jint kFinishError       = -1;

// ---------------------------------------------------------------------------
// Backend lifecycle
// ---------------------------------------------------------------------------

std::once_flag g_backend_once;

void ensure_backend_initialised() {
    std::call_once(g_backend_once, []() {
        llama_backend_init();
        // Route llama.cpp's internal logging into logcat, and drop anything
        // below WARN. The default writes every tensor-load line to stderr,
        // which on Android is both invisible and expensive.
        llama_log_set(
            [](ggml_log_level level, const char * text, void * /*user_data*/) {
                if (level >= GGML_LOG_LEVEL_ERROR) {
                    __android_log_print(ANDROID_LOG_ERROR, "DPS/llama", "%s", text);
                } else if (level >= GGML_LOG_LEVEL_WARN) {
                    __android_log_print(ANDROID_LOG_WARN, "DPS/llama", "%s", text);
                }
            },
            nullptr);
        LOGI("llama.cpp backend initialised");
    });
}

// ---------------------------------------------------------------------------
// Session — everything owned by one loaded model
// ---------------------------------------------------------------------------

/**
 * Native state behind the opaque `jlong` handle returned by loadModel.
 *
 * Ownership is strict: exactly one Session per handle, destroyed exactly once
 * by freeModel. The Kotlin side never lets a handle escape
 * LlamaCppRuntimeProvider, so use-after-free is prevented structurally rather
 * than by convention.
 */
struct Session {
    llama_model *       model = nullptr;
    llama_context *     ctx   = nullptr;
    const llama_vocab * vocab = nullptr;

    /**
     * Set from another thread by cancel(); read by the generation loop.
     *
     * Atomic because the write genuinely races with the read — this is the one
     * piece of cross-thread state in the file.
     */
    std::atomic<bool> cancel_requested{false};

    ~Session() {
        // Order matters: the context references the model.
        if (ctx != nullptr) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
    }
};

Session * as_session(jlong handle) {
    return reinterpret_cast<Session *>(handle);
}

/**
 * Abort hook handed to llama.cpp so `llama_decode` can be interrupted.
 *
 * ## Why this exists
 * The cancellation flag is otherwise only examined between tokens, which means
 * a cancel cannot take effect during prompt ingestion — and on this hardware
 * ingestion is the *dominant* cost. Profiling caught a generation that sat
 * inside `llama_decode` for 13 seconds after being cancelled, producing zero
 * tokens and holding the inference thread, which in turn stalled the
 * subsequent unload for 10 seconds.
 *
 * ggml calls this between graph nodes, so it must be cheap and must not block.
 * Reading one relaxed atomic satisfies both. Returning `true` aborts the decode.
 */
bool abort_if_cancelled(void * data) {
    auto * session = static_cast<Session *>(data);
    return session != nullptr &&
           session->cancel_requested.load(std::memory_order_relaxed);
}

// ---------------------------------------------------------------------------
// UTF-8 handling
//
// This is subtler than it looks and is a genuine source of crashes.
//
// 1. llama.cpp emits *token pieces*, which are byte fragments. A single
//    multi-byte UTF-8 character can be split across two tokens, so a piece is
//    not guaranteed to be valid UTF-8 on its own.
//
// 2. `NewStringUTF` expects Java's *modified* UTF-8, which differs from real
//    UTF-8 for characters outside the BMP: modified UTF-8 encodes them as a
//    surrogate pair (2x3 bytes) rather than one 4-byte sequence. Handing real
//    4-byte UTF-8 to NewStringUTF is undefined behaviour and in practice
//    garbles or aborts.
//
// Both are handled here: incomplete trailing sequences are withheld until
// completed, and conversion goes UTF-8 -> UTF-16 explicitly so `NewString` can
// be used instead of `NewStringUTF`.
// ---------------------------------------------------------------------------

/** Length in bytes of the UTF-8 sequence starting with `lead`, or 0 if invalid. */
int utf8_sequence_length(unsigned char lead) {
    if ((lead & 0x80u) == 0x00u) return 1;
    if ((lead & 0xE0u) == 0xC0u) return 2;
    if ((lead & 0xF0u) == 0xE0u) return 3;
    if ((lead & 0xF8u) == 0xF0u) return 4;
    return 0;
}

/**
 * Number of trailing bytes that form an incomplete UTF-8 sequence.
 *
 * Those bytes must be held back and prepended to the next token, or the user
 * sees replacement characters mid-word.
 */
size_t incomplete_tail_length(const std::string & bytes) {
    const size_t n = bytes.size();
    // A sequence is at most 4 bytes, so only the last 3 can be incomplete.
    const size_t look_back = n < 3 ? n : 3;
    for (size_t back = 1; back <= look_back; ++back) {
        const auto lead = static_cast<unsigned char>(bytes[n - back]);
        const int  len  = utf8_sequence_length(lead);
        if (len == 0) continue;              // continuation byte, keep scanning
        if (len > static_cast<int>(back)) {
            return back;                     // starts a sequence that is not finished
        }
        break;                               // complete sequence ends the string
    }
    return 0;
}

/** Converts real UTF-8 to UTF-16, substituting U+FFFD for malformed input. */
std::vector<jchar> utf8_to_utf16(const std::string & input) {
    std::vector<jchar> out;
    out.reserve(input.size());

    size_t i = 0;
    while (i < input.size()) {
        const auto lead = static_cast<unsigned char>(input[i]);
        const int  len  = utf8_sequence_length(lead);

        if (len == 0 || i + static_cast<size_t>(len) > input.size()) {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }

        uint32_t cp = 0;
        switch (len) {
            case 1: cp = lead; break;
            case 2: cp = lead & 0x1Fu; break;
            case 3: cp = lead & 0x0Fu; break;
            default: cp = lead & 0x07u; break;
        }

        bool valid = true;
        for (int k = 1; k < len; ++k) {
            const auto cont = static_cast<unsigned char>(input[i + k]);
            if ((cont & 0xC0u) != 0x80u) { valid = false; break; }
            cp = (cp << 6) | (cont & 0x3Fu);
        }

        if (!valid) {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }

        if (cp <= 0xFFFF) {
            out.push_back(static_cast<jchar>(cp));
        } else {
            // Outside the BMP: emit a surrogate pair.
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
        i += static_cast<size_t>(len);
    }
    return out;
}

/** Builds a jstring from real UTF-8 without going through modified UTF-8. */
jstring to_jstring(JNIEnv * env, const std::string & utf8) {
    const std::vector<jchar> utf16 = utf8_to_utf16(utf8);
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

/** Reads a jstring into a std::string as real UTF-8. */
std::string from_jstring(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

// ---------------------------------------------------------------------------
// Sampling
// ---------------------------------------------------------------------------

/**
 * Builds a sampler chain from the parameters ModelConfig supplies.
 *
 * Order is significant and follows llama.cpp convention: penalties, then
 * truncation (top-k, top-p), then temperature, then a final selector.
 *
 * `temperature <= 0` selects greedy decoding rather than dividing by zero. That
 * is the path ModelConfig.DETERMINISTIC relies on for reproducible tests.
 */
llama_sampler * build_sampler(float temperature,
                              float top_p,
                              int32_t top_k,
                              float repeat_penalty,
                              uint32_t seed) {
    auto params = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(params);

    if (repeat_penalty != 1.0f) {
        llama_sampler_chain_add(
            chain,
            llama_sampler_init_penalties(/*penalty_last_n=*/64,
                                         /*penalty_repeat=*/repeat_penalty,
                                         /*penalty_freq=*/0.0f,
                                         /*penalty_present=*/0.0f));
    }

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        return chain;
    }

    if (top_k > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    }
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, /*min_keep=*/1));
    }
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    return chain;
}

/** Tokenises `text`, returning the tokens or an empty vector on failure. */
std::vector<llama_token> tokenize(const llama_vocab * vocab,
                                  const std::string & text,
                                  bool add_special) {
    // Negative return is the required capacity — the documented two-call idiom.
    const int32_t needed = -llama_tokenize(vocab,
                                           text.data(),
                                           static_cast<int32_t>(text.size()),
                                           nullptr,
                                           0,
                                           add_special,
                                           /*parse_special=*/true);
    if (needed <= 0) return {};

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t written = llama_tokenize(vocab,
                                           text.data(),
                                           static_cast<int32_t>(text.size()),
                                           tokens.data(),
                                           needed,
                                           add_special,
                                           /*parse_special=*/true);
    if (written < 0) return {};
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

/** Converts one token to its text piece. */
std::string token_to_text(const llama_vocab * vocab, llama_token token) {
    char stack_buf[128];
    int32_t n = llama_token_to_piece(vocab, token, stack_buf, sizeof(stack_buf),
                                     /*lstrip=*/0, /*special=*/false);
    if (n >= 0) {
        return std::string(stack_buf, static_cast<size_t>(n));
    }
    // Negative means the buffer was too small; -n is the required size.
    std::vector<char> heap_buf(static_cast<size_t>(-n));
    n = llama_token_to_piece(vocab, token, heap_buf.data(),
                             static_cast<int32_t>(heap_buf.size()),
                             /*lstrip=*/0, /*special=*/false);
    if (n < 0) return {};
    return std::string(heap_buf.data(), static_cast<size_t>(n));
}

// ---------------------------------------------------------------------------
// Profiling
//
// Separates model computation from everything around it. Without this split a
// slow generation is indistinguishable from a fast one being throttled by
// synchronisation, and optimising the wrong half is the usual outcome.
//
// `callback_us` is the important one: it covers the JNI upcall and the channel
// send, i.e. every microsecond the native loop spends waiting on the Kotlin
// side rather than computing.
// ---------------------------------------------------------------------------

using clock_type = std::chrono::steady_clock;

inline int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               clock_type::now().time_since_epoch())
        .count();
}

struct GenerationProfile {
    int64_t tokenize_us      = 0;  // prompt -> token ids
    int64_t prompt_decode_us = 0;  // prompt ingestion (KV cache fill)
    int64_t sample_us        = 0;  // logits -> token id, cumulative
    int64_t detokenize_us    = 0;  // token id -> text, cumulative
    int64_t callback_us      = 0;  // JNI upcall + channel send, cumulative
    int64_t decode_us        = 0;  // per-token forward pass, cumulative
    int64_t first_token_us   = 0;  // entry -> first token handed to Kotlin
    int64_t total_us         = 0;
    int32_t tokens           = 0;

    void log() const {
        const int64_t accounted =
            tokenize_us + prompt_decode_us + sample_us + detokenize_us + callback_us + decode_us;
        LOGI("PROFILE tokenize=%.1fms prompt_decode=%.1fms decode=%.1fms "
             "sample=%.1fms detokenize=%.1fms callback=%.1fms "
             "first_token=%.1fms total=%.1fms unaccounted=%.1fms tokens=%d",
             tokenize_us / 1000.0, prompt_decode_us / 1000.0, decode_us / 1000.0,
             sample_us / 1000.0, detokenize_us / 1000.0, callback_us / 1000.0,
             first_token_us / 1000.0, total_us / 1000.0,
             (total_us - accounted) / 1000.0, tokens);
        if (tokens > 0) {
            LOGI("PROFILE per_token decode=%.1fms callback=%.1fms sample=%.1fms",
                 decode_us / 1000.0 / tokens,
                 callback_us / 1000.0 / tokens,
                 sample_us / 1000.0 / tokens);
        }
    }
};

/** True when `text` ends with any entry in `stops`. */
bool ends_with_any(const std::string & text, const std::vector<std::string> & stops) {
    for (const auto & stop : stops) {
        if (!stop.empty() && text.size() >= stop.size() &&
            text.compare(text.size() - stop.size(), stop.size(), stop) == 0) {
            return true;
        }
    }
    return false;
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI surface
//
// Signatures verified with `javap -s`:
//   loadModel  (Ljava/lang/String;III)J
//   freeModel  (J)V
//   generate   (JLjava/lang/String;IFFIFI[Ljava/lang/String;L...TokenCallback;)I
//   tokenCount (JLjava/lang/String;)I
//   cancel     (J)V
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_softwaremine_dps_data_runtime_llamacpp_LlamaCppBridge_loadModel(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring model_path,
        jint    context_length,
        jint    thread_count,
        jint    gpu_layers) {

    ensure_backend_initialised();

    const std::string path = from_jstring(env, model_path);
    if (path.empty()) {
        LOGE("loadModel: empty model path");
        return 0;
    }

    auto session = std::make_unique<Session>();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = gpu_layers;

    // mmap, explicitly without mlock.
    //
    // mmap keeps resident memory down by letting the kernel page weights in on
    // demand, which is decisive under the 3 GB device budget (ADR-002). mlock
    // is deliberately NOT combined with it: pinning ~1.1 GB of weights so the
    // kernel may never reclaim them is precisely the behaviour that gets an
    // Android app killed under memory pressure, and it defeats the trim-and-
    // reload strategy DpsApplication relies on.
    //
    // b10246 replaced the older `use_mmap` / `use_mlock` booleans with this
    // enum; LLAMA_LOAD_MODE_MMAP is mmap-only, MMAP_MLOCK would be both.
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

    const int64_t model_load_start = now_us();
    session->model = llama_model_load_from_file(path.c_str(), model_params);
    const int64_t model_load_us = now_us() - model_load_start;

    if (session->model == nullptr) {
        LOGE("loadModel: llama_model_load_from_file failed");
        return 0;
    }

    session->vocab = llama_model_get_vocab(session->model);
    if (session->vocab == nullptr) {
        LOGE("loadModel: model has no vocab");
        return 0;   // unique_ptr frees the model
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = static_cast<uint32_t>(context_length);
    ctx_params.n_threads       = thread_count;
    ctx_params.n_threads_batch = thread_count;
    // Prompt ingestion is batched; a full-context batch would allocate far more
    // scratch memory than a phone can spare.
    ctx_params.n_batch         = 512;

    // Context creation is where the KV cache is allocated — sized by n_ctx.
    const int64_t ctx_init_start = now_us();
    session->ctx = llama_init_from_model(session->model, ctx_params);
    const int64_t ctx_init_us = now_us() - ctx_init_start;

    if (session->ctx == nullptr) {
        LOGE("loadModel: llama_init_from_model failed");
        return 0;   // unique_ptr frees the model
    }

    // Makes llama_decode interruptible, including during prompt ingestion.
    llama_set_abort_callback(session->ctx, abort_if_cancelled, session.get());

    LOGI("PROFILE load model_mmap=%.1fms kv_context_init=%.1fms n_ctx=%d threads=%d gpu_layers=%d",
         model_load_us / 1000.0, ctx_init_us / 1000.0,
         context_length, thread_count, gpu_layers);

    // Ownership transfers to the Kotlin side, which must call freeModel.
    return reinterpret_cast<jlong>(session.release());
}

JNIEXPORT void JNICALL
Java_com_softwaremine_dps_data_runtime_llamacpp_LlamaCppBridge_freeModel(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;               // documented as safe
    delete as_session(handle);
    LOGI("freeModel: released");
}

JNIEXPORT jint JNICALL
Java_com_softwaremine_dps_data_runtime_llamacpp_LlamaCppBridge_tokenCount(
        JNIEnv * env, jobject /*thiz*/, jlong handle, jstring text) {

    Session * session = as_session(handle);
    if (session == nullptr || session->vocab == nullptr) return -1;

    const std::string input = from_jstring(env, text);
    if (input.empty()) return 0;

    const int32_t needed = -llama_tokenize(session->vocab,
                                           input.data(),
                                           static_cast<int32_t>(input.size()),
                                           nullptr, 0,
                                           /*add_special=*/false,
                                           /*parse_special=*/true);
    return needed < 0 ? -1 : needed;
}

JNIEXPORT void JNICALL
Java_com_softwaremine_dps_data_runtime_llamacpp_LlamaCppBridge_cancel(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    // Called from a different thread than generate(). Touches only the atomic
    // flag and makes no JNI calls, which is what makes that safe.
    Session * session = as_session(handle);
    if (session != nullptr) {
        session->cancel_requested.store(true, std::memory_order_relaxed);
    }
}

JNIEXPORT jint JNICALL
Java_com_softwaremine_dps_data_runtime_llamacpp_LlamaCppBridge_generate(
        JNIEnv *      env,
        jobject       /*thiz*/,
        jlong         handle,
        jstring       prompt,
        jint          max_tokens,
        jfloat        temperature,
        jfloat        top_p,
        jint          top_k,
        jfloat        repeat_penalty,
        jint          seed,
        jobjectArray  stop_sequences,
        jobject       callback) {

    Session * session = as_session(handle);
    if (session == nullptr || session->ctx == nullptr || session->vocab == nullptr) {
        LOGE("generate: invalid handle");
        return kFinishError;
    }

    session->cancel_requested.store(false, std::memory_order_relaxed);

    // --- resolve the callback method once, not per token ---
    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) return kFinishError;
    jmethodID on_token = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)Z");
    if (on_token == nullptr) {
        LOGE("generate: TokenCallback.onToken not found");
        return kFinishError;
    }

    // --- marshal stop sequences ---
    std::vector<std::string> stops;
    if (stop_sequences != nullptr) {
        const jsize count = env->GetArrayLength(stop_sequences);
        stops.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            auto item = static_cast<jstring>(env->GetObjectArrayElement(stop_sequences, i));
            if (item != nullptr) {
                stops.push_back(from_jstring(env, item));
                env->DeleteLocalRef(item);
            }
        }
    }

    GenerationProfile profile;
    const int64_t generate_entry_us = now_us();

    const std::string prompt_text = from_jstring(env, prompt);

    const int64_t tokenize_start = now_us();
    std::vector<llama_token> prompt_tokens =
        tokenize(session->vocab, prompt_text, /*add_special=*/true);
    profile.tokenize_us = now_us() - tokenize_start;
    if (prompt_tokens.empty()) {
        LOGE("generate: tokenisation produced no tokens");
        return kFinishError;
    }

    const auto n_ctx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    if (static_cast<int32_t>(prompt_tokens.size()) >= n_ctx) {
        // Reported rather than truncated: silently dropping the head of the
        // prompt removes the system persona and produces confidently wrong
        // answers, which for a secretary is worse than an honest failure.
        LOGE("generate: prompt %zu tokens exceeds context %d",
             prompt_tokens.size(), n_ctx);
        return kFinishError;
    }

    // Fresh conversation state per call. The Kotlin layer rebuilds the whole
    // prompt each turn, so carrying KV cache across calls would double-count
    // history. KV reuse is a deliberate future optimisation, not an oversight.
    llama_memory_clear(llama_get_memory(session->ctx), /*data=*/true);

    llama_sampler * sampler = build_sampler(temperature, top_p, top_k, repeat_penalty,
                                            static_cast<uint32_t>(seed));
    if (sampler == nullptr) return kFinishError;

    // Guarantees the chain is freed on every exit path below.
    struct SamplerGuard {
        llama_sampler * s;
        ~SamplerGuard() { if (s != nullptr) llama_sampler_free(s); }
    } sampler_guard{sampler};

    // --- ingest the prompt (fills the KV cache) ---
    const int64_t prompt_decode_start = now_us();
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(),
                                            static_cast<int32_t>(prompt_tokens.size()));
    const int prompt_rc = llama_decode(session->ctx, batch);
    profile.prompt_decode_us = now_us() - prompt_decode_start;

    if (prompt_rc != 0) {
        // A non-zero return here is ambiguous: it means either a genuine
        // failure or that the abort callback fired. The cancellation flag
        // disambiguates, and reporting the difference matters — a cancel is a
        // normal user action, an error is not.
        if (session->cancel_requested.load(std::memory_order_relaxed)) {
            LOGI("generate: cancelled during prompt ingestion after %.1fms",
                 profile.prompt_decode_us / 1000.0);
            return kFinishCancelled;
        }
        LOGE("generate: prompt decode failed (rc=%d)", prompt_rc);
        return kFinishError;
    }

    // --- generation loop ---
    jint finish_reason = kFinishMaxTokens;
    std::string generated;      // full text, for stop-sequence matching
    std::string pending_bytes;  // incomplete UTF-8 held back between tokens

    for (jint produced = 0; produced < max_tokens; ++produced) {
        if (session->cancel_requested.load(std::memory_order_relaxed)) {
            finish_reason = kFinishCancelled;
            break;
        }

        // Samples *and accepts* — llama_sampler_sample does both, so calling
        // llama_sampler_accept here as well would corrupt penalty state.
        const int64_t sample_start = now_us();
        const llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        profile.sample_us += now_us() - sample_start;

        if (llama_vocab_is_eog(session->vocab, token)) {
            finish_reason = kFinishEndOfTurn;
            break;
        }

        const int64_t detok_start = now_us();
        pending_bytes += token_to_text(session->vocab, token);
        profile.detokenize_us += now_us() - detok_start;

        // Emit only complete UTF-8; hold back a split character's tail.
        const size_t tail = incomplete_tail_length(pending_bytes);
        std::string emit  = pending_bytes.substr(0, pending_bytes.size() - tail);
        pending_bytes     = pending_bytes.substr(pending_bytes.size() - tail);

        if (!emit.empty()) {
            generated += emit;

            // Everything between these two timestamps is time the native loop
            // spends waiting on Kotlin — the JNI upcall plus the channel send.
            // If this dominates, the bottleneck is synchronisation, not the model.
            const int64_t callback_start = now_us();

            jstring piece = to_jstring(env, emit);
            if (piece == nullptr) { finish_reason = kFinishError; break; }

            const jboolean keep_going = env->CallBooleanMethod(callback, on_token, piece);
            env->DeleteLocalRef(piece);

            profile.callback_us += now_us() - callback_start;
            profile.tokens++;
            if (profile.first_token_us == 0) {
                profile.first_token_us = now_us() - generate_entry_us;
            }

            // A Kotlin-side exception must abort rather than be swallowed:
            // continuing with a pending exception is undefined behaviour.
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                finish_reason = kFinishError;
                break;
            }
            if (keep_going == JNI_FALSE) {
                finish_reason = kFinishCancelled;
                break;
            }

            if (ends_with_any(generated, stops)) {
                finish_reason = kFinishStopSeq;
                break;
            }
        }

        // Feed the sampled token back in for the next position.
        // This is the actual per-token forward pass — the model computation.
        const int64_t decode_start = now_us();
        llama_token next = token;
        llama_batch step = llama_batch_get_one(&next, 1);
        const int decode_rc = llama_decode(session->ctx, step);
        profile.decode_us += now_us() - decode_start;

        if (decode_rc != 0) {
            if (session->cancel_requested.load(std::memory_order_relaxed)) {
                finish_reason = kFinishCancelled;
            } else {
                LOGE("generate: decode failed at token %d", produced);
                finish_reason = kFinishError;
            }
            break;
        }
    }

    profile.total_us = now_us() - generate_entry_us;
    profile.log();

    LOGI("generate: finished reason=%d chars=%zu", finish_reason, generated.size());
    return finish_reason;
}

}  // extern "C"
