package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun aSizeReadsAsOneAPersonWouldSayOutLoud() {
        assertEquals("0 B", formatAttachmentSize(0))
        assertEquals("48 B", formatAttachmentSize(48))
        assertEquals("1 KB", formatAttachmentSize(1024))
        assertEquals("812 KB", formatAttachmentSize(831488))
        assertEquals("1.5 MB", formatAttachmentSize(1572864))
        assertEquals("12.4 MB", formatAttachmentSize(12999999))
    }

    @Test
    fun aTrailingPointZeroIsDropped() {
        assertEquals("10 MB", formatAttachmentSize(MAX_ATTACHMENT_BYTES))
        assertEquals("1 MB", formatAttachmentSize(1024 * 1024))
    }

    @Test
    fun aNegativeSizeDoesNotProduceANegativeString() {
        assertEquals("0 B", formatAttachmentSize(-1))
    }

    @Test
    fun aFileInsideTheCapIsNotRefused() {
        assertNull(attachmentRejection("a.png", 1))
        // The cap is inclusive — the core accepts exactly this many.
        assertNull(attachmentRejection("a.png", MAX_ATTACHMENT_BYTES))
    }

    @Test
    fun aRefusalNamesTheFileItsSizeAndTheLimit() {
        assertEquals(
            "\"holiday.mov\" is 12.4 MB — attachments are limited to 10 MB.",
            attachmentRejection("  holiday.mov  ", 12999999),
        )
    }

    @Test
    fun aCaptureWithNoNameStillGetsASentence() {
        assertTrue(
            attachmentRejection("", MAX_ATTACHMENT_BYTES + 1)!!
                .startsWith("That file is 10 MB"),
        )
    }

    @Test
    fun anEmptyPickIsRefusedBeforeAnythingIsEncrypted() {
        // A cancelled camera hands back a zero-byte placeholder; sending it
        // produces an attachment the recipient cannot open.
        assertEquals(
            "\"cap.jpg\" is empty — there is nothing to send.",
            attachmentRejection("cap.jpg", 0),
        )
    }

    @Test
    fun theOtherRoadHasNoCeilingToRefuseAgainst() {
        // The 10 MB limit is what the hosted path can encrypt and what a Blossom
        // operator will take; neither applies device to device.
        assertNull(peerToPeerAttachmentRejection("holiday.mp4", MAX_ATTACHMENT_BYTES + 1))
        assertNull(peerToPeerAttachmentRejection("holiday.mp4", 4L * 1024 * 1024 * 1024))
    }

    @Test
    fun anEmptyFileIsStillRefusedOnTheOtherRoad() {
        // Not a size problem: a cancelled camera hands back zero bytes, and they
        // are unopenable however they travel.
        assertEquals(
            "\"cap.jpg\" is empty — there is nothing to send.",
            peerToPeerAttachmentRejection("cap.jpg", 0),
        )
        assertEquals(
            "That file is empty — there is nothing to send.",
            peerToPeerAttachmentRejection("  ", -1),
        )
    }

    @Test
    fun thePreviewKindIsWhatTheSheetRenders() {
        assertEquals(AttachmentPreviewKind.Image, attachmentPreviewKind("image/png"))
        assertEquals(AttachmentPreviewKind.Video, attachmentPreviewKind("VIDEO/MP4"))
        assertEquals(AttachmentPreviewKind.Audio, attachmentPreviewKind("audio/aac"))
        assertEquals(AttachmentPreviewKind.File, attachmentPreviewKind("application/pdf"))
        assertEquals(AttachmentPreviewKind.File, attachmentPreviewKind(""))
    }

    @Test
    fun thePreviewDetailIsTheNameAndTheSize() {
        assertEquals(
            "IMG_20260731_114233.png · 1.5 MB",
            attachmentPreviewDetail("IMG_20260731_114233.png", 1572864),
        )
        assertEquals("48 B", attachmentPreviewDetail("  ", 48))
    }

    @Test
    fun aCaptionIsTrimmedAndCappedWhateverTheSource() {
        assertEquals("at the gate", normalizeCaption("  at the gate  "))
        assertEquals(
            MAX_CAPTION_LENGTH,
            normalizeCaption("x".repeat(MAX_CAPTION_LENGTH + 40)).length,
        )
    }

    @Test
    fun thePreviewPictureIs52PercentOfTheViewportFlooredAndCapped() {
        assertEquals(312f, attachmentPreviewMediaHeight(600f), 0.001f)
        assertEquals(260f, attachmentPreviewMediaHeight(500f), 0.001f)
        // Capped, so a sheet on a big screen still reads as a sheet.
        assertEquals(420f, attachmentPreviewMediaHeight(1600f), 0.001f)
        // Floored, so a short window still shows a recognisable picture — below
        // this it scrolls rather than shrinking further.
        assertEquals(120f, attachmentPreviewMediaHeight(200f), 0.001f)
        assertEquals(120f, attachmentPreviewMediaHeight(0f), 0.001f)
    }
}
