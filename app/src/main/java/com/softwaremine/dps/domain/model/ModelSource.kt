package com.softwaremine.dps.domain.model

/**
 * Where model artifacts are fetched from.
 *
 * ## Purpose
 * Separates *which artifact* a descriptor names from *where it is hosted*, so
 * the hosting decision can change without touching a single line of runtime
 * logic.
 *
 * Day 04 ships with HuggingFace as the source, on the explicit understanding
 * that production must migrate to a controlled mirror before public release
 * (see `docs/TECH-DEBT.md`, item TD-001). That migration must be a one-line
 * change here, not a refactor — which is what this type exists to guarantee.
 *
 * ## The property that matters
 * [ModelDescriptor.downloadUrl] remains a fully resolved absolute URL, exactly
 * as Day 02 defined it. Nothing downstream — [ModelManager],
 * `ModelDownloader`, the engine, the UI — knows a source abstraction exists.
 * They receive a URL and fetch it.
 *
 * Switching hosts therefore means changing [ModelCatalogSource.ACTIVE] and
 * nothing else. The alternative design, threading a source through the download
 * pipeline, would have spread hosting knowledge across four layers to solve a
 * problem that belongs in one.
 *
 * ## Integrity is source-independent
 * Changing the source does **not** change [ModelDescriptor.sha256]. The hash
 * describes the artifact, not where it came from. That is precisely what makes
 * a mirror safe to adopt: a mirror serving different bytes fails verification
 * rather than being silently trusted. The source is a convenience; the checksum
 * is the security boundary.
 *
 * ## Dependencies
 * None. Pure Kotlin.
 */
data class ModelSource(

    /** Stable identifier, e.g. `huggingface`. Used in logs and diagnostics. */
    val id: String,

    /** Human-readable name, safe to show in settings. */
    val displayName: String,

    /**
     * Base URL with no trailing slash.
     *
     * Must serve HTTP `Range` requests: a ~1 GB transfer over a mobile
     * connection will be interrupted, and a host that cannot resume forces a
     * restart from zero. Verified for HuggingFace via `Accept-Ranges: bytes`.
     */
    val baseUrl: String,

    /** What kind of host this is. See [Kind]. */
    val kind: Kind,

    /**
     * Whether fetching requires credentials.
     *
     * `true` for gated repositories such as Gemma and Llama, which require a
     * HuggingFace account and manual licence acceptance. DPS ships no
     * credentials, so gated sources cannot currently be provisioned.
     */
    val requiresAuthentication: Boolean,
) {

    /**
     * Resolves an artifact path against this source into an absolute URL.
     *
     * @param artifactPath repository-relative path, without a leading slash.
     */
    fun resolve(artifactPath: String): String =
        "${baseUrl.trimEnd('/')}/${artifactPath.trimStart('/')}"

    enum class Kind {
        /**
         * A public third-party repository. Convenient, but the operator can
         * retag or withdraw a file at any time — which is why production must
         * not depend on one.
         */
        PUBLIC_REPOSITORY,

        /** Infrastructure under our control. The production target. */
        CONTROLLED_MIRROR,
    }

    companion object {

        /**
         * HuggingFace. The Day 04 source, temporary by decision.
         *
         * `/resolve/main/<path>` returns the file itself rather than the HTML
         * page `/blob/` serves.
         */
        val HUGGING_FACE = ModelSource(
            id = "huggingface",
            displayName = "Hugging Face",
            baseUrl = "https://huggingface.co",
            kind = Kind.PUBLIC_REPOSITORY,
            requiresAuthentication = false,
        )

        /**
         * Builds a controlled-mirror source.
         *
         * The whole of TD-001 is: call this instead of [HUGGING_FACE] in
         * [ModelCatalogSource.ACTIVE]. Checksums stay exactly as they are, and
         * they are what proves the mirror serves the same bytes.
         */
        fun controlledMirror(baseUrl: String, displayName: String = "DPS Mirror") = ModelSource(
            id = "dps-mirror",
            displayName = displayName,
            baseUrl = baseUrl,
            kind = Kind.CONTROLLED_MIRROR,
            requiresAuthentication = false,
        )
    }
}

/**
 * The source the catalog currently resolves against.
 *
 * Deliberately a single mutable-by-edit constant rather than injected
 * configuration. Hosting is a release-engineering decision that must be visible
 * in a diff and reviewable, not something alterable at runtime — a
 * remotely-changeable model source would be an obvious attack surface for a
 * product whose entire premise is on-device privacy.
 */
object ModelCatalogSource {

    /**
     * ⚠ TD-001: HuggingFace is temporary. Production releases must migrate to
     * [ModelSource.controlledMirror] before public deployment.
     *
     * Approved for Day 04 by the Product Owner on 2026-08-04 on that condition.
     */
    val ACTIVE: ModelSource = ModelSource.HUGGING_FACE
}
