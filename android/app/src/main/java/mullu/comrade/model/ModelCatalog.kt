package mullu.comrade.model

import android.content.Context
import java.io.File

/**
 * The on-device models Comrade can fetch on demand, and the single place their
 * pinned sources live.
 *
 * Both "Hey Comrade" (speech) and Tara (companion) go through **one** download
 * pipeline: this catalog, [ModelInstaller] for fetch/verify/install, and
 * [ModelDownloadService] for the background download with notification
 * progress. They cannot, however, share one *set of weights*: the speech model
 * is a Vosk/Kaldi **recogniser** (audio → text) and a companion model is a
 * **generative** language model (text → text). Neither can do the other's job,
 * so what is shared is the machinery, not the file.
 */
object ModelCatalog {

    const val ID_SPEECH = "speech"
    const val ID_COMPANION = "companion"

    /** Main-nav tab key the companion download returns the user to. */
    const val TAB_TARA = "tara"

    /**
     * The official Vosk small-English recogniser (Apache-2.0), ~40 MB zipped —
     * the model behind "Hey Comrade", tap-to-talk and journal dictation.
     *
     * The sha256 is the same pin as scripts/fetch-vosk-model.sh (see there for
     * how it was verified); a download hashing to anything else is discarded.
     */
    val SPEECH = ModelSpec(
        id = ID_SPEECH,
        displayName = "speech model",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
        downloadBytes = 41_205_931L,
        packaging = ModelPackaging.ZIP_DIRECTORY,
        // Unchanged from the pre-catalog layout so an already-downloaded model
        // on an upgrading install keeps being found.
        installName = "voice-model",
        requiredEntries = listOf("am", "conf"),
        returnTab = null,
    )

    /**
     * Tara's generative companion model — **deliberately unpinned**.
     *
     * Two things must land before this becomes a real offer, and neither is a
     * decision this layer can make:
     *
     *  1. **The model choice (AUDIT §8 OQ9).** Which quantised on-device model
     *     and runtime, at what size/quality trade-off on low-end phones, under
     *     what licence. That is an owner call.
     *  2. **An inference backend.** The `CompanionEngine` seam in
     *     `comrade_core::tara` needs a real on-device implementation; weights
     *     alone do nothing.
     *
     * Until then [ModelSpec.configured] is false, every entry point treats the
     * model as unavailable, and Tara answers with the on-device reflective
     * engine instead. Shipping a guessed URL or an unverified checksum would
     * be worse than shipping nothing: it would either fail every install or,
     * far worse, install something unverified.
     */
    val COMPANION = ModelSpec(
        id = ID_COMPANION,
        displayName = "companion model",
        url = "",
        sha256 = "",
        downloadBytes = 0L,
        packaging = ModelPackaging.SINGLE_FILE,
        installName = "companion-model.bin",
        returnTab = TAB_TARA,
    )

    val all: List<ModelSpec> = listOf(SPEECH, COMPANION)

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    /** Where [spec] installs to for this app's private storage. */
    fun installTarget(context: Context, spec: ModelSpec): File =
        spec.installTarget(context.filesDir)

    /** Whether [spec] is already installed and usable. */
    fun isInstalled(context: Context, spec: ModelSpec): Boolean =
        spec.looksInstalled(installTarget(context, spec))
}
