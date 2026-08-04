package mullu.comrade.ui

/**
 * The rules a profile page has to agree on across every frontend, kept pure so
 * they can be tested once and mirrored exactly.
 *
 * The Dart port is `app/lib/src/util/profile_view.dart` and the desktop one is
 * `desktop/ui/profile_view.mjs`; all three are pinned by mirrored tests, so a
 * change here is a change in three places.
 *
 * What is *not* here: any wording. These functions return row and action kinds,
 * never labels, for the same reason [PresenceLabel] does — the rule is shared,
 * the strings live in `strings.xml` where they can be translated.
 */

/** The largest profile picture that will be accepted — `MAX_AVATAR_BYTES` in `comrade_core::avatar`. */
const val MAX_AVATAR_BYTES = 256L * 1024

/** The longest bio this UI will draw, matching `MAX_ABOUT_LEN` in the core. */
const val MAX_ABOUT_CHARS = 512

/** The longest published handle this UI will draw on one row. */
const val MAX_HANDLE_CHARS = 64

/**
 * The image types a profile picture may be, mirroring the allowlist the core
 * enforces on fetch.
 *
 * SVG is absent deliberately and permanently — it is a document that can carry
 * script and fetch remote resources, not a picture.
 */
val AVATAR_MIME_ALLOWLIST = listOf("image/jpeg", "image/png", "image/webp")

/** Which kind of row this is; the caller resolves the label. */
enum class ProfileRowKind { Bio, Handle, Nip05, Lud16, Key }

/** One row of the info block. */
data class ProfileRow(
    val kind: ProfileRowKind,
    val value: String,
    val copyable: Boolean,
)

/** What the action row offers; the caller resolves labels and icons. */
enum class ProfileAction {
    Message,
    Call,
    Mute,
    Unmute,
    AddContact,
    AddComrade,
    RemoveComrade,
    Block,
    Edit,
    CopyKey,
}

/** Which shared-media tab an item belongs to. */
enum class MediaTab { Media, Voice, Files }

/**
 * The fields a profile page reads, whether it came from `ProfileDto` (your own)
 * or `PeerProfileDto` (someone else's).
 *
 * A single shape so [infoRows] has one signature rather than two near-identical
 * ones — the two DTOs differ in which fields they carry, not in what a row means.
 */
data class ProfileFields(
    val npub: String,
    val name: String? = null,
    val about: String? = null,
    val nip05: String? = null,
    val lud16: String? = null,
)

/**
 * The rows of the info block, in order.
 *
 * Empty values are dropped — a blank "Bio" row states that we know the person
 * has no bio, which is not the same as not having fetched one. Every peer-chosen
 * value goes through [sanitizeDisplayText] on the way, because these are drawn at
 * heading size next to an avatar: the same threat a transfer card's filename has,
 * and the same answer.
 *
 * The key row is the exception: it is **never** dropped, and it is last. The
 * reasoning is [peerTitle]'s — a self-declared handle shown without the key
 * reachable "is the exact shape of an impersonation" — and the owner call of
 * 2026-07-30 moved the key out of the conversation header on the grounds that it
 * was reachable on demand one tap away. This page *is* that place, so the key is
 * not optional here: every other row is a claim the person made about
 * themselves, and this is the one row that is a fact.
 */
fun infoRows(fields: ProfileFields, isSelf: Boolean = false): List<ProfileRow> {
    val rows = mutableListOf<ProfileRow>()
    fun push(kind: ProfileRowKind, raw: String?, max: Int) {
        val clean = sanitizeDisplayText(raw, max)
        if (clean.isNotEmpty()) rows.add(ProfileRow(kind, clean, kind != ProfileRowKind.Bio))
    }
    push(ProfileRowKind.Bio, fields.about, MAX_ABOUT_CHARS)
    push(ProfileRowKind.Handle, handleOf(fields.name), MAX_HANDLE_CHARS)
    push(ProfileRowKind.Nip05, fields.nip05, MAX_HANDLE_CHARS)
    push(ProfileRowKind.Lud16, fields.lud16, MAX_HANDLE_CHARS)
    // Never conditional, and always last: the long monospace string is the least
    // scannable row and the one nobody reads unless they came for it. Not
    // sanitized and not truncated — it is bech32 from our own parser, and a
    // shortened key is not a key.
    rows.add(ProfileRow(ProfileRowKind.Key, fields.npub.trim(), copyable = true))
    // Your own empty bio still gets a row, because on your own page an empty row
    // is the affordance to fill it in. A peer's does not: there is nothing to act
    // on, and a blank row would read as a bio that says nothing.
    if (isSelf && rows.none { it.kind == ProfileRowKind.Bio }) {
        rows.add(0, ProfileRow(ProfileRowKind.Bio, "", copyable = false))
    }
    return rows
}

/**
 * The published handle with at most one leading `@`.
 *
 * [peerTitle] already owns the display-precedence rule (alias → handle → key);
 * this is only the prefix normalisation, so `name`, `@name` and `@@name` render
 * identically instead of three ways.
 */
fun handleOf(name: String?): String {
    val bare = (name ?: "").trim().trimStart('@')
    return if (bare.isEmpty()) "" else "@$bare"
}

/**
 * Which of the shared-media tabs an item belongs to.
 *
 * Delegates the MIME reading to [attachmentPreviewKind] rather than repeating it,
 * so a bubble, a preview sheet and a profile tab can never disagree about what a
 * file is. Photos and videos share one tab because that is the grid people scan
 * visually; a voice note has nothing to show and belongs in a list.
 */
fun mediaTabFor(mimeType: String): MediaTab =
    when (attachmentPreviewKind(mimeType)) {
        AttachmentPreviewKind.Image, AttachmentPreviewKind.Video -> MediaTab.Media
        AttachmentPreviewKind.Audio -> MediaTab.Voice
        AttachmentPreviewKind.File -> MediaTab.Files
    }

/**
 * The avatar's side length at a given header collapse fraction, where 0 is fully
 * expanded and 1 fully collapsed.
 *
 * Linear, and clamped at both ends so a platform that reports a fraction
 * slightly outside 0..1 during an overscroll (all three do, at some point)
 * cannot produce an avatar larger than the header or an inverted one.
 *
 * The three renderers are necessarily different — a Compose `LargeTopAppBar`
 * state, a Flutter `FlexibleSpaceBar`, a scroll listener over a CSS custom
 * property — so what is shared is the *curve*, which is the part a user would
 * notice drifting between platforms.
 */
fun collapsedAvatarSize(fraction: Float, expandedPx: Float, collapsedPx: Float): Float {
    val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    return expandedPx + (collapsedPx - expandedPx) * f
}

/**
 * Which actions the row under the header offers, in order.
 *
 * Four rules carry weight:
 *
 * 1. **A blocked peer offers nothing at all** — not even Unblock. There is no
 *    unblock command in the core and no getter for the state to drive one, so a
 *    button here would be a fake switch, which is the one thing the settings
 *    screen's own rule forbids. The page says you blocked them and offers no lie
 *    about undoing it. When an unblock command exists, this is the function that
 *    changes, and its test is what will say so.
 * 2. **A stranger gets no Call button.** Placing a call makes this device gather
 *    ICE for whoever is on the other end — the same bar the accepted-conversation
 *    gate already holds an incoming call signal to, for the same reason. Offered
 *    before acceptance, its only outcomes are a leak or an error.
 * 3. **Mute is only meaningful for someone you hear from.** A stranger's messages
 *    are already gated behind a request.
 * 4. **Your own profile offers no Message and no Block.** Both are nonsense
 *    against yourself, and a Block that half-worked would be worse than absent.
 */
fun actionRow(
    isSelf: Boolean = false,
    isContact: Boolean = false,
    isComrade: Boolean = false,
    isMuted: Boolean = false,
    isBlocked: Boolean = false,
): List<ProfileAction> {
    if (isSelf) return listOf(ProfileAction.Edit, ProfileAction.CopyKey)
    if (isBlocked) return emptyList()
    val actions = mutableListOf(ProfileAction.Message)
    if (isContact) {
        actions.add(ProfileAction.Call)
        actions.add(if (isMuted) ProfileAction.Unmute else ProfileAction.Mute)
        actions.add(if (isComrade) ProfileAction.RemoveComrade else ProfileAction.AddComrade)
    } else {
        actions.add(ProfileAction.AddContact)
    }
    actions.add(ProfileAction.Block)
    return actions
}

/**
 * Which tab a profile should open on: the first non-empty in the canonical order
 * Media → Files → Voice, or Media when there is nothing at all.
 *
 * Opening on an empty Media tab makes a profile with plenty of files read as
 * "nothing shared", which is the failure this exists to prevent.
 */
fun initialMediaTab(mimeTypes: List<String>): MediaTab {
    val tabs = mimeTypes.map { mediaTabFor(it) }
    return when {
        tabs.contains(MediaTab.Media) -> MediaTab.Media
        tabs.contains(MediaTab.Files) -> MediaTab.Files
        tabs.contains(MediaTab.Voice) -> MediaTab.Voice
        else -> MediaTab.Media
    }
}

/** Why this image cannot be used as a profile picture, or null when it can. */
fun avatarRejection(mimeType: String?, bytes: Long): AvatarRefusal? {
    val mime = (mimeType ?: "").trim().lowercase()
    if (bytes <= 0L) return AvatarRefusal.Empty
    if (!AVATAR_MIME_ALLOWLIST.contains(mime)) return AvatarRefusal.WrongType
    if (bytes > MAX_AVATAR_BYTES) return AvatarRefusal.TooLarge
    return null
}

/** Why an avatar was refused; the caller resolves the wording. */
enum class AvatarRefusal { Empty, WrongType, TooLarge }

/**
 * Whether a peer's `picture` URL may be fetched at all.
 *
 * Two gates, and the caller must pass both: the user has not turned remote
 * pictures off, and the profile belongs to someone already accepted — so opening
 * a stranger's profile cannot make this device call out to a host they picked.
 *
 * Scheme, host and size are *not* checked here. They are enforced in the core,
 * where every caller gets them whether or not it remembered to ask. This is the
 * "should we ask at all", not the "is it safe".
 */
fun mayFetchAvatar(
    url: String?,
    remoteAvatarsEnabled: Boolean = true,
    isContact: Boolean = false,
    isSelf: Boolean = false,
    isBlocked: Boolean = false,
): Boolean {
    if ((url ?: "").isBlank()) return false
    if (!remoteAvatarsEnabled) return false
    if (isBlocked) return false
    return isSelf || isContact
}
