package mullu.comrade.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * Material glyphs the bottom navigation needs but material-icons-core doesn't
 * ship (chat bubble, article, mic). Inlined as ImageVectors so the app never
 * depends on the multi-megabyte material-icons-extended artifact.
 */

/** Material "chat bubble" (filled). */
val ChatBubbleIcon: ImageVector = materialIcon(name = "Filled.ChatBubble") {
    materialPath {
        moveTo(20.0f, 2.0f)
        horizontalLineTo(4.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(18.0f)
        lineToRelative(4.0f, -4.0f)
        horizontalLineToRelative(14.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        verticalLineTo(4.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        close()
    }
}

/** Material "article" (filled) — the public feed. */
val ArticleIcon: ImageVector = materialIcon(name = "Filled.Article") {
    materialPath {
        moveTo(19.0f, 3.0f)
        horizontalLineTo(5.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(14.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(14.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        verticalLineTo(5.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        close()
        moveTo(14.0f, 17.0f)
        horizontalLineTo(7.0f)
        verticalLineToRelative(-2.0f)
        horizontalLineToRelative(7.0f)
        verticalLineToRelative(2.0f)
        close()
        moveTo(17.0f, 13.0f)
        horizontalLineTo(7.0f)
        verticalLineToRelative(-2.0f)
        horizontalLineToRelative(10.0f)
        verticalLineToRelative(2.0f)
        close()
        moveTo(17.0f, 9.0f)
        horizontalLineTo(7.0f)
        verticalLineTo(7.0f)
        horizontalLineToRelative(10.0f)
        verticalLineToRelative(2.0f)
        close()
    }
}

/** Material "book" (filled) — the private journal. */
val BookIcon: ImageVector = materialIcon(name = "Filled.Book") {
    materialPath {
        moveTo(18.0f, 2.0f)
        horizontalLineTo(6.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(16.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(12.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        verticalLineTo(4.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        close()
        moveTo(6.0f, 4.0f)
        horizontalLineToRelative(5.0f)
        verticalLineToRelative(8.0f)
        lineToRelative(-2.5f, -1.5f)
        lineTo(6.0f, 12.0f)
        verticalLineTo(4.0f)
        close()
    }
}

/** Material "favorite" (filled) — Tara, the reflective companion. */
val HeartIcon: ImageVector = materialIcon(name = "Filled.Favorite") {
    materialPath {
        moveTo(12.0f, 21.35f)
        lineToRelative(-1.45f, -1.32f)
        curveTo(5.4f, 15.36f, 2.0f, 12.28f, 2.0f, 8.5f)
        curveTo(2.0f, 5.42f, 4.42f, 3.0f, 7.5f, 3.0f)
        curveToRelative(1.74f, 0.0f, 3.41f, 0.81f, 4.5f, 2.09f)
        curveTo(13.09f, 3.81f, 14.76f, 3.0f, 16.5f, 3.0f)
        curveTo(19.58f, 3.0f, 22.0f, 5.42f, 22.0f, 8.5f)
        curveToRelative(0.0f, 3.78f, -3.4f, 6.86f, -8.55f, 11.54f)
        lineTo(12.0f, 21.35f)
        close()
    }
}

/** Material "call" (filled) — place a voice call. */
val CallIcon: ImageVector = materialIcon(name = "Filled.Call") {
    materialPath {
        moveTo(6.62f, 10.79f)
        curveToRelative(1.44f, 2.83f, 3.76f, 5.14f, 6.59f, 6.59f)
        lineToRelative(2.2f, -2.2f)
        curveToRelative(0.27f, -0.27f, 0.67f, -0.36f, 1.02f, -0.24f)
        curveToRelative(1.12f, 0.37f, 2.33f, 0.57f, 3.57f, 0.57f)
        curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
        verticalLineTo(20.0f)
        curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
        curveTo(10.29f, 21.0f, 3.0f, 13.71f, 3.0f, 4.0f)
        curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
        horizontalLineToRelative(3.5f)
        curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
        curveToRelative(0.0f, 1.25f, 0.2f, 2.45f, 0.57f, 3.57f)
        curveToRelative(0.11f, 0.35f, 0.03f, 0.74f, -0.25f, 1.02f)
        lineToRelative(-2.2f, 2.2f)
        close()
    }
}

/** Material "call_end" (filled) — hang up / decline. */
val CallEndIcon: ImageVector = materialIcon(name = "Filled.CallEnd") {
    materialPath {
        moveTo(12.0f, 9.0f)
        curveToRelative(-1.6f, 0.0f, -3.15f, 0.25f, -4.6f, 0.72f)
        verticalLineToRelative(3.1f)
        curveToRelative(0.0f, 0.39f, -0.23f, 0.74f, -0.56f, 0.9f)
        curveToRelative(-0.98f, 0.49f, -1.87f, 1.12f, -2.66f, 1.85f)
        curveToRelative(-0.18f, 0.18f, -0.43f, 0.28f, -0.7f, 0.28f)
        curveToRelative(-0.28f, 0.0f, -0.53f, -0.11f, -0.71f, -0.29f)
        lineTo(0.29f, 13.08f)
        curveToRelative(-0.18f, -0.17f, -0.29f, -0.42f, -0.29f, -0.7f)
        curveToRelative(0.0f, -0.28f, 0.11f, -0.53f, 0.29f, -0.71f)
        curveTo(3.34f, 8.78f, 7.46f, 7.0f, 12.0f, 7.0f)
        reflectiveCurveToRelative(8.66f, 1.78f, 11.71f, 4.67f)
        curveToRelative(0.18f, 0.18f, 0.29f, 0.43f, 0.29f, 0.71f)
        curveToRelative(0.0f, 0.28f, -0.11f, 0.53f, -0.29f, 0.71f)
        lineToRelative(-2.48f, 2.48f)
        curveToRelative(-0.18f, 0.18f, -0.43f, 0.29f, -0.71f, 0.29f)
        curveToRelative(-0.27f, 0.0f, -0.52f, -0.11f, -0.7f, -0.28f)
        curveToRelative(-0.79f, -0.73f, -1.68f, -1.36f, -2.66f, -1.85f)
        curveToRelative(-0.33f, -0.16f, -0.56f, -0.5f, -0.56f, -0.9f)
        verticalLineToRelative(-3.1f)
        curveTo(15.15f, 9.25f, 13.6f, 9.0f, 12.0f, 9.0f)
        close()
    }
}

/** Material "videocam" (filled) — place a video call. */
val VideocamIcon: ImageVector = materialIcon(name = "Filled.Videocam") {
    materialPath {
        moveTo(17.0f, 10.5f)
        verticalLineTo(7.0f)
        curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
        horizontalLineTo(4.0f)
        curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
        verticalLineToRelative(10.0f)
        curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
        horizontalLineToRelative(12.0f)
        curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
        verticalLineToRelative(-3.5f)
        lineToRelative(4.0f, 4.0f)
        verticalLineTo(6.5f)
        lineToRelative(-4.0f, 4.0f)
        close()
    }
}

/** Material "videocam_off" (filled) — camera currently disabled (mid-call toggle state). */
val VideocamOffIcon: ImageVector = materialIcon(name = "Filled.VideocamOff") {
    materialPath {
        moveTo(21.0f, 6.5f)
        lineToRelative(-4.0f, 4.0f)
        verticalLineTo(7.0f)
        curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
        horizontalLineTo(9.82f)
        lineTo(21.0f, 17.18f)
        verticalLineTo(6.5f)
        close()
        moveTo(3.27f, 2.0f)
        lineTo(2.0f, 3.27f)
        lineTo(4.73f, 6.0f)
        horizontalLineTo(4.0f)
        curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
        verticalLineToRelative(10.0f)
        curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
        horizontalLineToRelative(12.0f)
        curveToRelative(0.21f, 0.0f, 0.39f, -0.08f, 0.54f, -0.18f)
        lineTo(19.73f, 21.0f)
        lineTo(21.0f, 19.73f)
        lineTo(3.27f, 2.0f)
        close()
    }
}

/** Material "autorenew" (filled) — the flip-front/back-camera control on the self-preview tile. */
val FlipCameraIcon: ImageVector = materialIcon(name = "Filled.FlipCamera") {
    materialPath {
        moveTo(12.0f, 6.0f)
        verticalLineToRelative(3.0f)
        lineToRelative(4.0f, -4.0f)
        lineToRelative(-4.0f, -4.0f)
        verticalLineToRelative(3.0f)
        curveToRelative(-4.42f, 0.0f, -8.0f, 3.58f, -8.0f, 8.0f)
        curveToRelative(0.0f, 1.57f, 0.46f, 3.03f, 1.24f, 4.26f)
        lineTo(6.7f, 14.8f)
        curveToRelative(-0.45f, -0.83f, -0.7f, -1.79f, -0.7f, -2.8f)
        curveToRelative(0.0f, -3.31f, 2.69f, -6.0f, 6.0f, -6.0f)
        close()
        moveTo(18.76f, 7.74f)
        lineTo(17.3f, 9.2f)
        curveToRelative(0.44f, 0.84f, 0.7f, 1.79f, 0.7f, 2.8f)
        curveToRelative(0.0f, 3.31f, -2.69f, 6.0f, -6.0f, 6.0f)
        verticalLineToRelative(-3.0f)
        lineToRelative(-4.0f, 4.0f)
        lineToRelative(4.0f, 4.0f)
        verticalLineToRelative(-3.0f)
        curveToRelative(4.42f, 0.0f, 8.0f, -3.58f, 8.0f, -8.0f)
        curveToRelative(0.0f, -1.57f, -0.46f, -3.03f, -1.24f, -4.26f)
        close()
    }
}

/** Material "volume_up" (filled) — speakerphone toggle. */
val SpeakerIcon: ImageVector = materialIcon(name = "Filled.VolumeUp") {
    materialPath {
        moveTo(3.0f, 9.0f)
        verticalLineToRelative(6.0f)
        horizontalLineToRelative(4.0f)
        lineToRelative(5.0f, 5.0f)
        verticalLineTo(4.0f)
        lineToRelative(-5.0f, 5.0f)
        horizontalLineTo(3.0f)
        close()
        moveTo(16.5f, 12.0f)
        curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
        verticalLineToRelative(8.05f)
        curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
        close()
        moveTo(14.0f, 3.23f)
        verticalLineToRelative(2.06f)
        curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
        reflectiveCurveToRelative(-2.11f, 5.85f, -5.0f, 6.71f)
        verticalLineToRelative(2.06f)
        curveToRelative(4.01f, -0.91f, 7.0f, -4.49f, 7.0f, -8.77f)
        reflectiveCurveToRelative(-2.99f, -7.86f, -7.0f, -8.77f)
        close()
    }
}

/** Material "mic" (filled) — the voice assistant. */
val MicIcon: ImageVector = materialIcon(name = "Filled.Mic") {
    materialPath {
        moveTo(12.0f, 14.0f)
        curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
        verticalLineTo(5.0f)
        curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
        reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
        verticalLineToRelative(6.0f)
        curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
        close()
        moveTo(17.0f, 11.0f)
        curveToRelative(0.0f, 2.76f, -2.24f, 5.0f, -5.0f, 5.0f)
        reflectiveCurveToRelative(-5.0f, -2.24f, -5.0f, -5.0f)
        horizontalLineTo(5.0f)
        curveToRelative(0.0f, 3.53f, 2.61f, 6.43f, 6.0f, 6.92f)
        verticalLineTo(21.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(-3.08f)
        curveToRelative(3.39f, -0.49f, 6.0f, -3.39f, 6.0f, -6.92f)
        horizontalLineToRelative(-2.0f)
        close()
    }
}

/**
 * Material "star" (filled) — a chosen comrade (see [ComradesScreen]).
 *
 * Defined here rather than taken from `Icons.Filled`, like every other
 * feature glyph in this file, so the app never depends on which subset of
 * Material icons the core artifact happens to ship.
 */
val StarIcon: ImageVector = materialIcon(name = "Filled.Star") {
    materialPath {
        moveTo(12.0f, 17.27f)
        lineTo(18.18f, 21.0f)
        lineTo(16.54f, 13.97f)
        lineTo(22.0f, 9.24f)
        lineTo(14.81f, 8.63f)
        lineTo(12.0f, 2.0f)
        lineTo(9.19f, 8.63f)
        lineTo(2.0f, 9.24f)
        lineTo(7.46f, 13.97f)
        lineTo(5.82f, 21.0f)
        close()
    }
}

/** Material "star_outline" — not (yet) a comrade; the off state of [StarIcon]. */
val StarOutlineIcon: ImageVector = materialIcon(name = "Filled.StarOutline") {
    materialPath {
        moveTo(22.0f, 9.24f)
        lineTo(14.81f, 8.62f)
        lineTo(12.0f, 2.0f)
        lineTo(9.19f, 8.63f)
        lineTo(2.0f, 9.24f)
        lineTo(7.46f, 13.97f)
        lineTo(5.82f, 21.0f)
        lineTo(12.0f, 17.27f)
        lineTo(18.18f, 21.0f)
        lineTo(16.55f, 13.97f)
        close()
        // The inner cut-out, wound the other way so it renders as a hole.
        moveTo(12.0f, 15.4f)
        lineTo(8.24f, 17.67f)
        lineTo(9.24f, 13.39f)
        lineTo(5.92f, 10.51f)
        lineTo(10.3f, 10.13f)
        lineTo(12.0f, 6.1f)
        lineTo(13.71f, 10.14f)
        lineTo(18.09f, 10.52f)
        lineTo(14.77f, 13.4f)
        lineTo(15.77f, 17.68f)
        close()
    }
}

/** Material "content copy" (filled) — copying a key to the clipboard. */
val CopyIcon: ImageVector = materialIcon(name = "Filled.ContentCopy") {
    materialPath {
        moveTo(16.0f, 1.0f)
        horizontalLineTo(4.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(14.0f)
        horizontalLineToRelative(2.0f)
        verticalLineTo(3.0f)
        horizontalLineToRelative(12.0f)
        verticalLineTo(1.0f)
        close()
        moveTo(19.0f, 5.0f)
        horizontalLineTo(8.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(14.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(11.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        verticalLineTo(7.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        close()
        // The inner cut-out, wound the other way so it renders as a hole.
        moveTo(19.0f, 21.0f)
        horizontalLineTo(8.0f)
        verticalLineTo(7.0f)
        horizontalLineToRelative(11.0f)
        verticalLineToRelative(14.0f)
        close()
    }
}

/** Material "notifications" (filled) — the ⋮ menu's mute row. */
val NotificationsIcon: ImageVector = materialIcon(name = "Filled.Notifications") {
    materialPath {
        moveTo(12.0f, 22.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        horizontalLineToRelative(-4.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        close()
        moveTo(18.0f, 16.0f)
        verticalLineToRelative(-5.0f)
        curveToRelative(0.0f, -3.07f, -1.63f, -5.64f, -4.5f, -6.32f)
        verticalLineTo(4.0f)
        curveToRelative(0.0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
        reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
        verticalLineToRelative(0.68f)
        curveTo(7.64f, 5.36f, 6.0f, 7.92f, 6.0f, 11.0f)
        verticalLineToRelative(5.0f)
        lineToRelative(-2.0f, 2.0f)
        verticalLineToRelative(1.0f)
        horizontalLineToRelative(16.0f)
        verticalLineToRelative(-1.0f)
        lineToRelative(-2.0f, -2.0f)
        close()
    }
}

/**
 * Material "notifications off" (filled) — the unmute row. Drawn as the bell
 * with the diagonal bar Material uses for every "off" glyph, so it reads the
 * same way [VideocamOffIcon] does next to it.
 */
val NotificationsOffIcon: ImageVector = materialIcon(name = "Filled.NotificationsOff") {
    materialPath {
        moveTo(20.0f, 18.69f)
        lineTo(7.84f, 6.14f)
        curveTo(8.47f, 5.69f, 9.2f, 5.35f, 10.0f, 5.18f)
        verticalLineTo(4.0f)
        curveToRelative(0.0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f)
        reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
        verticalLineToRelative(0.68f)
        curveToRelative(2.87f, 0.68f, 4.5f, 3.25f, 4.5f, 6.32f)
        verticalLineToRelative(5.0f)
        lineToRelative(2.0f, 2.0f)
        verticalLineToRelative(0.69f)
        close()
        moveTo(12.0f, 22.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        horizontalLineToRelative(-4.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        close()
        moveTo(6.0f, 11.0f)
        curveToRelative(0.0f, -0.5f, 0.05f, -0.98f, 0.14f, -1.44f)
        lineTo(4.41f, 7.83f)
        curveTo(4.15f, 8.82f, 4.0f, 9.88f, 4.0f, 11.0f)
        verticalLineToRelative(5.0f)
        lineToRelative(-2.0f, 2.0f)
        verticalLineToRelative(1.0f)
        horizontalLineToRelative(14.14f)
        lineTo(6.0f, 12.86f)
        close()
        // The diagonal bar every Material "off" glyph carries.
        moveTo(2.81f, 2.81f)
        lineTo(1.39f, 4.22f)
        lineTo(19.78f, 22.61f)
        lineTo(21.19f, 21.19f)
        close()
    }
}

/**
 * Material "screen_share" (filled) — share your screen during a call.
 *
 * A monitor with an upward arrow: the same glyph Telegram/Meet use, so the
 * control reads without a label. Inlined here for the same reason as every
 * other icon in this file — the app never depends on which subset of Material
 * icons the core artifact happens to ship.
 */
val ScreenShareIcon: ImageVector = materialIcon(name = "Filled.ScreenShare") {
    materialPath {
        // The monitor body.
        moveTo(20.0f, 18.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        verticalLineTo(6.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        horizontalLineTo(4.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(10.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        horizontalLineTo(0.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(24.0f)
        verticalLineToRelative(-2.0f)
        horizontalLineToRelative(-4.0f)
        close()
        // The arrow rising out of it.
        moveTo(13.0f, 12.4f)
        verticalLineToRelative(2.6f)
        horizontalLineToRelative(-2.0f)
        verticalLineToRelative(-2.6f)
        horizontalLineTo(8.0f)
        lineToRelative(4.0f, -4.0f)
        lineToRelative(4.0f, 4.0f)
        horizontalLineToRelative(-3.0f)
        close()
    }
}

/**
 * Material "hourglass_empty" (filled) — the Focus tab (attention practice).
 *
 * An hourglass rather than a stopwatch on purpose: a stopwatch measures
 * performance, an hourglass just marks that time is passing, which is all a
 * focus session asks anyone to do.
 */
val TimerIcon: ImageVector = materialIcon(name = "Filled.HourglassEmpty") {
    materialPath {
        moveTo(6.0f, 2.0f)
        verticalLineToRelative(6.0f)
        horizontalLineToRelative(0.01f)
        lineTo(6.0f, 8.01f)
        lineTo(10.0f, 12.0f)
        lineToRelative(-4.0f, 4.0f)
        lineToRelative(0.01f, 0.01f)
        horizontalLineTo(6.0f)
        verticalLineTo(22.0f)
        horizontalLineToRelative(12.0f)
        verticalLineToRelative(-5.99f)
        horizontalLineToRelative(-0.01f)
        lineTo(18.0f, 16.0f)
        lineToRelative(-4.0f, -4.0f)
        lineToRelative(4.0f, -3.99f)
        lineToRelative(-0.01f, -0.01f)
        horizontalLineTo(18.0f)
        verticalLineTo(2.0f)
        horizontalLineTo(6.0f)
        close()
        moveTo(16.0f, 16.5f)
        verticalLineTo(20.0f)
        horizontalLineTo(8.0f)
        verticalLineToRelative(-3.5f)
        lineToRelative(4.0f, -4.0f)
        lineToRelative(4.0f, 4.0f)
        close()
        moveTo(12.0f, 11.5f)
        lineToRelative(-4.0f, -4.0f)
        verticalLineTo(4.0f)
        horizontalLineToRelative(8.0f)
        verticalLineToRelative(3.5f)
        lineToRelative(-4.0f, 4.0f)
        close()
    }
}
