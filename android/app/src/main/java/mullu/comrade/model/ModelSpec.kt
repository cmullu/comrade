package mullu.comrade.model

import java.io.File

/**
 * How a downloaded artifact turns into an installed model on disk.
 *
 * The two on-device models Comrade uses are packaged differently — the speech
 * model ships as a zip of a directory tree, a companion LLM ships as one
 * weights file — so the shared installer branches on this rather than growing
 * a second pipeline.
 */
/**
 * Whole-percent progress for a download of [total] bytes, clamped to 0..100 —
 * 0 when the size isn't known yet. Kept free of Android types so the
 * notification's progress bar arithmetic is unit-tested rather than eyeballed.
 */
fun downloadPercent(read: Long, total: Long): Int =
    if (total <= 0L) 0 else ((read.coerceAtLeast(0L) * 100L) / total).coerceIn(0L, 100L).toInt()

enum class ModelPackaging {
    /** A zip whose contents (possibly under one wrapper folder) become a directory. */
    ZIP_DIRECTORY,

    /** A single file (e.g. quantised weights) installed verbatim. */
    SINGLE_FILE,
}

/**
 * Everything the shared downloader needs to fetch, verify and install one
 * on-device model. Pure data — no Android types — so the catalog and the
 * validation rules are unit-testable on the host JVM.
 *
 * `url`/`sha256` are a *pinned pair*: a download that hashes to anything else
 * is deleted rather than installed. A spec with either field blank is
 * [configured] == false and the UI must not offer it — see [ModelCatalog].
 */
data class ModelSpec(
    /** Stable id used by intents, notification ids and state lookup. */
    val id: String,
    /** Human-readable name for prompts and notifications ("speech model"). */
    val displayName: String,
    val url: String,
    val sha256: String,
    /** Download size in bytes: the progress denominator until the server reports one. */
    val downloadBytes: Long,
    val packaging: ModelPackaging,
    /** Child of `filesDir` the model installs to — a directory or a file, per [packaging]. */
    val installName: String,
    /**
     * For [ModelPackaging.ZIP_DIRECTORY], entries that must exist inside the
     * installed directory for it to count as a real model (the speech model's
     * `am/` + `conf/`). Empty for single-file models, which are validated by
     * existence and non-emptiness instead.
     */
    val requiredEntries: List<String> = emptyList(),
    /**
     * Which main-navigation tab the "download finished" notification returns
     * to, or null to just open the app where the user left it.
     */
    val returnTab: String? = null,
) {
    /**
     * Whether this model has a pinned source to download from. False is a
     * deliberate, meaningful state: it means the owner has not yet chosen (or
     * pinned the checksum of) the artifact, and every entry point must treat
     * the model as unavailable rather than fetching something unverified.
     */
    val configured: Boolean
        get() = url.isNotBlank() && sha256.isNotBlank() && downloadBytes > 0

    /** Where this model lives once installed, under [filesDir]. */
    fun installTarget(filesDir: File): File = File(filesDir, installName)

    /** Scratch path for the in-flight download, under [cacheDir]. */
    fun downloadCache(cacheDir: File): File = File(cacheDir, "$id-model.part")

    /** Staging path the archive extracts into before the atomic swap, under [filesDir]. */
    fun stagingTarget(filesDir: File): File = File(filesDir, "$installName.staging")

    /**
     * Whether [target] (an [installTarget] or a freshly extracted staging
     * dir) holds something this spec would accept as installed.
     */
    fun looksInstalled(target: File): Boolean = when (packaging) {
        ModelPackaging.ZIP_DIRECTORY ->
            target.isDirectory && requiredEntries.all { File(target, it).exists() }
        ModelPackaging.SINGLE_FILE -> target.isFile && target.length() > 0
    }
}
