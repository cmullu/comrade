package mullu.comrade.ui

/**
 * The rules about attachments that every frontend has to agree on, kept pure so
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
 * The largest attachment the core will accept — `MAX_MEDIA_BYTES` in
 * `comrade_core::media`.
 */
const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024

/**
 * A caption as the core will store it: trimmed, and no longer than
 * [MAX_CAPTION_LENGTH].
 *
 * Applied on both sides of the preview sheet — to the text seeded into it and
 * to the text confirmed out of it — so what the sender reads back in their own
 * thread is exactly what the recipient gets.
 */
fun normalizeCaption(text: String): String = text.trim().take(MAX_CAPTION_LENGTH)

/**
 * The caption ("tag") a new attachment *starts* with, before the sender confirms
 * it in the preview sheet.
 *
 * Telegram's rule: whatever is in the composer when you attach becomes the
 * caption, and the box is emptied. This app adds one exception — **not while a
 * reply is pending**. The core cannot carry `reply_to` on a media event, so an
 * attachment sent during a reply is not that reply; taking the text would
 * quietly consume a half-written reply and send it as a photo caption instead.
 */
fun captionForAttachment(draft: String, replyPending: Boolean): String =
    if (replyPending) "" else normalizeCaption(draft)

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

/**
 * How the preview sheet should render an attachment that has been picked but not
 * yet sent.
 *
 * The three renderers are necessarily different (`BitmapFactory`,
 * `Image.memory`, an `<img>` with an object URL), so what is shared is the
 * *choice* — which is the part that must not drift.
 */
enum class AttachmentPreviewKind {
    /** Show the picture itself, scaled to fit. */
    Image,

    /**
     * Show it in a player where the platform has one **that needs no file** —
     * the desktop's in-memory object URL qualifies, a `VideoView` pointed at a
     * staged temp file does not, because an unsent attachment must not put
     * plaintext on disk (AUDIT S-4). Everywhere else, a card. Never a still
     * frame: extracting one costs a decoder none of the three frontends carries.
     */
    Video,

    /**
     * A card. There is nothing to look at, and the sender just recorded or
     * picked it, so the kind and the length are the whole story.
     */
    Audio,

    /** A card, for everything else. */
    File,
}

fun attachmentPreviewKind(mimeType: String): AttachmentPreviewKind {
    val mime = mimeType.trim().lowercase()
    return when {
        mime.startsWith("image/") -> AttachmentPreviewKind.Image
        mime.startsWith("video/") -> AttachmentPreviewKind.Video
        mime.startsWith("audio/") -> AttachmentPreviewKind.Audio
        else -> AttachmentPreviewKind.File
    }
}

/**
 * "48 B" / "812 KB" / "1.5 MB" / "10 MB".
 *
 * Integer arithmetic throughout, and a trailing `.0` is dropped, so the three
 * ports round the same way instead of inheriting three float formatters.
 */
fun formatAttachmentSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${(bytes + 512) / 1024} KB"
    val tenths = (bytes * 10 + 524288) / (1024 * 1024)
    val whole = tenths / 10
    val frac = tenths % 10
    return if (frac == 0L) "$whole MB" else "$whole.$frac MB"
}

/**
 * Why this file cannot be sent, or null when it can.
 *
 * Checked **before** the preview sheet opens, in every frontend. The cap is
 * enforced in the core either way, but discovering it after composing a caption
 * — which is where every frontend used to discover it — wastes the only work the
 * sender actually did.
 */
fun attachmentRejection(name: String, bytes: Long): String? {
    val trimmed = name.trim()
    val subject = if (trimmed.isEmpty()) "That file" else "\"$trimmed\""
    if (bytes <= 0) return "$subject is empty — there is nothing to send."
    if (bytes > MAX_ATTACHMENT_BYTES) {
        return "$subject is ${formatAttachmentSize(bytes)} — attachments are " +
            "limited to ${formatAttachmentSize(MAX_ATTACHMENT_BYTES)}."
    }
    return null
}

/**
 * Why this file cannot be handed **straight to the other device**, or null when
 * it can.
 *
 * A second function rather than a bigger number inside [attachmentRejection],
 * because the two roads refuse for different reasons and one of them has no
 * ceiling at all: [MAX_ATTACHMENT_BYTES] is the *hosted* limit — what the core
 * can encrypt in one buffer and what a Blossom operator will take — and none of
 * that applies to bytes going device to device. What survives is the empty file,
 * which is not a size problem: a cancelled camera hands back zero bytes, and
 * sending them produces something the recipient cannot open either way.
 *
 * Callers ask `attachment_route_for_bytes` which road a file takes and then this
 * or [attachmentRejection], rather than comparing against a local 10 MB — the
 * threshold belongs to the core that enforces it.
 */
fun peerToPeerAttachmentRejection(name: String, bytes: Long): String? {
    val trimmed = name.trim()
    val subject = if (trimmed.isEmpty()) "That file" else "\"$trimmed\""
    if (bytes <= 0) return "$subject is empty — there is nothing to send."
    return null
}

/**
 * The line under the preview's heading: the file's own name and its size, or
 * just the size for a capture that has no name yet.
 *
 * The name appears *here* and only here. It is deliberately not the caption —
 * see [captionForAttachment] — but it is the one thing that answers "is this the
 * file I meant to pick", which is what a preview is for.
 */
fun attachmentPreviewDetail(name: String, bytes: Long): String {
    val trimmed = name.trim()
    val size = formatAttachmentSize(bytes)
    return if (trimmed.isEmpty()) size else "$trimmed · $size"
}

/**
 * The tallest the preview's picture may be, given the height of the viewport it
 * sits in (in dp here, logical pixels elsewhere).
 *
 * 52% of the viewport, never more than 420 and never less than 120. The
 * *fraction* is what keeps the caption box and Send on screen on a short window —
 * they are the two controls the sheet exists for, and neither may need a
 * scrollbar to reach. The *cap* stops a photo dominating a large window, where a
 * sheet should still read as a sheet and not as the full-screen viewer. The
 * *floor* stops a very short window shrinking the picture to something you cannot
 * recognise, which would defeat the preview entirely — below it the picture
 * scrolls instead.
 *
 * The desktop port spells the same rule in CSS: `clamp(120px, 52vh, 420px)`.
 */
fun attachmentPreviewMediaHeight(viewportHeight: Float): Float {
    val proportional = viewportHeight * 0.52f
    return when {
        proportional < 120f -> 120f
        proportional > 420f -> 420f
        else -> proportional
    }
}
