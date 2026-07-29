/// The comrade-presence contract, exercised against [FakeComradeRepository].
///
/// Presence is the one part of the interface where "we do not know" and
/// "offline" are different answers that must not be collapsed, and where a
/// stale `true` is a privacy claim the core never made. Those rules live in
/// [ComradeRepository]'s doc comments; this pins them to behaviour so the fake
/// and the real bridge cannot drift apart silently.
library;

import 'package:comrade/src/data/comrade_repository.dart';
import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/data/models.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

void main() {
  const String stranger =
      'npub1stranger0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qk4h7p';

  test('only chosen comrades are listed, seeded state included', () async {
    final FakeComradeRepository repo = await unlockedFake();
    addTearDown(repo.dispose);

    final List<ComradeInfo> list = await repo.comrades();
    expect(list, hasLength(1),
        reason: 'the seed marks exactly one contact as a comrade');
    expect(list.single.alias, 'Amma');
    expect(list.single.online, isTrue);
    expect(list.single.peerMarkedUs, isTrue);
  });

  test('marking a peer never saved creates the contact', () async {
    final FakeComradeRepository repo = await unlockedFake();
    addTearDown(repo.dispose);

    final ContactInfo saved =
        await repo.setComrade(npub: stranger, comrade: true);
    expect(saved.comrade, isTrue);
    expect(
      (await repo.contacts()).map((ContactInfo c) => c.npub),
      contains(stranger),
    );
  });

  test('a new comrade is not assumed online, and says why', () async {
    final FakeComradeRepository repo = await unlockedFake();
    addTearDown(repo.dispose);

    await repo.setComrade(npub: stranger, comrade: true);
    final ComradeInfo added = (await repo.comrades())
        .firstWhere((ComradeInfo c) => c.npub == stranger);

    expect(added.online, isFalse);
    expect(added.lastSeenAt, 0);
    // The honest explanation for the grey dot: they have not chosen us back,
    // so no beacon will ever arrive, however long the UI waits.
    expect(added.peerMarkedUs, isFalse);
  });

  test('never having heard from a peer is null, not offline', () async {
    final FakeComradeRepository repo = await unlockedFake();
    addTearDown(repo.dispose);

    expect(await repo.peerPresence(stranger), isNull);
  });

  test('un-marking a comrade drops what we knew about them', () async {
    final FakeComradeRepository repo = await unlockedFake();
    addTearDown(repo.dispose);

    const String alice =
        'npub1alice7q0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qtqy3gq';
    expect((await repo.peerPresence(alice))?.online, isTrue);

    await repo.setComrade(npub: alice, comrade: false);

    expect(await repo.comrades(), isEmpty);
    expect(await repo.peerPresence(alice), isNull,
        reason: 'presence stops flowing the moment the choice is withdrawn');
  });

  test('a chat row shows presence only for a comrade', () async {
    final FakeComradeRepository repo = await unlockedFake();
    addTearDown(repo.dispose);

    const String alice =
        'npub1alice7q0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qtqy3gq';
    const String bhaskar =
        'npub1bob4laefqx0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qwx9m';

    List<ConversationInfo> rows = await repo.conversations();
    ConversationInfo row(String peer) =>
        rows.firstWhere((ConversationInfo c) => c.peer == peer);

    expect(row(alice).comrade, isTrue);
    expect(row(alice).online, isTrue);
    expect(row(bhaskar).comrade, isFalse);
    expect(row(bhaskar).online, isFalse);

    // …and a comrade who goes offline stops being shown as around.
    repo.seedPresence(alice, online: false);
    rows = await repo.conversations();
    expect(row(alice).comrade, isTrue);
    expect(row(alice).online, isFalse);
  });

  test('a presence transition reaches the event stream', () async {
    final FakeComradeRepository repo = await unlockedFake();
    addTearDown(repo.dispose);

    const String alice =
        'npub1alice7q0av9y8gm7vk2xspwjnvyxydr0hjfpnr4x9dvw2l3jd2qtqy3gq';
    final Future<BridgeEvent> next = repo.events.first;

    repo.seedPresence(alice, online: false, at: 1730000000);

    final ComradePresenceChanged event = await next as ComradePresenceChanged;
    expect(event.peer, alice);
    expect(event.name, 'alice');
    expect(event.online, isFalse);
    expect(event.at, 1730000000);
  });

  test('announcing presence is a courtesy, never a failure', () async {
    // Locked: a quiet zero rather than a throw, because the host calls this on
    // every foreground transition, including ones that race the vault opening.
    final FakeComradeRepository repo =
        FakeComradeRepository(latency: Duration.zero);
    addTearDown(repo.dispose);
    expect(await repo.announcePresence(true), 0);

    await repo.unlockVault(path: 'test-vault', passphrase: 'passphrase');
    expect(await repo.announcePresence(true), 1);
  });
}
