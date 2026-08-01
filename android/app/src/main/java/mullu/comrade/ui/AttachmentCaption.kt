package mullu.comrade.ui

/**
 * Two rules about attachments that every frontend has to agree on, kept pure so
 * they can be tested once and mirrored exactly.
 *
 * The Dart port is `app/lib/src/util/attachment_caption.dart` and the desktop
 * one is `desktop/ui/attachment_caption.mjs`; all three are pinned by mirrored
 * tests, so a change here is a change in three places.
 */

/**
 * The longest caption the core will keep — `MAX_CAPTION_LEN` in
 * `comrade_ui::runtime`, which truncates anything longer.
 */
const val MAX_CAPTION_LENGTH = 512

/**
 * The caption ("tag") a new attachment is sent with.
 *
 * Telegram's rule: whatever is in the composer when you attach becomes the
 * caption, and the box is emptied. This app adds one exception — **not while a
 * reply is pending**. The core cannot carry `reply_to` on a media event, so an
 * attachment sent during a reply is not that reply; taking the text would
 * quietly consume a half-written reply and send it as a photo caption instead.
 */
fun captionForAttachment(draft: String, replyPending: Boolean): String {
    if (replyPending) return ""
    return draft.trim().take(MAX_CAPTION_LENGTH)
}

/**
 * Whether [captionForAttachment] would consume the composer's text — i.e.
 * whether the caller must clear the box after sending.
 */
fun captionConsumesDraft(draft: String, replyPending: Boolean): Boolean =
    !replyPending && draft.isNotBlank()

/**
 * One line describing a media message: what a reply chip shows above the
 * composer, and what a quoted preview shows inside a bubble replying to it.
 *
 * The kind is always named. A caption is *added*, never substituted for it —
 * "📷 Photo" answers "what am I replying to" even when the sender wrote
 * nothing, which is the common case for a photo.
 */
fun mediaQuoteLabel(mimeType: String, caption: String): String {
    val trimmed = caption.trim()
    val head = "${mediaKindGlyph(mimeType)} ${mediaKindLabel(mimeType)}"
    return if (trimmed.isEmpty()) head else "$head · $trimmed"
}

/** "Photo" / "Video" / "Voice message" / "File" for a MIME type. */
fun mediaKindLabel(mimeType: String): String {
    val mime = mimeType.trim().lowercase()
    return when {
        mime.startsWith("image/") -> "Photo"
        mime.startsWith("video/") -> "Video"
        mime.startsWith("audio/") -> "Voice message"
        else -> "File"
    }
}

/** The glyph paired with [mediaKindLabel]. */
fun mediaKindGlyph(mimeType: String): String {
    val mime = mimeType.trim().lowercase()
    return when {
        mime.startsWith("image/") -> "📷"
        mime.startsWith("video/") -> "🎬"
        mime.startsWith("audio/") -> "🎤"
        else -> "📎"
    }
}

/**
 * Whether tapping this attachment should open the full-screen viewer.
 *
 * Photos and videos only: an audio clip has nothing to fill a screen with, and
 * a PDF belongs to whatever app the device has for PDFs.
 */
fun opensFullScreen(mimeType: String): Boolean {
    val mime = mimeType.trim().lowercase()
    return mime.startsWith("image/") || mime.startsWith("video/")
}
