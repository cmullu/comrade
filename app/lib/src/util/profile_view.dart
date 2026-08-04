/// The rules a profile page has to agree on across every frontend, kept pure so
/// they can be tested once and mirrored exactly.
///
/// Ports: `android/.../ui/ProfileView.kt`, `desktop/ui/profile_view.mjs`. Same
/// cases, same answers — a change here is a change in three places.
///
/// What is *not* here: any wording. These functions return row and action kinds,
/// never labels, for the same reason `PresenceLabel` does — the rule is shared,
/// the strings live where they can be translated.
library;

import 'attachment_caption.dart';
import 'display_text.dart';

/// The largest profile picture that will be accepted — `MAX_AVATAR_BYTES` in
/// `comrade_core::avatar`.
const int maxAvatarBytes = 256 * 1024;

/// The longest bio this UI will draw, matching `MAX_ABOUT_LEN` in the core.
const int maxAboutChars = 512;

/// The longest published handle this UI will draw on one row.
const int maxHandleChars = 64;

/// The image types a profile picture may be, mirroring the allowlist the core
/// enforces on fetch.
///
/// SVG is absent deliberately and permanently — it is a document that can carry
/// script and fetch remote resources, not a picture.
const List<String> avatarMimeAllowlist = <String>[
  'image/jpeg',
  'image/png',
  'image/webp',
];

/// Which kind of row this is; the caller resolves the label.
enum ProfileRowKind { bio, handle, nip05, lud16, key }

/// One row of the info block.
class ProfileRow {
  const ProfileRow(this.kind, this.value, {required this.copyable});

  final ProfileRowKind kind;
  final String value;
  final bool copyable;

  @override
  bool operator ==(Object other) =>
      other is ProfileRow &&
      other.kind == kind &&
      other.value == value &&
      other.copyable == copyable;

  @override
  int get hashCode => Object.hash(kind, value, copyable);

  @override
  String toString() => 'ProfileRow($kind, "$value", copyable: $copyable)';
}

/// What the action row offers; the caller resolves labels and icons.
enum ProfileAction {
  message,
  call,
  mute,
  unmute,
  addContact,
  addComrade,
  removeComrade,
  block,
  edit,
  copyKey,
}

/// Which shared-media tab an item belongs to.
enum MediaTab { media, voice, files }

/// Why an avatar was refused; the caller resolves the wording.
enum AvatarRefusal { empty, wrongType, tooLarge }

/// The fields a profile page reads, whether it came from `Profile` (your own) or
/// `PeerProfile` (someone else's).
class ProfileFields {
  const ProfileFields({
    required this.npub,
    this.name,
    this.about,
    this.nip05,
    this.lud16,
  });

  final String npub;
  final String? name;
  final String? about;
  final String? nip05;
  final String? lud16;
}

/// The rows of the info block, in order.
///
/// Empty values are dropped — a blank "Bio" row states that we know the person
/// has no bio, which is not the same as not having fetched one. Every peer-chosen
/// value goes through [sanitizeDisplayText] on the way, because these are drawn
/// at heading size next to an avatar.
///
/// The key row is the exception: it is **never** dropped, and it is last. The
/// reasoning is `peerTitle`'s — a self-declared handle shown without the key
/// reachable is the exact shape of an impersonation — and the owner call of
/// 2026-07-30 moved the key out of the conversation header on the grounds that it
/// was reachable on demand one tap away. This page *is* that place, so the key is
/// not optional here: every other row is a claim the person made about
/// themselves, and this is the one row that is a fact.
List<ProfileRow> infoRows(ProfileFields fields, {bool isSelf = false}) {
  final rows = <ProfileRow>[];
  void push(ProfileRowKind kind, String? raw, int max) {
    final clean = sanitizeDisplayText(raw, max);
    if (clean.isNotEmpty) {
      rows.add(ProfileRow(kind, clean, copyable: kind != ProfileRowKind.bio));
    }
  }

  push(ProfileRowKind.bio, fields.about, maxAboutChars);
  push(ProfileRowKind.handle, handleOf(fields.name), maxHandleChars);
  push(ProfileRowKind.nip05, fields.nip05, maxHandleChars);
  push(ProfileRowKind.lud16, fields.lud16, maxHandleChars);
  // Never conditional, and always last: the long monospace string is the least
  // scannable row and the one nobody reads unless they came for it. Not
  // sanitized and not truncated — it is bech32 from our own parser, and a
  // shortened key is not a key.
  rows.add(ProfileRow(ProfileRowKind.key, fields.npub.trim(), copyable: true));
  // Your own empty bio still gets a row, because on your own page an empty row
  // is the affordance to fill it in. A peer's does not: there is nothing to act
  // on, and a blank row would read as a bio that says nothing.
  if (isSelf && !rows.any((r) => r.kind == ProfileRowKind.bio)) {
    rows.insert(0, const ProfileRow(ProfileRowKind.bio, '', copyable: false));
  }
  return rows;
}

/// The published handle with at most one leading `@`.
///
/// `peerTitle` already owns the display-precedence rule (alias → handle → key);
/// this is only the prefix normalisation, so `name`, `@name` and `@@name` render
/// identically instead of three ways.
String handleOf(String? name) {
  final bare = (name ?? '').trim().replaceFirst(RegExp(r'^@+'), '');
  return bare.isEmpty ? '' : '@$bare';
}

/// Which of the shared-media tabs an item belongs to.
///
/// Delegates the MIME reading to [attachmentPreviewKind] rather than repeating
/// it, so a bubble, a preview sheet and a profile tab can never disagree about
/// what a file is. Photos and videos share one tab because that is the grid
/// people scan visually; a voice note has nothing to show and belongs in a list.
MediaTab mediaTabFor(String mimeType) {
  switch (attachmentPreviewKind(mimeType)) {
    case AttachmentPreviewKind.image:
    case AttachmentPreviewKind.video:
      return MediaTab.media;
    case AttachmentPreviewKind.audio:
      return MediaTab.voice;
    case AttachmentPreviewKind.file:
      return MediaTab.files;
  }
}

/// The avatar's side length at a given header collapse fraction, where 0 is fully
/// expanded and 1 fully collapsed.
///
/// Linear, and clamped at both ends so a platform that reports a fraction
/// slightly outside 0..1 during an overscroll (all three do, at some point)
/// cannot produce an avatar larger than the header or an inverted one.
///
/// The three renderers are necessarily different — a Compose `LargeTopAppBar`
/// state, a Flutter `FlexibleSpaceBar`, a scroll listener over a CSS custom
/// property — so what is shared is the *curve*, which is the part a user would
/// notice drifting between platforms.
double collapsedAvatarSize(
    double fraction, double expandedPx, double collapsedPx) {
  // `num.clamp` is declared to return `num`, so make the double explicit rather
  // than leaning on the arithmetic to widen it back.
  final double f = fraction.isNaN ? 0.0 : fraction.clamp(0.0, 1.0).toDouble();
  return expandedPx + (collapsedPx - expandedPx) * f;
}

/// Which actions the row under the header offers, in order.
///
/// Four rules carry weight:
///
/// 1. **A blocked peer offers nothing at all** — not even Unblock. There is no
///    unblock command in the core and no getter for the state to drive one, so a
///    button here would be a fake switch, which is the one thing the settings
///    screen's own rule forbids. When an unblock command exists, this is the
///    function that changes, and its test is what will say so.
/// 2. **A stranger gets no Call button.** Placing a call makes this device gather
///    ICE for whoever is on the other end — the same bar the
///    accepted-conversation gate already holds an incoming call signal to.
/// 3. **Mute is only meaningful for someone you hear from.** A stranger's
///    messages are already gated behind a request.
/// 4. **Your own profile offers no Message and no Block.** Both are nonsense
///    against yourself, and a Block that half-worked would be worse than absent.
List<ProfileAction> actionRow({
  bool isSelf = false,
  bool isContact = false,
  bool isComrade = false,
  bool isMuted = false,
  bool isBlocked = false,
}) {
  if (isSelf) return const [ProfileAction.edit, ProfileAction.copyKey];
  if (isBlocked) return const [];
  final actions = <ProfileAction>[ProfileAction.message];
  if (isContact) {
    actions.add(ProfileAction.call);
    actions.add(isMuted ? ProfileAction.unmute : ProfileAction.mute);
    actions.add(
        isComrade ? ProfileAction.removeComrade : ProfileAction.addComrade);
  } else {
    actions.add(ProfileAction.addContact);
  }
  actions.add(ProfileAction.block);
  return actions;
}

/// Which tab a profile should open on: the first non-empty in the canonical order
/// Media → Files → Voice, or Media when there is nothing at all.
///
/// Opening on an empty Media tab makes a profile with plenty of files read as
/// "nothing shared", which is the failure this exists to prevent.
MediaTab initialMediaTab(List<String> mimeTypes) {
  final tabs = mimeTypes.map(mediaTabFor).toList();
  if (tabs.contains(MediaTab.media)) return MediaTab.media;
  if (tabs.contains(MediaTab.files)) return MediaTab.files;
  if (tabs.contains(MediaTab.voice)) return MediaTab.voice;
  return MediaTab.media;
}

/// Why this image cannot be used as a profile picture, or null when it can.
AvatarRefusal? avatarRejection(String? mimeType, int bytes) {
  final mime = (mimeType ?? '').trim().toLowerCase();
  if (bytes <= 0) return AvatarRefusal.empty;
  if (!avatarMimeAllowlist.contains(mime)) return AvatarRefusal.wrongType;
  if (bytes > maxAvatarBytes) return AvatarRefusal.tooLarge;
  return null;
}

/// Whether a peer's `picture` URL may be fetched at all.
///
/// Two gates, and the caller must pass both: the user has not turned remote
/// pictures off, and the profile belongs to someone already accepted — so opening
/// a stranger's profile cannot make this device call out to a host they picked.
///
/// Scheme, host and size are *not* checked here. They are enforced in the core,
/// where every caller gets them whether or not it remembered to ask. This is the
/// "should we ask at all", not the "is it safe".
bool mayFetchAvatar(
  String? url, {
  bool remoteAvatarsEnabled = true,
  bool isContact = false,
  bool isSelf = false,
  bool isBlocked = false,
}) {
  if ((url ?? '').trim().isEmpty) return false;
  if (!remoteAvatarsEnabled) return false;
  if (isBlocked) return false;
  return isSelf || isContact;
}
