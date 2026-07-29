package mullu.comrade.ui

/**
 * The conversation overflow (⋮) menu, as data.
 *
 * Kept free of Compose/Android imports so plain JVM unit tests
 * (`ChatMenuTest`) can pin the order, the comrade-toggle direction and which
 * entries are destructive — the parts that are easy to get subtly wrong and
 * invisible until someone taps the wrong row. Labels are deliberately *not*
 * here: like [presenceLabelOf], the caller resolves the string, so Android
 * string resources stay the single source of wording.
 *
 * The Dart port is `app/lib/src/util/chat_menu.dart`; the two must offer the
 * same actions in the same order (see `docs/FRONTEND_STRATEGY.md` on parity
 * debt — divergence here is exactly the pain that document measures).
 */
enum class ChatMenuAction {
    /** Set/change the local petname for this key. */
    SetAlias,

    /** Mark as a comrade — mutual presence starts flowing. */
    AddComrade,

    /** Stop being comrades; their presence goes dark again. */
    RemoveComrade,

    /** Copy the peer's `npub` to the clipboard. */
    CopyKey,

    /** Explain what protects this thread, and show the key in full. */
    EncryptionInfo,

    /** Drop this person's future messages. */
    Block,
    ;

    /**
     * Whether the row should be styled as a warning. Blocking is the only
     * entry that silently changes what reaches the user, so it is the only
     * one that earns the error colour — spending that emphasis elsewhere
     * would teach people to ignore it.
     */
    val destructive: Boolean get() = this == Block
}

/**
 * The menu for an open conversation, top to bottom. Exactly one of
 * [ChatMenuAction.AddComrade]/[ChatMenuAction.RemoveComrade] appears, chosen
 * by [isComrade], so the row always names what tapping it will *do* rather
 * than describing the current state.
 *
 * Destructive entries sort last: a menu that opens under the thumb should not
 * put "Block" where "Set alias" was a moment ago.
 */
fun conversationMenu(isComrade: Boolean): List<ChatMenuAction> = listOf(
    ChatMenuAction.SetAlias,
    if (isComrade) ChatMenuAction.RemoveComrade else ChatMenuAction.AddComrade,
    ChatMenuAction.CopyKey,
    ChatMenuAction.EncryptionInfo,
    ChatMenuAction.Block,
)
