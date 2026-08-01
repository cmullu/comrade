package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two attachment rules: what a new attachment is captioned with, and
 * how one reads when something quotes it.
 *
 * Mirrored in `app/test/attachment_caption_test.dart` and
 * `desktop/ui/attachment_caption.test.mjs` — same cases, same answers.
 */
class AttachmentCaptionTest {

    @Test
    fun theComposerTextBecomesTheCaption() {
        assertEquals(
            "at the station",
            captionForAttachment("  at the station  ", replyPending = false),
        )
        assertTrue(captionConsumesDraft("  at the station  ", replyPending = false))
    }

    @Test
    fun anEmptyComposerSendsAnUntaggedAttachment() {
        assertEquals("", captionForAttachment("   ", replyPending = false))
        // Nothing was taken, so nothing should be cleared.
        assertFalse(captionConsumesDraft("   ", replyPending = false))
    }

    @Test
    fun aPendingReplyKeepsItsDraft() {
        // The core cannot tag a media event as a reply, so an attachment sent
        // mid-reply is a separate message. Eating the reply text as its caption
        // would both lose the reply and mislabel the photo.
        assertEquals("", captionForAttachment("yes, exactly", replyPending = true))
        assertFalse(captionConsumesDraft("yes, exactly", replyPending = true))
    }

    @Test
    fun isCappedWhereTheCoreCapsIt() {
        val long = "x".repeat(MAX_CAPTION_LENGTH + 40)
        assertEquals(MAX_CAPTION_LENGTH, captionForAttachment(long, replyPending = false).length)
    }

    @Test
    fun theKindIsNamedEvenWithNoCaption() {
        assertEquals("📷 Photo", mediaQuoteLabel("image/jpeg", ""))
        assertEquals("🎬 Video", mediaQuoteLabel("video/mp4", ""))
        assertEquals("🎤 Voice message", mediaQuoteLabel("audio/aac", ""))
        assertEquals("📎 File", mediaQuoteLabel("application/pdf", ""))
    }

    @Test
    fun theCaptionIsAddedToTheKindNotSubstitutedForIt() {
        assertEquals(
            "📷 Photo · the platform",
            mediaQuoteLabel("image/png", "  the platform  "),
        )
    }

    @Test
    fun anUnknownOrEmptyMimeStillReadsAsSomething() {
        assertEquals("📎 File", mediaQuoteLabel("", ""))
        assertEquals("📷 Photo", mediaQuoteLabel("IMAGE/PNG", ""))
    }

    @Test
    fun onlyPhotosAndVideosOpenFullScreen() {
        assertTrue(opensFullScreen("image/webp"))
        assertTrue(opensFullScreen("video/mp4"))
        assertFalse(opensFullScreen("audio/aac"))
        assertFalse(opensFullScreen("application/pdf"))
    }
}
