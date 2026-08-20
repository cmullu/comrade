/// The one shell that is both frontends.
///
/// Below [Breakpoints.expanded] it is Android's: a top app bar with a
/// hamburger, a bottom navigation bar with four destinations, a drawer holding
/// the profile header and the secondary destinations, and a conversation that
/// takes over the whole screen when opened.
///
/// At or above it, it is desktop's: a persistent left sidebar with the brand,
/// the destinations, a "Modes" group and a status/identity footer — and the
/// chat tab becomes the two-pane list+thread layout `styles.css`'s
/// `.view-vault` grid describes. There is no second widget tree; the same
/// state drives both, and dragging a desktop window across 840 px moves
/// between them live.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/models.dart';
import '../state/call_providers.dart';
import '../state/chat_providers.dart';
import '../state/providers.dart';
import '../state/settings_providers.dart';
import '../platform/platform.dart' show SystemChannel;
import '../theme/breakpoints.dart';
import '../theme/comrade_theme.dart';
import '../util/chat_menu.dart';
import '../util/display_name.dart';
import '../widgets/app_chrome.dart';
import '../widgets/glass_surface.dart';
import '../widgets/peer_avatar.dart';
import 'call_screen.dart';
import 'chats/call_history_screen.dart';
import 'chats/chat_menu_dialogs.dart';
import 'chats/chats_list_screen.dart';
import 'chats/conversation_screen.dart';
import 'chats/edit_alias_dialog.dart';
import 'chats/new_chat_screen.dart';
import 'chats/requests_screen.dart';
import 'couple_screen.dart';
import 'feed_screen.dart';
import 'journal_screen.dart';
import 'settings_screen.dart';
import 'tara_screen.dart';

/// Primary destinations, in on-screen order.
///
/// Tara sits **last** deliberately: messaging/journal/feed are the daily
/// surfaces, and the companion is the one you reach for on purpose.
enum MainTab {
  chats('Chats', Icons.chat_bubble_outline, Icons.chat_bubble),
  journal('Journal', Icons.book_outlined, Icons.book),
  feed('Feed', Icons.article_outlined, Icons.article),
  tara('Tara', Icons.favorite_outline, Icons.favorite);

  const MainTab(this.label, this.icon, this.selectedIcon);

  final String label;
  final IconData icon;
  final IconData selectedIcon;
}

/// Destinations that are not bottom-nav tabs. On Android these lived in the
/// navigation drawer; on desktop, in the sidebar's "Modes" group.
enum SecondaryDestination {
  callHistory('Call history', Icons.call),
  partnerPortal('Partner Portal', Icons.favorite_border),
  settings('Settings', Icons.settings);

  const SecondaryDestination(this.label, this.icon);

  final String label;
  final IconData icon;
}

/// Sub-navigation inside the Chats tab.
enum ChatNav { list, newChat, requests }

class HomeShell extends ConsumerStatefulWidget {
  const HomeShell({super.key});

  @override
  ConsumerState<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends ConsumerState<HomeShell> {
  MainTab _tab = MainTab.chats;
  ChatNav _chatNav = ChatNav.list;
  SecondaryDestination? _secondary;
  final GlobalKey<ScaffoldState> _scaffoldKey = GlobalKey<ScaffoldState>();
  final List<StreamSubscription<String>> _navRequests =
      <StreamSubscription<String>>[];

  /// The app bar's own background is transparent, painted instead by this —
  /// glass tier chrome, per §2 of `docs/DESIGN_SYSTEM.md`.
  static const Widget _glassAppBarBackground =
      GlassSurface(tier: GlassTier.chrome, child: SizedBox.expand());

  /// Notification taps land here (WP11 in `docs/COMMS_ARCHITECTURE.md`).
  ///
  /// Two directions, because a notification names either a destination (an
  /// update notice → Settings, a model-ready notice → Tara) or a conversation (a
  /// message). A tap that arrived before this shell existed — a cold start from
  /// a notification is exactly that — was stashed natively, so the pending pair
  /// is collected once here as well as streamed.
  @override
  void initState() {
    super.initState();
    final SystemChannel system = ref.read(systemChannelProvider);
    _navRequests
      ..add(system.openTabRequests.listen(_openRequestedDestination))
      ..add(system.openConversationRequests.listen(_openRequestedConversation));
    WidgetsBinding.instance
        .addPostFrameCallback((_) => _collectPending(system));
  }

  Future<void> _collectPending(SystemChannel system) async {
    try {
      final String? tab = await system.consumePendingTab();
      if (tab != null) _openRequestedDestination(tab);
      final String? peer = await system.consumePendingPeer();
      if (peer != null) _openRequestedConversation(peer);
    } on MissingPluginException {
      // No native handler (desktop, tests). Nothing to collect, and a missing
      // deep link must never stop the shell from rendering.
    }
  }

  /// A tab or non-tab destination named by a notification. Unknown keys are
  /// ignored rather than guessed at.
  void _openRequestedDestination(String key) {
    final String wanted = key.toLowerCase();
    final MainTab? tab = MainTab.values
        .where((MainTab t) => t.name.toLowerCase() == wanted)
        .firstOrNull;
    final SecondaryDestination? secondary = SecondaryDestination.values
        .where((SecondaryDestination d) => d.name.toLowerCase() == wanted)
        .firstOrNull;
    if (tab == null && secondary == null && wanted != 'requests') return;
    if (!mounted) return;
    setState(() {
      if (wanted == 'requests') {
        _tab = MainTab.chats;
        _chatNav = ChatNav.requests;
        _secondary = null;
      } else if (secondary != null) {
        _secondary = secondary;
      } else {
        _tab = tab!;
        _chatNav = ChatNav.list;
        _secondary = null;
      }
    });
  }

  /// A conversation named by a tapped message notification. The intent carries
  /// only the key, so the title is filled in from whatever the chat list
  /// already knows — the same lookup the in-call chat button uses.
  void _openRequestedConversation(String peer) {
    if (!mounted) return;
    final ConversationInfo? known = ref
        .read(conversationsProvider)
        .value
        ?.where((ConversationInfo c) => c.peer == peer)
        .firstOrNull;
    _openConversation(
      ChatTarget(peer: peer, alias: known?.alias, username: known?.peerName),
    );
  }

  @override
  void dispose() {
    for (final StreamSubscription<String> sub in _navRequests) {
      sub.cancel();
    }
    super.dispose();
  }

  ChatTarget? get _openChat => ref.read(openConversationProvider);

  void _openConversation(ChatTarget target) {
    ref.read(openConversationProvider.notifier).state = target;
    setState(() {
      _tab = MainTab.chats;
      _chatNav = ChatNav.list;
      _secondary = null;
    });
  }

  void _closeConversation() {
    ref.read(openConversationProvider.notifier).state = null;
    setState(() {});
  }

  /// Back priority, innermost first: a pushed secondary screen closes, then a
  /// Chats sub-screen returns to the list, then an open conversation closes.
  /// Mirrors the Compose `BackHandler` chain.
  bool _handleBack() {
    if (_secondary != null) {
      setState(() => _secondary = null);
      return true;
    }
    if (_tab == MainTab.chats && _chatNav != ChatNav.list) {
      setState(() => _chatNav = ChatNav.list);
      return true;
    }
    if (_tab == MainTab.chats && _openChat != null) {
      _closeConversation();
      return true;
    }
    return false;
  }

  Future<void> _startCall({required bool video}) async {
    final ChatTarget? chat = _openChat;
    if (chat == null) return;
    await ref.read(callProvider.notifier).startOutgoing(
          peer: chat.peer,
          peerLabel: peerTitle(chat.peer, chat.alias, chat.username),
          video: video,
        );
  }

  /// The in-call chat button: open the conversation with the person you are
  /// talking to.
  ///
  /// Reuses whatever the chat list already knows about them (alias, @handle) so
  /// the header is titled the same way it would be if you had navigated there
  /// yourself — the call only carries one resolved label, not the parts.
  void _openChatDuringCall(String peer, String label) {
    final ConversationInfo? known = ref
        .read(conversationsProvider)
        .value
        ?.where((ConversationInfo c) => c.peer == peer)
        .firstOrNull;
    _openConversation(
      ChatTarget(
        peer: peer,
        alias: known?.alias,
        username: known?.peerName,
      ),
    );
  }

  Future<void> _editAlias() async {
    final ChatTarget? chat = _openChat;
    if (chat == null) return;
    final ContactInfo? saved = await showEditAliasDialog(
      context,
      peer: chat.peer,
      currentAlias: chat.alias,
    );
    if (saved == null) return;
    ref.read(openConversationProvider.notifier).state = chat.copyWith(
      alias: saved.alias.isEmpty ? null : saved.alias,
      clearAlias: saved.alias.isEmpty,
      username: chat.username ?? saved.name,
    );
    // The chat list titles change too.
    ref.read(conversationsProvider.notifier).refresh();
  }

  /// Runs one ⋮ entry. Everything here needs the open chat, so a null target
  /// (the menu closing as the pane clears) is simply a no-op.
  Future<void> _onMenuAction(ChatMenuAction action) async {
    final ChatTarget? chat = _openChat;
    if (chat == null) return;
    switch (action) {
      case ChatMenuAction.setAlias:
        await _editAlias();
      case ChatMenuAction.addComrade:
      case ChatMenuAction.removeComrade:
        await ref.read(comradesProvider.notifier).setComrade(
              chat.peer,
              comrade: action == ChatMenuAction.addComrade,
            );
      case ChatMenuAction.mute:
      case ChatMenuAction.unmute:
        // Native owns the mute set (the notification path reads it with no
        // engine attached) and clears the shade for a freshly-muted peer.
        await ref.read(mutedChatsProvider.notifier).setMuted(
              chat.peer,
              muted: action == ChatMenuAction.mute,
            );
      case ChatMenuAction.copyKey:
        await Clipboard.setData(ClipboardData(text: chat.peer));
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Public key copied')),
          );
        }
      case ChatMenuAction.encryptionInfo:
        await showEncryptionDetailsDialog(context, peer: chat.peer);
      case ChatMenuAction.block:
        if (!await confirmBlockPeer(context)) return;
        // The requests controller owns the only `block` in the state layer and
        // already refreshes both lists; a second implementation here would be
        // one more thing to keep in step.
        await ref.read(messageRequestsProvider.notifier).block(chat.peer);
        // The thread is gone from the list, so staying on it would show a
        // conversation that no longer exists.
        if (mounted) ref.read(openConversationProvider.notifier).state = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final ComradeWindowClass windowClass = windowClassOf(context);
    // Reading it here (rather than in a callback) keeps the header, the FAB
    // and the two-pane selection in sync on every rebuild.
    final ChatTarget? openChat = ref.watch(openConversationProvider);

    final Widget shell = windowClass.usesSidebar
        ? _wideShell(context, openChat)
        : _narrowShell(context, openChat);

    return PopScope<Object?>(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, Object? _) {
        if (didPop) return;
        if (!_handleBack()) SystemNavigator.pop();
      },
      child: Stack(
        children: <Widget>[
          shell,
          // Call overlay — covers the app while a call is ringing/connected,
          // and shrinks to a corner tile when the in-call chat button sends it
          // to picture-in-picture. `onOpenChat` is what makes that useful: the
          // conversation opens *behind* the call as it shrinks.
          CallOverlay(onOpenChat: _openChatDuringCall),
        ],
      ),
    );
  }

  // ── Narrow: Android's shell ───────────────────────────────────────────────

  Widget _narrowShell(BuildContext context, ChatTarget? openChat) {
    final bool inConversation = _tab == MainTab.chats && openChat != null;
    return Scaffold(
      key: _scaffoldKey,
      appBar: _appBar(context, openChat),
      drawer: _Drawer(
        onSelect: (SecondaryDestination d) {
          Navigator.of(context).pop();
          setState(() => _secondary = d);
        },
      ),
      // The conversation view owns the whole screen, Telegram-style.
      bottomNavigationBar: (inConversation || _secondary != null)
          ? null
          // Glass tier chrome (§2): the bar paints nothing of its own —
          // `GlassSurface` supplies the tint, blur, specular edge and shadow.
          : GlassSurface(
              tier: GlassTier.chrome,
              child: NavigationBar(
                backgroundColor: Colors.transparent,
                selectedIndex: _tab.index,
                onDestinationSelected: (int i) => setState(() {
                  _tab = MainTab.values[i];
                  _chatNav = ChatNav.list;
                }),
                destinations: <Widget>[
                  for (final MainTab t in MainTab.values)
                    NavigationDestination(
                      icon: Icon(t.icon),
                      selectedIcon: Icon(t.selectedIcon),
                      label: t.label,
                    ),
                ],
              ),
            ),
      floatingActionButton: (_secondary == null &&
              _tab == MainTab.chats &&
              _chatNav == ChatNav.list &&
              openChat == null)
          ? FloatingActionButton(
              onPressed: () => setState(() => _chatNav = ChatNav.newChat),
              tooltip: 'New chat',
              child: const Icon(Icons.create),
            )
          : null,
      body: _body(context, openChat, twoPane: false),
    );
  }

  // ── Wide: the desktop shell ───────────────────────────────────────────────

  Widget _wideShell(BuildContext context, ChatTarget? openChat) => Scaffold(
        body: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            SizedBox(
              width: Breakpoints.sidebarWidth(MediaQuery.sizeOf(context).width),
              child: _Sidebar(
                tab: _tab,
                secondary: _secondary,
                onTab: (MainTab t) => setState(() {
                  _tab = t;
                  _chatNav = ChatNav.list;
                  _secondary = null;
                }),
                onSecondary: (SecondaryDestination d) =>
                    setState(() => _secondary = d),
              ),
            ),
            VerticalDivider(width: 1, color: context.surfaces.border),
            Expanded(
              child: Column(
                children: <Widget>[
                  _WideHeader(
                    title: _headerTitle(openChat),
                    subtitle: _headerSubtitle(openChat),
                    leading: _headerLeading(openChat),
                    actions: _headerActions(context, openChat),
                  ),
                  Divider(height: 1, color: context.surfaces.border),
                  Expanded(child: _body(context, openChat, twoPane: true)),
                ],
              ),
            ),
          ],
        ),
      );

  // ── Shared header pieces ──────────────────────────────────────────────────

  PreferredSizeWidget _appBar(BuildContext context, ChatTarget? openChat) {
    if (_secondary != null) {
      return AppBar(
        backgroundColor: Colors.transparent,
        flexibleSpace: _glassAppBarBackground,
        leading: BackButton(onPressed: () => setState(() => _secondary = null)),
        title: Text(_secondary!.label),
      );
    }
    if (_tab == MainTab.chats && openChat != null) {
      final String title =
          peerTitle(openChat.peer, openChat.alias, openChat.username);
      return AppBar(
        backgroundColor: Colors.transparent,
        flexibleSpace: _glassAppBarBackground,
        leading: BackButton(onPressed: _closeConversation),
        titleSpacing: 0,
        title: Row(
          children: <Widget>[
            PeerAvatar(title: title, seed: openChat.peer, size: 36),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  // The header always shows the key tail beside the name: a
                  // published @handle is a self-declared claim, so the thing
                  // that actually identifies the peer stays on screen.
                  KeyText(openChat.peer,
                      style: Theme.of(context).textTheme.labelSmall),
                ],
              ),
            ),
          ],
        ),
        actions: _headerActions(context, openChat),
      );
    }
    if (_tab == MainTab.chats && _chatNav != ChatNav.list) {
      return AppBar(
        backgroundColor: Colors.transparent,
        flexibleSpace: _glassAppBarBackground,
        leading: BackButton(
            onPressed: () => setState(() => _chatNav = ChatNav.list)),
        title:
            Text(_chatNav == ChatNav.newChat ? 'New chat' : 'Message requests'),
      );
    }
    return AppBar(
      backgroundColor: Colors.transparent,
      flexibleSpace: _glassAppBarBackground,
      centerTitle: true,
      leading: IconButton(
        key: const Key('nav-drawer-button'),
        tooltip: 'Open navigation menu',
        icon: const Icon(Icons.menu),
        onPressed: () => _scaffoldKey.currentState?.openDrawer(),
      ),
      title: Text(_tab == MainTab.chats ? 'Comrade' : _tab.label),
      // Exactly opposite the hamburger: which route a message takes is a
      // property of the app, not of the screen you happen to be on, so it sits
      // on the one bar every tab shares.
      actions: const <Widget>[TransportPrecedenceAction()],
    );
  }

  String _headerTitle(ChatTarget? openChat) {
    if (_secondary != null) return _secondary!.label;
    if (_tab == MainTab.chats) {
      return switch (_chatNav) {
        ChatNav.newChat => 'New chat',
        ChatNav.requests => 'Message requests',
        ChatNav.list => openChat == null
            ? 'Chats'
            : peerTitle(openChat.peer, openChat.alias, openChat.username),
      };
    }
    return _tab.label;
  }

  String? _headerSubtitle(ChatTarget? openChat) => (_secondary == null &&
          _tab == MainTab.chats &&
          _chatNav == ChatNav.list &&
          openChat != null)
      ? shortNpub(openChat.peer)
      : null;

  Widget? _headerLeading(ChatTarget? openChat) {
    if (_secondary != null) {
      return BackButton(onPressed: () => setState(() => _secondary = null));
    }
    if (_tab == MainTab.chats && _chatNav != ChatNav.list) {
      return BackButton(
          onPressed: () => setState(() => _chatNav = ChatNav.list));
    }
    if (_tab == MainTab.chats && openChat != null) {
      return PeerAvatar(
        title: peerTitle(openChat.peer, openChat.alias, openChat.username),
        seed: openChat.peer,
        size: 32,
      );
    }
    return null;
  }

  List<Widget> _headerActions(BuildContext context, ChatTarget? openChat) {
    if (_secondary != null) return const <Widget>[];
    // The wide shell has a permanent sidebar instead of a hamburger, so the
    // header's trailing edge is the same slot the narrow bar puts it in.
    if (_tab != MainTab.chats) {
      return const <Widget>[TransportPrecedenceAction()];
    }
    if (openChat != null) {
      final bool isComrade = ref.watch(isComradeProvider(openChat.peer));
      // Watched for the same reason as isComrade: the mute set loads on first
      // subscribe, so a lazy read would build the first menu from an empty one.
      final bool isMuted =
          (ref.watch(mutedChatsProvider).value ?? const <String>{})
              .contains(openChat.peer);
      return <Widget>[
        IconButton(
          tooltip: 'Voice call',
          icon: const Icon(Icons.call),
          onPressed: () => _startCall(video: false),
        ),
        IconButton(
          tooltip: 'Video call',
          icon: const Icon(Icons.videocam),
          onPressed: () => _startCall(video: true),
        ),
        // Everything that isn't a call lives behind ⋮, so the bar stays
        // readable as options grow (the alias pencil used to sit out here).
        PopupMenuButton<ChatMenuAction>(
          key: const Key('chat-menu'),
          tooltip: 'More options',
          icon: const Icon(Icons.more_vert),
          // Flutter caps popup menus at 280px, which the longer labels
          // ("Encryption details" beside its glyph) overflow. Widen it rather
          // than truncate a label that tells someone what a tap will do.
          constraints: const BoxConstraints(minWidth: 220, maxWidth: 340),
          onSelected: _onMenuAction,
          // Watched, not read: the comrade list loads on first subscribe, so
          // reading it lazily inside itemBuilder would render the first menu
          // from a not-yet-loaded (false) answer.
          itemBuilder: (BuildContext context) =>
              conversationMenu(isComrade: isComrade, isMuted: isMuted)
                  .map(_menuEntry)
                  .toList(),
        ),
      ];
    }
    if (_chatNav == ChatNav.list) {
      return <Widget>[
        IconButton(
          tooltip: 'New chat',
          icon: const Icon(Icons.create),
          onPressed: () => setState(() => _chatNav = ChatNav.newChat),
        ),
        const TransportPrecedenceAction(),
      ];
    }
    return const <Widget>[];
  }

  /// One row of the ⋮ menu. Labels and glyphs are resolved here so
  /// [conversationMenu] can stay a pure list.
  PopupMenuItem<ChatMenuAction> _menuEntry(ChatMenuAction action) {
    final (String label, IconData icon) = switch (action) {
      ChatMenuAction.setAlias => ('Set alias', Icons.edit),
      // Outline for "not yet", filled for "currently".
      ChatMenuAction.addComrade => ('Make a comrade', Icons.star_border),
      ChatMenuAction.removeComrade => ('Remove as comrade', Icons.star),
      // The glyph shows what tapping *does*, matching the label: a struck-out
      // bell on the row that turns notifications back on would read backwards.
      ChatMenuAction.mute => ('Mute notifications', Icons.notifications_off),
      ChatMenuAction.unmute => ('Unmute notifications', Icons.notifications),
      ChatMenuAction.copyKey => ('Copy public key', Icons.content_copy),
      ChatMenuAction.encryptionInfo => ('Encryption details', Icons.lock),
      ChatMenuAction.block => ('Block this person', Icons.warning),
    };
    final ColorScheme scheme = Theme.of(context).colorScheme;
    final Color color =
        action.destructive ? scheme.error : scheme.onSurfaceVariant;
    return PopupMenuItem<ChatMenuAction>(
      key: Key('chat-menu-${action.name}'),
      value: action,
      child: Row(
        children: <Widget>[
          Icon(icon, size: 20, color: color),
          const SizedBox(width: 12),
          // Flexible so a large system font scale shrinks the label instead of
          // overflowing the row.
          Flexible(
            child: Text(
              label,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: action.destructive ? scheme.error : null,
              ),
            ),
          ),
        ],
      ),
    );
  }

  // ── Body ──────────────────────────────────────────────────────────────────

  Widget _body(BuildContext context, ChatTarget? openChat,
      {required bool twoPane}) {
    if (_secondary != null) {
      return switch (_secondary!) {
        SecondaryDestination.callHistory => CallHistoryScreen(
            onCallBack: (String peer, String label, bool video) => ref
                .read(callProvider.notifier)
                .startOutgoing(peer: peer, peerLabel: label, video: video),
          ),
        SecondaryDestination.partnerPortal => const CoupleScreen(),
        SecondaryDestination.settings => const SettingsScreen(),
      };
    }
    return switch (_tab) {
      MainTab.chats => _chatsBody(context, openChat, twoPane: twoPane),
      MainTab.journal => const JournalScreen(),
      MainTab.feed => const FeedScreen(),
      MainTab.tara => const TaraScreen(),
    };
  }

  Widget _chatsBody(BuildContext context, ChatTarget? openChat,
      {required bool twoPane}) {
    switch (_chatNav) {
      case ChatNav.newChat:
        return NewChatScreen(onOpen: _openConversation);
      case ChatNav.requests:
        return RequestsScreen(onOpen: _openConversation);
      case ChatNav.list:
        final Widget list = ChatsListScreen(
          onOpen: _openConversation,
          onNewChat: () => setState(() => _chatNav = ChatNav.newChat),
          onOpenRequests: () => setState(() => _chatNav = ChatNav.requests),
          selectedPeer: twoPane ? openChat?.peer : null,
        );
        return ListDetailPane(
          list: list,
          hasSelection: openChat != null,
          detail: () => ConversationScreen(peer: openChat!.peer),
          placeholder: const EmptyState(
            title: 'Select a conversation',
            body: 'Incoming encrypted DMs appear on the left automatically.',
          ),
        );
    }
  }
}

/// The wide-window header. Not an [AppBar]: it sits inside the content column
/// beside the sidebar, so it must not claim the whole window width.
class _WideHeader extends StatelessWidget {
  const _WideHeader({
    required this.title,
    this.subtitle,
    this.leading,
    this.actions = const <Widget>[],
  });

  final String title;
  final String? subtitle;
  final Widget? leading;
  final List<Widget> actions;

  // Glass tier chrome (§2, "top bar"): the fixed height comes from this
  // `Container`, the fill/blur/specular edge/shadow from `GlassSurface`.
  @override
  Widget build(BuildContext context) => GlassSurface(
        tier: GlassTier.chrome,
        child: Container(
          height: 60,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Row(
            children: <Widget>[
              if (leading != null) ...<Widget>[
                leading!,
                const SizedBox(width: 10)
              ],
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    if (subtitle != null)
                      KeyText(
                        subtitle!,
                        short: false,
                        style: Theme.of(context).textTheme.labelSmall,
                      ),
                  ],
                ),
              ),
              ...actions,
            ],
          ),
        ),
      );
}

/// The persistent desktop sidebar: brand + workspace badge, destinations,
/// modes, and the network/identity footer.
class _Sidebar extends ConsumerWidget {
  const _Sidebar({
    required this.tab,
    required this.secondary,
    required this.onTab,
    required this.onSecondary,
  });

  final MainTab tab;
  final SecondaryDestination? secondary;
  final ValueChanged<MainTab> onTab;
  final ValueChanged<SecondaryDestination> onSecondary;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ComradeSurfaces surfaces = context.surfaces;
    final WorkspaceInfo workspace =
        ref.watch(workspaceProvider).value ?? const WorkspaceInfo.base();
    final Profile? profile = ref.watch(profileProvider);
    final int requests = ref.watch(messageRequestCountProvider);

    return Material(
      color: surfaces.panel,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 12, 8),
            child: Row(
              children: <Widget>[
                Text(
                  '⬢',
                  style: TextStyle(
                    fontSize: 18,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Comrade',
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                ),
                _Pill(
                  label: workspace.meshActive
                      ? 'Off-Grid'
                      : workspace.coupleSandbox
                          ? 'Couples'
                          : 'Base',
                  on: !workspace.meshActive,
                ),
              ],
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              children: <Widget>[
                for (final MainTab t in MainTab.values)
                  _SidebarItem(
                    icon: t.icon,
                    label: t.label,
                    selected: secondary == null && tab == t,
                    badge: t == MainTab.chats && requests > 0 ? requests : null,
                    onTap: () => onTab(t),
                  ),
                const SizedBox(height: 12),
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 4, 12, 4),
                  child: Text(
                    'MORE',
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                          color: Theme.of(context).colorScheme.primary,
                          letterSpacing: 0.8,
                          fontWeight: FontWeight.bold,
                        ),
                  ),
                ),
                for (final SecondaryDestination d
                    in SecondaryDestination.values)
                  _SidebarItem(
                    icon: d.icon,
                    label: d.label,
                    selected: secondary == d,
                    onTap: () => onSecondary(d),
                  ),
              ],
            ),
          ),
          Divider(height: 1, color: surfaces.border),
          Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: <Widget>[
                Row(
                  children: <Widget>[
                    _Pill(label: 'relays', on: workspace.relayConnected),
                    const SizedBox(width: 6),
                    _Pill(label: 'mesh', on: workspace.meshActive),
                  ],
                ),
                const SizedBox(height: 10),
                if (profile != null)
                  InkWell(
                    onTap: () async {
                      await Clipboard.setData(
                        ClipboardData(text: profile.npub),
                      );
                      if (context.mounted) {
                        ScaffoldMessenger.maybeOf(context)?.showSnackBar(
                          const SnackBar(content: Text('npub copied')),
                        );
                      }
                    },
                    child: Row(
                      children: <Widget>[
                        PeerAvatar(
                          title: profile.username ?? profile.npub,
                          seed: profile.npub,
                          size: 26,
                        ),
                        const SizedBox(width: 8),
                        Expanded(child: KeyText(profile.npub)),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SidebarItem extends StatelessWidget {
  const _SidebarItem({
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
    this.badge,
  });

  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;
  final int? badge;

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Material(
        color: selected ? colors.primaryContainer.withValues(alpha: 0.5) : null,
        borderRadius: BorderRadius.circular(ComradeRadii.extraSmall),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(ComradeRadii.extraSmall),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Row(
              children: <Widget>[
                Icon(
                  icon,
                  size: 20,
                  color: selected ? colors.primary : colors.onSurfaceVariant,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    label,
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          fontWeight:
                              selected ? FontWeight.w600 : FontWeight.normal,
                        ),
                  ),
                ),
                if (badge != null)
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                    decoration: BoxDecoration(
                      color: colors.primary,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Text(
                      '$badge',
                      style: TextStyle(color: colors.onPrimary, fontSize: 11),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.label, required this.on});

  final String label;
  final bool on;

  @override
  Widget build(BuildContext context) {
    final ComradeSurfaces surfaces = context.surfaces;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: on
            ? surfaces.good.withValues(alpha: 0.18)
            : surfaces.border.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: on ? surfaces.good : Theme.of(context).colorScheme.outline,
            ),
      ),
    );
  }
}

/// The navigation drawer: a profile header (tap → Settings) over the app-wide
/// destinations that don't belong in the bottom bar. Message requests
/// deliberately stay in the chat list, not here.
class _Drawer extends ConsumerWidget {
  const _Drawer({required this.onSelect});

  final ValueChanged<SecondaryDestination> onSelect;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final Profile? profile = ref.watch(profileProvider);
    return Drawer(
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            if (profile != null)
              InkWell(
                key: const Key('drawer-profile'),
                onTap: () => onSelect(SecondaryDestination.settings),
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Row(
                    children: <Widget>[
                      PeerAvatar(
                        title: profile.username ?? profile.npub,
                        seed: profile.npub,
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: <Widget>[
                            Text(
                              profile.username != null
                                  ? '@${profile.username}'
                                  : 'No username yet',
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                            KeyText(profile.npub),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            const Divider(height: 1),
            for (final SecondaryDestination d in SecondaryDestination.values)
              ListTile(
                key: Key('drawer-${d.name}'),
                leading: Icon(d.icon),
                title: Text(d.label),
                onTap: () => onSelect(d),
              ),
          ],
        ),
      ),
    );
  }
}
