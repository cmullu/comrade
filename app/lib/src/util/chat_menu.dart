/// The conversation overflow (⋮) menu, as data — ported from
/// `android/app/src/main/java/mullu/comrade/ui/ChatMenu.kt`.
///
/// Kept free of Flutter imports so plain Dart unit tests can pin the order, the
/// comrade-toggle direction and which entries are destructive, exactly as
/// `ChatMenuTest.kt` does on Android. Labels are deliberately not here: the
/// caller resolves the string, so wording stays with the widget layer.
///
/// The two frontends must offer the same actions in the same order — see
/// `docs/FRONTEND_STRATEGY.md` on parity debt.
library;

/// An entry in the conversation ⋮ menu.
enum ChatMenuAction {
  /// Set/change the local petname for this key.
  setAlias,

  /// Mark as a comrade — mutual presence starts flowing.
  addComrade,

  /// Stop being comrades; their presence goes dark again.
  removeComrade,

  /// Stop notifying about this conversation (calls still ring).
  mute,

  /// Start notifying about it again.
  unmute,

  /// Copy the peer's `npub` to the clipboard.
  copyKey,

  /// Explain what protects this thread, and show the key in full.
  encryptionInfo,

  /// Drop this person's future messages.
  block;

  /// Whether the row should be styled as a warning.
  ///
  /// Blocking is the only entry that silently changes what reaches the user, so
  /// it is the only one that earns the error colour — spending that emphasis
  /// elsewhere would teach people to ignore it.
  ///
  /// Mute deliberately does not qualify: it is reversible from the same menu,
  /// it is announced in Settings, and it never silences a call.
  bool get destructive => this == ChatMenuAction.block;
}

/// The menu for an open conversation, top to bottom.
///
/// Exactly one of each either/or pair appears —
/// [ChatMenuAction.addComrade]/[ChatMenuAction.removeComrade] chosen by
/// [isComrade], [ChatMenuAction.mute]/[ChatMenuAction.unmute] by [isMuted] — so
/// a row always names what tapping it will *do* rather than describing the
/// current state.
///
/// Mute sits next to the comrade toggle because both answer "how much do I want
/// to hear from this person": the two settings people reach for together.
///
/// Destructive entries sort last: a menu that opens under the thumb should not
/// put "Block" where "Set alias" was a moment ago.
List<ChatMenuAction> conversationMenu({
  required bool isComrade,
  bool isMuted = false,
}) =>
    <ChatMenuAction>[
      ChatMenuAction.setAlias,
      if (isComrade)
        ChatMenuAction.removeComrade
      else
        ChatMenuAction.addComrade,
      if (isMuted) ChatMenuAction.unmute else ChatMenuAction.mute,
      ChatMenuAction.copyKey,
      ChatMenuAction.encryptionInfo,
      ChatMenuAction.block,
    ];
