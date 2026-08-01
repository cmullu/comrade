package mullu.comrade

/**
 * When a notification is warranted. Pure decisions, no Android types, so
 * `NotificationPolicyTest` can pin them — the rules used to be `if` conditions
 * inlined in [ChatEventRouter.route], where "does mute apply to attachments
 * too?" could only be answered by reading two call sites and hoping they
 * matched.
 */
object NotificationPolicy {

    /**
     * A DM or attachment from [peer].
     *
     * Two things suppress it:
     * - the conversation is **on screen** — the message is already visible, and
     *   buzzing about it is how an app tells you what you are looking at;
     * - the user **muted** that conversation.
     *
     * Mute wins even when the thread is not on screen, and applies to
     * attachments exactly as to text: "mute this chat" means the chat, not the
     * text half of it.
     */
    fun shouldNotifyMessage(
        peer: String,
        openConversationPeer: String?,
        muted: Boolean,
        quietHours: Boolean = false,
    ): Boolean {
        if (muted) return false
        // Quiet hours (mullu.comrade.attention.QuietHours) silence everything
        // except a ringing call — see the note at the bottom of this file.
        if (quietHours) return false
        return peer != openConversationPeer
    }

    /**
     * A comrade coming online.
     *
     * Muting a conversation silences their presence notice too: someone who
     * asked not to be buzzed about a person meant the person, not one class of
     * event about them. The dot in the chat list stays — mute is about
     * interruption, not about hiding information from the app itself.
     */
    fun shouldNotifyPresence(
        peer: String,
        openConversationPeer: String?,
        muted: Boolean,
        becameOnline: Boolean,
        quietHours: Boolean = false,
    ): Boolean {
        if (!becameOnline) return false
        if (muted) return false
        if (quietHours) return false
        return peer != openConversationPeer
    }

    /**
     * A message request from a stranger. Quiet hours apply; nothing else does
     * (a request has no conversation to be "on screen", and mute is per
     * accepted conversation).
     */
    fun shouldNotifyRequest(quietHours: Boolean = false): Boolean = !quietHours

    /**
     * An "a newer Comrade shipped" notice. Quiet hours apply — a release can
     * always wait until morning.
     */
    fun shouldNotifyUpdate(quietHours: Boolean = false): Boolean = !quietHours

    // Incoming calls deliberately have no rule here: neither mute nor quiet
    // hours silences a ringing call. A muted conversation is a preference about
    // chatter, and quiet hours are about sleep — but dropping a call would lose
    // the user something they cannot get back, and someone ringing at 3am may
    // be exactly the person this app exists for. Telegram draws the same line
    // (its mute has a separate "mute calls"), and `docs/ATTENTION.md` states it
    // as a rule: attention features never come between a user and a call.

    /** Whether the message group's summary is now orphaned (no children left). */
    fun summaryStale(messageChildren: Int): Boolean = messageChildren <= 0
}
