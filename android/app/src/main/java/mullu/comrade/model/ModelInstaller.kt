package mullu.comrade.model

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Thrown by [ModelInstaller.fetchAndInstall] when the caller cancels mid-flight. */
class InstallCancelledException : InterruptedIOException("model download cancelled")

/**
 * The pure-JVM half of an on-demand model install, shared by every model in
 * [ModelCatalog]: download, checksum verification, extraction (for archives),
 * layout validation, and an atomic swap into place.
 *
 * Deliberately free of Android types so `ModelInstallerTest` exercises the
 * whole pipeline on the host JVM against `file://` URLs; the Android-facing
 * state machine around it is [ModelDownloadService].
 */
object ModelInstaller {

    /**
     * Run the full pipeline for [spec] using this app's [cacheDir]/[filesDir].
     * See the primitive overload for the guarantees.
     */
    fun fetchAndInstall(
        spec: ModelSpec,
        cacheDir: File,
        filesDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onInstalling: () -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        require(spec.configured) { "model ${spec.id} has no pinned source to download" }
        fetchAndInstall(
            url = URI(spec.url).toURL(),
            expectedSha256 = spec.sha256,
            downloadCache = spec.downloadCache(cacheDir),
            stagingDir = spec.stagingTarget(filesDir),
            installTarget = spec.installTarget(filesDir),
            packaging = spec.packaging,
            requiredEntries = spec.requiredEntries,
            onProgress = onProgress,
            onInstalling = onInstalling,
            isCancelled = isCancelled,
        )
    }

    /**
     * Stream [url] into [downloadCache] (reporting `(bytesRead, totalBytes)` to
     * [onProgress]; total is -1 until the server says), refuse anything whose
     * sha256 differs from [expectedSha256], then install it at
     * [installTarget] — extracting into [stagingDir] first for
     * [ModelPackaging.ZIP_DIRECTORY], moving the file verbatim for
     * [ModelPackaging.SINGLE_FILE].
     *
     * Every failure path deletes the partial artifacts, and [isCancelled]
     * flipping true aborts with [InstallCancelledException] — a verified model
     * either lands complete at [installTarget] or not at all.
     */
    fun fetchAndInstall(
        url: URL,
        expectedSha256: String,
        downloadCache: File,
        stagingDir: File,
        installTarget: File,
        packaging: ModelPackaging = ModelPackaging.ZIP_DIRECTORY,
        requiredEntries: List<String> = emptyList(),
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onInstalling: () -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        try {
            download(url, downloadCache, onProgress, isCancelled)
            onInstalling()
            val actual = sha256Hex(downloadCache)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw IOException("model checksum mismatch — refusing it (expected $expectedSha256, got $actual)")
            }
            if (isCancelled()) throw InstallCancelledException()
            when (packaging) {
                ModelPackaging.ZIP_DIRECTORY ->
                    installArchive(downloadCache, stagingDir, installTarget, requiredEntries)
                ModelPackaging.SINGLE_FILE -> installFile(downloadCache, installTarget)
            }
        } finally {
            downloadCache.delete()
            stagingDir.deleteRecursively()
        }
    }

    private fun installArchive(
        archive: File,
        stagingDir: File,
        installTarget: File,
        requiredEntries: List<String>,
    ) {
        stagingDir.deleteRecursively()
        extractInto(archive, stagingDir)
        if (!requiredEntries.all { File(stagingDir, it).exists() }) {
            throw IOException(
                "downloaded archive is missing ${requiredEntries.joinToString("/")} — not the expected model",
            )
        }
        installTarget.deleteRecursively()
        installTarget.parentFile?.mkdirs()
        if (!stagingDir.renameTo(installTarget)) {
            throw IOException("could not move the model into place")
        }
    }

    private fun installFile(downloaded: File, installTarget: File) {
        if (downloaded.length() == 0L) throw IOException("downloaded model is empty")
        installTarget.delete()
        installTarget.parentFile?.mkdirs()
        // cacheDir and filesDir normally share a partition so the rename is
        // atomic; fall back to copy+delete rather than failing the install if
        // this device puts them apart.
        if (!downloaded.renameTo(installTarget)) {
            downloaded.inputStream().use { input ->
                installTarget.outputStream().use { output -> input.copyTo(output) }
            }
            downloaded.delete()
        }
    }

    private fun download(
        url: URL,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val connection = url.openConnection()
        (connection as? HttpURLConnection)?.apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            val code = responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("model download failed: HTTP $code")
        }
        try {
            val total = connection.contentLengthLong
            dest.parentFile?.mkdirs()
            connection.getInputStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastReported = 0L
                    onProgress(0L, total)
                    while (true) {
                        if (isCancelled()) throw InstallCancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        // Throttle the callback: every 256 KiB is plenty for a progress bar.
                        if (copied - lastReported >= 256 * 1024) {
                            lastReported = copied
                            onProgress(copied, total)
                        }
                    }
                    onProgress(copied, total)
                }
            }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    /** Hex sha256 of [file], streamed. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Extract [zip] into [dest], flattening the single wrapper folder the
     * official Vosk archives put everything under (so
     * `vosk-model-small-en-us-0.15/am/…` lands as `am/…`, matching what
     * scripts/fetch-vosk-model.sh stages into assets). An entry that would
     * resolve outside [dest] (zip-slip) aborts the whole extraction.
     */
    fun extractInto(zip: File, dest: File) {
        ZipFile(zip).use { archive ->
            val entries = archive.entries().toList()
            if (entries.isEmpty()) throw IOException("model archive is empty")
            // Flatten only when every entry sits under one shared root folder.
            val roots = entries.mapTo(HashSet()) { it.name.substringBefore('/') }
            val wrapper = roots.singleOrNull()?.takeIf { root -> entries.none { it.name == root } }
            val strip = wrapper?.length?.plus(1) ?: 0
            val destRoot = dest.canonicalFile
            for (entry in entries) {
                if (entry.name.length <= strip) continue // the wrapper folder itself
                val target = File(destRoot, entry.name.substring(strip))
                if (!target.canonicalPath.startsWith(destRoot.canonicalPath + File.separator)) {
                    throw IOException("archive entry escapes the install dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    if (!target.isDirectory && !target.mkdirs()) {
                        throw IOException("cannot create ${entry.name}")
                    }
                } else {
                    target.parentFile?.let {
                        if (!it.isDirectory && !it.mkdirs()) throw IOException("cannot create ${entry.name}")
                    }
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    /**
     * A directory the Vosk recogniser can plausibly load. Kept as a named
     * helper because [mullu.comrade.voice.VoskModel] probes it directly on the
     * hot path; equivalent to `ModelCatalog.SPEECH.looksInstalled(dir)`.
     */
    fun looksLikeSpeechModel(dir: File): Boolean = ModelCatalog.SPEECH.looksInstalled(dir)
}
