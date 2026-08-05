package mullu.comrade.together

import android.content.Context
import android.os.Build

/**
 * When Comrade asks to read the music library, and what to do when it may not.
 *
 * Kept apart from [LibraryResolver] because the two answer different questions:
 * that one knows how to look, this one knows whether we are allowed to and
 * whether asking again is worth anything. Compose-free and `MediaStore`-free, so
 * the decision is unit-tested in the JVM lane rather than only on a device.
 *
 * ## Why at most one ask
 * Android stops showing the dialog once someone has refused, and a request made
 * after that returns "denied" without anything appearing on screen. Asking on
 * every `/play` would therefore be a button that silently does nothing, so the
 * ask happens once and every later attempt goes straight to the route that needs
 * no permission at all — the file picker, which SAF grants per file. Nothing in
 * this feature is unreachable without the library; the library only makes it
 * automatic.
 */
object MediaLibraryAccess {

    /** What to do next about looking in the library. */
    enum class Step {
        /** Allowed — run the lookup. */
        Look,

        /** Not allowed, and never asked. Show the system dialog, then retry. */
        Ask,

        /** Not allowed and already asked. Fall back to the file picker and say so. */
        Picker,
    }

    /**
     * The permission that grants an audio-library read on this release.
     *
     * `READ_EXTERNAL_STORAGE` stopped granting media access at Tiramisu and the
     * per-type permissions took over, so asking for the old one on a new phone is
     * a request that can never be granted. Takes the SDK level as an argument
     * rather than reading [Build.VERSION] so both branches are testable.
     */
    fun permissionFor(sdkInt: Int): String =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** The step for a device that is [granted] the read, having [askedBefore] or not. */
    fun next(granted: Boolean, askedBefore: Boolean): Step = when {
        granted -> Step.Look
        askedBefore -> Step.Picker
        else -> Step.Ask
    }

    /** Whether this install has already put the dialog in front of someone. */
    fun asked(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    /**
     * Record that the dialog was shown — called when the request is *answered*,
     * granted or not.
     *
     * Deliberately not conditional on the answer: what makes a second ask
     * pointless is that the question was already put, and a grant that is later
     * revoked in Settings leaves [next] at [Step.Picker], which is the honest
     * outcome. Revoking a permission is a way of saying no.
     */
    fun rememberAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "media_library_access"
    private const val KEY_ASKED = "asked"
}
