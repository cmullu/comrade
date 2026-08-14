/// An in-memory [ComradeRepository] with believable data.
///
/// Two jobs:
///  * make the app runnable end-to-end before `flutter_rust_bridge` lands
///    (the same role `mockBackend()` played in `desktop/ui/main.js`), and
///  * make every screen widget-testable without a native library, which the
///    Compose screens never were — they called the `ComradeCore` singleton
///    directly, so their tests had to be instrumented.
///
/// It is deliberately *not* a stub that returns empty lists: seeded data is
/// what makes a layout regression visible.
library;

import 'dart:async';
import 'dart:math';
import 'dart:typed_data';

import 'comrade_repository.dart';
import 'models.dart';

class FakeComradeRepository implements ComradeRepository {
  FakeComradeRepository({
    this.latency = const Duration(milliseconds: 120),
    bool seed = true,
    int? nowSecs,
  }) : _now = nowSecs ?? DateTime.now().millisecondsSinceEpoch ~/ 1000 {
    if (seed) _seed();
  }

  /// Simulated backend latency. Widget tests pass [Duration.zero] so a single
  /// `pump` settles them.
  final Duration latency;

  final int _now;
  final Random _random = Random(0x0C0FFEE);
  final StreamController<BridgeEvent> _events =
      StreamController<BridgeEvent>.broadcast();

  Profile? _profile;
  bool _unlocked = false;
  WorkspaceInfo _workspace = const WorkspaceInfo.base();
  SakhaStatus _sakha = const SakhaStatus.unpaired();
  String _ledger = '';
  TurnServerStatus _turn = const TurnServerStatus.none();
  MeshStatus _mesh = const MeshStatus.idle();

  final List<ContactInfo> _contacts = <ContactInfo>[];
  final Map<String, List<MessageInfo>> _messages =
      <String, List<MessageInfo>>{};
  final Map<String, List<MediaMessageInfo>> _media =
      <String, List<MediaMessageInfo>>{};

  /// Reactions keyed `<peer>` → `<targetId>:<reactor>` → row, mirroring the
  /// store's own `(target, reactor)` key so the fake enforces the same "one
  /// reaction per person per message" rule the real one does. A fake that let
  /// them stack would let a widget test pass on behaviour the app cannot produce.
  final Map<String, Map<String, ReactionInfo>> _reactions =
      <String, Map<String, ReactionInfo>>{};
  final List<MessageRequestInfo> _requests = <MessageRequestInfo>[];
  final List<ChitthiInfo> _feed = <ChitthiInfo>[];
  final List<JournalEntryInfo> _journal = <JournalEntryInfo>[];
  final List<TaraMessageInfo> _tara = <TaraMessageInfo>[];
  final List<CallRecordInfo> _calls = <CallRecordInfo>[];

  /// This device's own npub in the fake. Named rather than repeated inline so
  /// "did *I* react to this" and "who am I" cannot drift apart.
  static const String _selfNpub =
      'npub1youridentity0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2q7m4te';

  static const String _alicePub =
      'npub1alice7q0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qtqy3gq';
  static const String _bobPub =
      'npub1bob4laefqx0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qwx9m';
  static const String _strangerPub =
      'npub1stranger0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qk4h7p';

  void _seed() {
    // Alice is a comrade who has marked us back and is online; Bhaskar is an
    // ordinary contact. Both states are seeded because the interesting bug is
    // a UI that renders only one of them.
    _contacts
      ..add(const ContactInfo(
          npub: _alicePub, alias: 'Amma', name: 'alice', comrade: true))
      ..add(const ContactInfo(npub: _bobPub, alias: '', name: 'bhaskar'));
    _presence[_alicePub] = PresenceInfo(
      peer: _alicePub,
      online: true,
      lastSeenAt: _now - 45,
      peerMarkedUs: true,
    );

    // Two calendar days of history so the day separators actually render.
    final int yesterday = _now - 26 * 3600;
    _messages[_alicePub] = <MessageInfo>[
      MessageInfo(
        id: 'm1',
        peer: _alicePub,
        content: 'Reached the station. Long queue but fine.',
        createdAt: yesterday,
        outgoing: false,
      ),
      MessageInfo(
        id: 'm2',
        peer: _alicePub,
        content: 'Good. Message when you board.',
        createdAt: yesterday + 240,
        outgoing: true,
        status: MessageStatus.read,
      ),
      MessageInfo(
        id: 'm3',
        peer: _alicePub,
        content: 'Boarded 🙂',
        createdAt: _now - 5400,
        outgoing: false,
      ),
      MessageInfo(
        id: 'm4',
        peer: _alicePub,
        content: 'Safe travels.',
        createdAt: _now - 5100,
        outgoing: true,
        status: MessageStatus.delivered,
        replyTo: 'm3',
      ),
    ];
    _media[_alicePub] = <MediaMessageInfo>[
      MediaMessageInfo(
        eventId: 'media-1',
        url: 'blossom://fake/1',
        mimeType: 'audio/aac',
        caption: '',
        sender: _alicePub,
        createdAt: _now - 5000,
        size: 24_512,
        outgoing: false,
      ),
    ];
    _messages[_bobPub] = <MessageInfo>[
      MessageInfo(
        id: 'm5',
        peer: _bobPub,
        content: 'Sent the notes.',
        createdAt: _now - 900,
        outgoing: false,
      ),
      MessageInfo(
        id: 'm6',
        peer: _bobPub,
        content: 'Got them, thanks.',
        createdAt: _now - 840,
        outgoing: true,
        status: MessageStatus.sent,
      ),
    ];

    _requests.add(
      MessageRequestInfo(
        peer: _strangerPub,
        lastMessage: 'Saw your Chitthi — mind if we chat?',
        lastAt: _now - 300,
      ),
    );

    _feed
      ..add(ChitthiInfo(
        id: 'c1',
        author: _alicePub,
        content: 'Namaste from the Sabha.',
        createdAt: _now - 600,
      ))
      ..add(ChitthiInfo(
        id: 'c2',
        author: _bobPub,
        content:
            'Off-grid mesh held up for the whole trek. No towers, no relays.',
        createdAt: _now - 90,
        replyTo: 'c1',
      ));

    _journal
      ..add(JournalEntryInfo(
        id: 'j1',
        text: 'Slept badly, but the morning walk helped.',
        mood: '🙂',
        createdAt: _now - 3600,
      ))
      ..add(JournalEntryInfo(
        id: 'j2',
        text: 'Long day. Wrote the difficult message and did not send it.',
        mood: '😐',
        createdAt: yesterday,
      ));

    _calls
      ..add(CallRecordInfo(
        id: 'call-1',
        peer: _alicePub,
        media: 'audio',
        incoming: true,
        outcome: 'connected',
        startedAt: _now - 7200,
        durationSecs: 305,
      ))
      ..add(CallRecordInfo(
        id: 'call-2',
        peer: _bobPub,
        media: 'video',
        incoming: true,
        outcome: 'missed',
        startedAt: _now - 20000,
        durationSecs: 0,
      ));
  }

  Future<T> _io<T>(T Function() body) async {
    if (latency > Duration.zero) await Future<void>.delayed(latency);
    return body();
  }

  void _requireUnlocked() {
    if (!_unlocked) throw const ComradeException('The vault is locked.');
  }

  String _id(String prefix) =>
      '$prefix-${_random.nextInt(1 << 32).toRadixString(16)}';

  int get _clock => DateTime.now().millisecondsSinceEpoch ~/ 1000;

  /// Push an event as though the core had emitted it — used by tests and by
  /// the fake's own optimistic sends.
  void emit(BridgeEvent event) {
    if (!_events.isClosed) _events.add(event);
  }

  // ── Lifecycle ────────────────────────────────────────────────────────────

  @override
  Future<String> version() async => '0.1.0-fake';

  @override
  Future<Profile> unlockVault(
          {required String path, required String passphrase}) =>
      _io(() {
        if (passphrase.trim().isEmpty) {
          throw const ComradeException('Enter your passcode.');
        }
        if (passphrase == 'wrong') {
          throw const ComradeException(
              'Vault unlock failed: wrong passphrase.');
        }
        _unlocked = true;
        _profile ??= const Profile(npub: _selfNpub, username: 'you');
        return _profile!;
      });

  @override
  Future<Profile?> currentProfile() => _io(() => _unlocked ? _profile : null);

  @override
  Future<void> lockVault() => _io(() {
        _unlocked = false;
      });

  // ── Identity & contacts ──────────────────────────────────────────────────

  @override
  Future<Profile> setUsername(String name) => _io(() {
        _requireUnlocked();
        final String handle = name.trim().replaceFirst(RegExp('^@'), '');
        if (handle.length < 3 || handle.length > 24) {
          throw const ComradeException('Username must be 3–24 characters.');
        }
        if (!RegExp(r'^\w+$').hasMatch(handle)) {
          throw const ComradeException(
              'Only letters, numbers and _ are allowed.');
        }
        _profile = (_profile ?? const Profile(npub: _selfNpub))
            .copyWith(username: handle);
        return _profile!;
      });

  @override
  Future<List<FoundProfile>> searchProfiles(String query) => _io(() {
        _requireUnlocked();
        final String q =
            query.trim().replaceFirst(RegExp('^@'), '').toLowerCase();
        if (q.isEmpty) return const <FoundProfile>[];
        return <FoundProfile>[
          for (final ContactInfo c in _contacts)
            if ((c.name ?? '').toLowerCase().contains(q))
              FoundProfile(npub: c.npub, name: c.name, about: 'known contact'),
          if ('charlie'.contains(q))
            const FoundProfile(
              npub:
                  'npub1charlie0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2q9r2f',
              name: 'charlie',
              about: 'reachable on the public relays',
            ),
        ];
      });

  @override
  Future<List<ContactInfo>> contacts() =>
      _io(() => List<ContactInfo>.unmodifiable(_contacts));

  @override
  Future<ContactInfo> addContact({required String npub, String alias = ''}) =>
      _io(() {
        _requireUnlocked();
        if (!npub.startsWith('npub1') || npub.length < 20) {
          throw const ComradeException("That doesn't look like a valid key.");
        }
        final int i = _contacts.indexWhere((ContactInfo c) => c.npub == npub);
        final ContactInfo saved = ContactInfo(
          npub: npub,
          alias: alias,
          name: i >= 0 ? _contacts[i].name : null,
          comrade: i >= 0 && _contacts[i].comrade,
        );
        if (i >= 0) {
          _contacts[i] = saved;
        } else {
          _contacts.add(saved);
        }
        return saved;
      });

  @override
  Future<ContactInfo> setContactAlias(
          {required String npub, required String alias}) =>
      _io(() {
        _requireUnlocked();
        final int i = _contacts.indexWhere((ContactInfo c) => c.npub == npub);
        final ContactInfo saved = ContactInfo(
          npub: npub,
          alias: alias,
          name: i >= 0 ? _contacts[i].name : null,
          comrade: i >= 0 && _contacts[i].comrade,
        );
        if (i >= 0) {
          _contacts[i] = saved;
        } else {
          _contacts.add(saved);
        }
        return saved;
      });

  // ── Comrades (chosen-peer presence) ──────────────────────────────────────

  /// Last beacon heard from a peer, keyed by npub. Only peers seeded into
  /// [_presence] can ever show as online — a fake that reported everyone
  /// online would hide exactly the state the UI has to explain.
  final Map<String, PresenceInfo> _presence = <String, PresenceInfo>{};

  @override
  Future<ContactInfo> setComrade({
    required String npub,
    required bool comrade,
  }) =>
      _io(() {
        _requireUnlocked();
        final int i = _contacts.indexWhere((ContactInfo c) => c.npub == npub);
        final ContactInfo saved = ContactInfo(
          npub: npub,
          alias: i >= 0 ? _contacts[i].alias : '',
          name: i >= 0 ? _contacts[i].name : null,
          comrade: comrade,
        );
        if (i >= 0) {
          _contacts[i] = saved;
        } else {
          // Marking someone never saved creates the contact, like the core.
          _contacts.add(saved);
        }
        // Un-marking drops what we knew about them: presence stops flowing
        // the moment the choice is withdrawn.
        if (!comrade) _presence.remove(npub);
        return saved;
      });

  @override
  Future<List<ComradeInfo>> comrades() => _io(() {
        _requireUnlocked();
        final List<ComradeInfo> rows = <ComradeInfo>[
          for (final ContactInfo c in _contacts)
            if (c.comrade)
              ComradeInfo(
                npub: c.npub,
                alias: c.alias,
                name: c.name,
                online: _presence[c.npub]?.online ?? false,
                lastSeenAt: _presence[c.npub]?.lastSeenAt ?? 0,
                peerMarkedUs: _presence.containsKey(c.npub),
              ),
        ]..sort((ComradeInfo a, ComradeInfo b) {
            final int byOnline = (b.online ? 1 : 0).compareTo(a.online ? 1 : 0);
            if (byOnline != 0) return byOnline;
            final int bySeen = b.lastSeenAt.compareTo(a.lastSeenAt);
            return bySeen != 0 ? bySeen : a.npub.compareTo(b.npub);
          });
        return List<ComradeInfo>.unmodifiable(rows);
      });

  @override
  Future<PresenceInfo?> peerPresence(String npub) => _io(() {
        _requireUnlocked();
        return _presence[npub];
      });

  @override
  Future<int> announcePresence(bool online) =>
      // Never throws, even locked — presence is a courtesy, like the core.
      _io(() =>
          _unlocked ? _contacts.where((ContactInfo c) => c.comrade).length : 0);

  /// Every composer report the UI has made, in order — `('note', npub)` while
  /// text is in the box, `('abandon', npub)` once it is gone.
  ///
  /// The fake records rather than simulates: turning the pair into a nudge is
  /// timing policy that lives in `comrade_core::nudge` and is tested there, so
  /// duplicating it here would only give the widget tests a second, drifting
  /// answer. What a widget test needs to know is that the screen *reported*.
  final List<(String, String)> draftReports = <(String, String)>[];

  @override
  Future<void> noteDraft(String npub) =>
      _io(() => draftReports.add(('note', npub)));

  @override
  Future<void> abandonDraft(String npub) =>
      _io(() => draftReports.add(('abandon', npub)));

  /// Push a nudge as though [npub] had written something for us and given up on
  /// it. Test-facing, like [seedPresence] below.
  void seedNudge(String npub) => emit(IncomingComradeNudge(
        peer: npub,
        name: _contactFor(npub)?.name,
      ));

  /// Push a beacon as though [npub] had sent one, and emit the transition.
  /// Test-facing, like [emit]: nothing in the fake's own flow makes a peer
  /// come online, because nothing in it talks to a peer.
  void seedPresence(String npub, {required bool online, int? at}) {
    final int when = at ?? _clock;
    _presence[npub] = PresenceInfo(
      peer: npub,
      online: online,
      lastSeenAt: when,
      peerMarkedUs: true,
    );
    emit(ComradePresenceChanged(
      peer: npub,
      name: _contactFor(npub)?.name,
      online: online,
      at: when,
    ));
  }

  // ── Conversations ────────────────────────────────────────────────────────

  @override
  Future<List<ConversationInfo>> conversations() => _io(() {
        _requireUnlocked();
        final List<ConversationInfo> rows = <ConversationInfo>[];
        for (final MapEntry<String, List<MessageInfo>> e in _messages.entries) {
          if (e.value.isEmpty) continue;
          final MessageInfo last = e.value.last;
          final ContactInfo? c = _contactFor(e.key);
          rows.add(
            ConversationInfo(
              peer: e.key,
              alias: (c?.alias.isNotEmpty ?? false) ? c!.alias : null,
              peerName: c?.name,
              lastMessage: last.content,
              lastAt: last.createdAt,
              lastOutgoing: last.outgoing,
              comrade: c?.comrade ?? false,
              // Presence only flows between comrades, so a non-comrade is
              // always offline here — there is nothing to know.
              online:
                  (c?.comrade ?? false) && (_presence[e.key]?.online ?? false),
            ),
          );
        }
        rows.sort((ConversationInfo a, ConversationInfo b) =>
            b.lastAt.compareTo(a.lastAt));
        return rows;
      });

  ContactInfo? _contactFor(String npub) {
    for (final ContactInfo c in _contacts) {
      if (c.npub == npub) return c;
    }
    return null;
  }

  @override
  Future<List<MessageInfo>> messages(String peer) => _io(() {
        _requireUnlocked();
        return List<MessageInfo>.unmodifiable(
            _messages[peer] ?? const <MessageInfo>[]);
      });

  @override
  Future<MessageInfo> sendDm({
    required String peer,
    required String content,
    String? replyTo,
  }) =>
      _io(() {
        _requireUnlocked();
        final MessageInfo msg = MessageInfo(
          id: _id('msg'),
          peer: peer,
          content: content,
          createdAt: _clock,
          outgoing: true,
          status: MessageStatus.sent,
          replyTo: (replyTo?.isEmpty ?? true) ? null : replyTo,
        );
        (_messages[peer] ??= <MessageInfo>[]).add(msg);
        return msg;
      });

  /// Per-peer read position, mirroring `ConversationMeta.last_read_at` in the
  /// real encrypted store. Absent means never opened.
  final Map<String, int> _readPositions = <String, int>{};

  /// Pretend the thread was last read up to [seenAt], so a test can set up
  /// "opened yesterday, three messages since" without driving the clock.
  void seedReadPosition(String peer, int seenAt) {
    _readPositions[peer] = seenAt;
  }

  @override
  Future<int> markConversationRead(String peer) => _io(() {
        final int previous = _readPositions[peer] ?? 0;
        final int newest = (_messages[peer] ?? const <MessageInfo>[]).fold<int>(
            0, (int a, MessageInfo m) => m.createdAt > a ? m.createdAt : a);
        // Monotonic, like the store: re-opening a thread scrolled up must not
        // drag the watermark backwards.
        if (newest > previous) _readPositions[peer] = newest;
        return previous;
      });

  // ── Threads and topics (see `comrade_core::topic`) ───────────────────────
  //
  // An in-memory model of the real one, with the two rules that actually matter
  // to a widget test: the slug is the id (so naming a topic twice is one topic)
  // and a thread is the transitive closure of `replyTo`. The slug rules here are
  // deliberately the shapes a test types, not a second implementation of
  // `comrade_core::topic::slugify` — the real one is where a name is refused.

  /// `peer -> slug -> topic`.
  final Map<String, Map<String, TopicInfo>> _topics =
      <String, Map<String, TopicInfo>>{};

  /// `rootId -> slug`, across every conversation, as the real store keys it.
  final Map<String, String?> _filings = <String, String?>{};

  String _slugify(String name) => name
      .trim()
      .replaceFirst(RegExp(r'^#'), '')
      .toLowerCase()
      .replaceAll(RegExp(r'[^a-z0-9_-]+'), '-')
      .replaceAll(RegExp(r'^-+|-+$'), '');

  /// The oldest ancestor of [id] by `replyTo`, bounded the way core's
  /// `root_of` is. A message we do not hold is its own root, which is what
  /// keeps a reply to something outside the window visible.
  String _rootOf(String peer, String id) {
    final List<MessageInfo> msgs = _messages[peer] ?? const <MessageInfo>[];
    String current = id;
    final Set<String> seen = <String>{};
    for (int i = 0; i < 64; i++) {
      if (!seen.add(current)) break;
      final String? parent = msgs
          .cast<MessageInfo?>()
          .firstWhere((MessageInfo? m) => m?.id == current, orElse: () => null)
          ?.replyTo;
      if (parent == null || parent.isEmpty || parent == current) break;
      current = parent;
    }
    return current;
  }

  List<ThreadInfo> _threadRows(String peer) {
    final List<MessageInfo> msgs = _messages[peer] ?? const <MessageInfo>[];
    final Map<String, List<MessageInfo>> grouped =
        <String, List<MessageInfo>>{};
    for (final MessageInfo m in msgs) {
      (grouped[_rootOf(peer, m.id)] ??= <MessageInfo>[]).add(m);
    }
    final List<ThreadInfo> rows = <ThreadInfo>[];
    grouped.forEach((String root, List<MessageInfo> members) {
      members.sort(
          (MessageInfo a, MessageInfo b) => a.createdAt.compareTo(b.createdAt));
      final MessageInfo? head = members
          .cast<MessageInfo?>()
          .firstWhere((MessageInfo? m) => m?.id == root, orElse: () => null);
      final int readTo = _readPositions[peer] ?? 0;
      rows.add(ThreadInfo(
        rootId: root,
        topicSlug: _filings[root],
        preview: head?.content ?? '',
        rootIsMedia: false,
        rootMissing: head == null,
        replyCount: members.length - (head == null ? 0 : 1),
        lastAt: members.last.createdAt,
        unread:
            members.any((MessageInfo m) => !m.outgoing && m.createdAt > readTo),
      ));
    });
    rows.sort((ThreadInfo a, ThreadInfo b) => b.lastAt.compareTo(a.lastAt));
    return rows;
  }

  @override
  Future<List<TopicInfo>> topics(String peer) => _io(() {
        _requireUnlocked();
        final List<ThreadInfo> rows = _threadRows(peer);
        return (_topics[peer] ?? const <String, TopicInfo>{})
            .values
            .map((TopicInfo t) {
          final List<ThreadInfo> filed =
              rows.where((ThreadInfo r) => r.topicSlug == t.slug).toList();
          return TopicInfo(
            slug: t.slug,
            name: t.name,
            closed: t.closed,
            mine: t.mine,
            threadCount: filed.length,
            messageCount: filed.fold<int>(
                0, (int a, ThreadInfo r) => a + r.replyCount + 1),
            lastActivityAt: filed.fold<int>(t.lastActivityAt,
                (int a, ThreadInfo r) => r.lastAt > a ? r.lastAt : a),
          );
        }).toList()
          ..sort((TopicInfo a, TopicInfo b) => a.slug.compareTo(b.slug));
      });

  @override
  Future<List<ThreadInfo>> threads(String peer, {String? topicSlug}) => _io(() {
        _requireUnlocked();
        final List<ThreadInfo> rows = _threadRows(peer);
        return topicSlug == null
            ? rows
            : rows.where((ThreadInfo r) => r.topicSlug == topicSlug).toList();
      });

  @override
  Future<ThreadDetail> thread({
    required String peer,
    required String rootId,
  }) =>
      _io(() {
        _requireUnlocked();
        final String root = _rootOf(peer, rootId);
        final List<MessageInfo> members = (_messages[peer] ??
                const <MessageInfo>[])
            .where((MessageInfo m) => _rootOf(peer, m.id) == root)
            .toList()
          ..sort((MessageInfo a, MessageInfo b) =>
              a.createdAt.compareTo(b.createdAt));
        return ThreadDetail(
          rootId: root,
          topicSlug: _filings[root],
          messages: List<MessageInfo>.unmodifiable(members),
          media: const <MediaMessageInfo>[],
        );
      });

  @override
  Future<TopicInfo> createTopic({
    required String peer,
    required String name,
  }) =>
      _io(() => _upsertTopic(peer, name));

  TopicInfo _upsertTopic(String peer, String name) {
    _requireUnlocked();
    final String slug = _slugify(name);
    if (slug.length < 2) {
      throw StateError('A topic name needs two or more letters or digits');
    }
    final Map<String, TopicInfo> byslug =
        _topics[peer] ??= <String, TopicInfo>{};
    // The slug is the id: naming the same word twice is the same topic, and the
    // first spelling is the one that is kept.
    return byslug[slug] ??= TopicInfo(
      slug: slug,
      name: name.trim().replaceFirst(RegExp(r'^#'), ''),
      closed: false,
      threadCount: 0,
      messageCount: 0,
      lastActivityAt: _clock,
    );
  }

  @override
  Future<ThreadInfo> assignThread({
    required String peer,
    required String messageId,
    String? topicName,
  }) =>
      _io(() {
        _requireUnlocked();
        final String root = _rootOf(peer, messageId);
        _filings[root] = (topicName == null || topicName.trim().isEmpty)
            ? null
            : _upsertTopic(peer, topicName).slug;
        return _threadRows(peer).firstWhere((ThreadInfo r) => r.rootId == root);
      });

  @override
  Future<TopicInfo> setTopicClosed({
    required String peer,
    required String slug,
    required bool closed,
  }) =>
      _io(() {
        _requireUnlocked();
        final TopicInfo? existing = _topics[peer]?[slug];
        if (existing == null) throw StateError('no such topic');
        final TopicInfo next = TopicInfo(
          slug: existing.slug,
          name: existing.name,
          closed: closed,
          mine: existing.mine,
          threadCount: existing.threadCount,
          messageCount: existing.messageCount,
          lastActivityAt: existing.lastActivityAt,
        );
        _topics[peer]![slug] = next;
        return next;
      });

  @override
  Future<MessageInfo> sendThreadReply({
    required String peer,
    required String rootId,
    required String content,
  }) =>
      // Addressed to the *root*, not to `rootId` as given — the flatness is the
      // feature, and a fake that quoted the last message instead would let a
      // widget test pass on behaviour the real one does not have.
      sendDm(peer: peer, content: content, replyTo: _rootOf(peer, rootId));

  @override
  Future<List<MessageRequestInfo>> messageRequests() => _io(() {
        _requireUnlocked();
        return List<MessageRequestInfo>.unmodifiable(_requests);
      });

  @override
  Future<void> acceptRequest(String peer) => _io(() {
        _requests.removeWhere((MessageRequestInfo r) => r.peer == peer);
        _messages.putIfAbsent(
          peer,
          () => <MessageInfo>[
            MessageInfo(
              id: _id('msg'),
              peer: peer,
              content: 'Saw your Chitthi — mind if we chat?',
              createdAt: _clock - 300,
              outgoing: false,
            ),
          ],
        );
      });

  @override
  Future<void> blockConversation(String peer) => _io(() {
        _requests.removeWhere((MessageRequestInfo r) => r.peer == peer);
        _messages.remove(peer);
      });

  // ── Encrypted media ──────────────────────────────────────────────────────

  @override
  Future<List<MediaMessageInfo>> media(String peer) => _io(() {
        _requireUnlocked();
        return List<MediaMessageInfo>.unmodifiable(
          _media[peer] ?? const <MediaMessageInfo>[],
        );
      });

  @override
  Future<List<ReactionInfo>> reactions(String peer) => _io(() {
        _requireUnlocked();
        return List<ReactionInfo>.unmodifiable(
          (_reactions[peer] ?? const <String, ReactionInfo>{})
              .values
              .where((ReactionInfo r) => r.emoji.isNotEmpty)
              .toList()
            ..sort((ReactionInfo a, ReactionInfo b) =>
                a.createdAt.compareTo(b.createdAt)),
        );
      });

  @override
  Future<ReactionInfo?> toggleReaction({
    required String peer,
    required String targetId,
    required String emoji,
  }) =>
      _io(() {
        _requireUnlocked();
        final Map<String, ReactionInfo> rows =
            _reactions[peer] ??= <String, ReactionInfo>{};
        final String key = '$targetId:$_selfNpub';
        // The toggle rule the real implementation applies in Rust: tapping what
        // you already sent withdraws it, anything else replaces.
        final bool clearing = rows[key]?.emoji == emoji;
        final ReactionInfo row = ReactionInfo(
          targetId: targetId,
          peer: peer,
          reactor: _selfNpub,
          emoji: clearing ? '' : emoji,
          createdAt: _clock,
          outgoing: true,
        );
        rows[key] = row;
        return clearing ? null : row;
      });

  @override
  Future<MediaMessageInfo> sendMedia({
    required String peer,
    required String mimeType,
    required String caption,
    required Uint8List bytes,
  }) =>
      _io(() {
        _requireUnlocked();
        if (bytes.length > 10 * 1024 * 1024) {
          throw const ComradeException('Attachments are limited to 10 MB.');
        }
        final MediaMessageInfo info = MediaMessageInfo(
          eventId: _id('media'),
          url: 'blossom://fake/${bytes.length}',
          mimeType: mimeType,
          caption: caption,
          sender: _profile?.npub ?? 'me',
          createdAt: _clock,
          size: bytes.length,
          outgoing: true,
        );
        (_media[peer] ??= <MediaMessageInfo>[]).add(info);
        return info;
      });

  @override
  Future<MediaBytes> downloadMedia(String eventId) => _io(() {
        // A 1×1 transparent PNG, like the desktop mock's — enough for an
        // <Image.memory> to render without shipping a fixture file.
        const List<int> png = <int>[
          137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, //
          0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, 196, 137, //
          0, 0, 0, 13, 73, 68, 65, 84, 120, 156, 99, 250, 207, 0, 0, //
          3, 1, 1, 0, 24, 221, 141, 219, 0, 0, 0, 0, 73, 69, 78, 68, //
          174, 66, 96, 130,
        ];
        return MediaBytes(
            mimeType: 'image/png', bytes: Uint8List.fromList(png));
      });

  // ── Sabha ────────────────────────────────────────────────────────────────

  @override
  Future<List<ChitthiInfo>> sabhaTimeline() => _io(() {
        _requireUnlocked();
        return List<ChitthiInfo>.unmodifiable(_feed.reversed);
      });

  @override
  Future<String> broadcastChitthi(String content, {String? replyTo}) => _io(() {
        _requireUnlocked();
        final String id = _id('chitthi');
        _feed.add(
          ChitthiInfo(
            id: id,
            author: _profile?.npub ?? 'you',
            content: content,
            createdAt: _clock,
            replyTo: replyTo,
          ),
        );
        return id;
      });

  @override
  Future<List<UpiIntent>> extractPayments(String text) => _io(() {
        final RegExp re = RegExp(
          r'/pay\s+(\d+(?:\.\d{1,2})?)\s+to\s+([\w.\-]+@[A-Za-z0-9]+)',
          caseSensitive: false,
        );
        return <UpiIntent>[
          for (final RegExpMatch m in re.allMatches(text))
            UpiIntent(
              amountInr: double.parse(m.group(1)!),
              vpa: m.group(2)!,
              uri: 'upi://pay?pa=${m.group(2)}&am=${m.group(1)}',
            ),
        ];
      });

  // ── Journal ──────────────────────────────────────────────────────────────

  @override
  Future<List<JournalEntryInfo>> journal() => _io(() {
        _requireUnlocked();
        final List<JournalEntryInfo> rows = List<JournalEntryInfo>.of(_journal)
          ..sort((JournalEntryInfo a, JournalEntryInfo b) =>
              b.createdAt.compareTo(a.createdAt));
        return List<JournalEntryInfo>.unmodifiable(rows);
      });

  @override
  Future<JournalEntryInfo> addJournalEntry(
          {required String text, String? mood}) =>
      _io(() {
        _requireUnlocked();
        final JournalEntryInfo entry = JournalEntryInfo(
          id: _id('journal'),
          text: text,
          mood: (mood?.isEmpty ?? true) ? null : mood,
          createdAt: _clock,
        );
        _journal.add(entry);
        return entry;
      });

  @override
  Future<bool> deleteJournalEntry(String id) => _io(() {
        final int before = _journal.length;
        _journal.removeWhere((JournalEntryInfo e) => e.id == id);
        return _journal.length != before;
      });

  // ── Tara ─────────────────────────────────────────────────────────────────

  /// Distress cues the real Rust detector looks for. Kept crude on purpose:
  /// the fake only has to make the crisis path reachable in a demo/test, and
  /// the authority is `comrade_core`, never this list.
  static const List<String> _distressCues = <String>[
    'kill myself',
    'end it all',
    'suicide',
    "can't go on",
    'want to die',
    'hurt myself',
  ];

  @override
  Future<List<TaraMessageInfo>> taraThread() =>
      _io(() => List<TaraMessageInfo>.unmodifiable(_tara));

  @override
  Future<TaraMessageInfo> taraSend(String text) => _io(() {
        _requireUnlocked();
        final String lower = text.toLowerCase();
        final bool crisis = _distressCues.any(lower.contains);
        _tara.add(
          TaraMessageInfo(
            id: _id('tara'),
            text: text,
            fromTara: false,
            crisis: false,
            createdAt: _clock,
          ),
        );
        final TaraMessageInfo reply = TaraMessageInfo(
          id: _id('tara'),
          text: crisis
              ? "I'm glad you told me. I'm not a crisis service and I can't "
                  'carry this with you, but the people below can — please '
                  'reach one of them now.'
              : 'That sounds heavy, and it makes sense you would feel that '
                  'way. What do you think is underneath it?',
          fromTara: true,
          crisis: crisis,
          createdAt: _clock,
        );
        _tara.add(reply);
        return reply;
      });

  @override
  Future<String> taraOpener() async => "What's on your mind today?";

  @override
  Future<List<CrisisResourceInfo>> taraCrisisResources() async =>
      const <CrisisResourceInfo>[
        CrisisResourceInfo(
          name: 'Tele-MANAS',
          contact: '14416',
          note: 'Government of India · 24×7 · many languages',
        ),
        CrisisResourceInfo(
          name: 'KIRAN',
          contact: '1800-599-0019',
          note: 'Toll-free mental-health helpline · 24×7',
        ),
      ];

  @override
  Future<int> clearTaraThread() => _io(() {
        final int n = _tara.length;
        _tara.clear();
        return n;
      });

  // ── Calls ────────────────────────────────────────────────────────────────

  @override
  Future<List<CallRecordInfo>> callHistory({String? peer}) => _io(() {
        _requireUnlocked();
        final List<CallRecordInfo> rows = <CallRecordInfo>[
          for (final CallRecordInfo r in _calls)
            if (peer == null || r.peer == peer) r,
        ]..sort((CallRecordInfo a, CallRecordInfo b) =>
            b.startedAt.compareTo(a.startedAt));
        return List<CallRecordInfo>.unmodifiable(rows);
      });

  @override
  Future<CallSessionInfo> placeCall(
          {required String peer, required String media}) =>
      _io(() {
        _requireUnlocked();
        return CallSessionInfo(
          callId: _id('call'),
          peer: peer,
          media: media,
          iceServers: const <IceServerInfo>[
            IceServerInfo(urls: <String>['stun:stun.l.google.com:19302']),
          ],
        );
      });

  @override
  Future<void> sendCallSignal({
    required String peer,
    required String callId,
    required String media,
    required String signalJson,
  }) =>
      _io(() {});

  @override
  Future<void> hangupCall({
    required String peer,
    required String callId,
    required String media,
    required String reason,
  }) =>
      _io(() {});

  @override
  Future<CallRecordInfo> logCall({
    required String peer,
    required String callId,
    required String media,
    required bool incoming,
    required String outcome,
    required int startedAt,
    required int durationSecs,
  }) =>
      _io(() {
        final CallRecordInfo rec = CallRecordInfo(
          id: callId,
          peer: peer,
          media: media,
          incoming: incoming,
          outcome: outcome,
          startedAt: startedAt,
          durationSecs: durationSecs,
        );
        _calls.add(rec);
        return rec;
      });

  @override
  Future<List<IceServerInfo>> callIceServers({String strategy = 'stun_only'}) =>
      _io(() => const <IceServerInfo>[
            IceServerInfo(urls: <String>['stun:stun.l.google.com:19302']),
          ]);

  @override
  Future<List<String>?> callSas(
          {required String localSdp, required String remoteSdp}) =>
      _io(() {
        if (localSdp.isEmpty || remoteSdp.isEmpty) return null;
        return const <String>['🐶', '🦊', '🐝', '🐳'];
      });

  // ── Settings ─────────────────────────────────────────────────────────────

  @override
  Future<TurnServerStatus> turnServerStatus() => _io(() => _turn);

  @override
  Future<void> setTurnServer({
    required String url,
    required String username,
    required String credential,
  }) =>
      _io(() {
        final String trimmed = url.trim();
        if (trimmed.isEmpty) {
          _turn = const TurnServerStatus.none();
          return;
        }
        if (!trimmed.startsWith('turn:') && !trimmed.startsWith('turns:')) {
          throw const ComradeException(
            'A relay URL must start with turn: or turns:.',
          );
        }
        // Note what is NOT stored: the credential is accepted and dropped,
        // exactly like the core, which never reads it back out.
        _turn = TurnServerStatus(configured: true, url: trimmed);
      });

  @override
  Future<TurnDiagnostic> testTurnConnectivity() => _io(() => _turn.configured
      ? TurnDiagnostic.relayAvailable
      : TurnDiagnostic.noServerConfigured);

  // ── Workspace / mesh / couple ────────────────────────────────────────────

  @override
  Future<MeshStatus> meshStatus() => _io(() => _mesh);

  @override
  Future<WorkspaceInfo> currentWorkspace() => _io(() => _workspace);

  @override
  Future<WorkspaceInfo> toggleWorkspace(String target) => _io(() {
        _workspace = WorkspaceInfo(
          key: target,
          label: target,
          relayConnected: target != 'OffGridTravel',
          meshActive: target == 'OffGridTravel',
          coupleSandbox: target.startsWith('CoupleSandbox'),
        );
        _mesh = MeshStatus(active: _workspace.meshActive, peerCount: 0);
        emit(MeshStatusChanged(_mesh));
        return _workspace;
      });

  @override
  Future<SakhaStatus> sakhaStatus() => _io(() => _sakha);

  @override
  Future<SakhaStatus> pairSakha(
          {required String partnerNpub, required String role}) =>
      _io(() {
        if (!RegExp(r'^npub1[0-9a-z]+$', caseSensitive: false)
            .hasMatch(partnerNpub)) {
          throw const ComradeException("Enter your partner's npub public key.");
        }
        _sakha = SakhaStatus(
          paired: true,
          partnerNpub: partnerNpub,
          role: role == 'sakhi' ? 'sakhi' : 'sakha',
        );
        return _sakha;
      });

  @override
  Future<String> sakhaAddEntry({
    required String description,
    required double amountInr,
    required String paidBy,
  }) =>
      _io(() {
        if (!_sakha.paired) {
          throw const ComradeException('Not paired with a partner yet.');
        }
        final String line =
            '$description | ₹${amountInr.toStringAsFixed(2)} | paid by $paidBy';
        _ledger = _ledger.isEmpty ? line : '$_ledger\n$line';
        return _ledger;
      });

  @override
  Future<String> sakhaReadLedger() => _io(() => _ledger);

  @override
  Future<String> syncLedger() => _io(() {
        if (!_sakha.paired) {
          throw const ComradeException(
            'No shared secret available — pairing handshake incomplete.',
          );
        }
        return 'sync-$_clock';
      });

  // ── Events ───────────────────────────────────────────────────────────────

  @override
  Stream<BridgeEvent> get events => _events.stream;

  @override
  Future<void> dispose() async {
    await _events.close();
  }
}
