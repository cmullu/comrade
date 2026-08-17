/// The translation layer in `rust_comrade_repository.dart`, tested without a
/// native library.
///
/// The generated DTOs are plain Dart classes — constructing one does not touch
/// the bridge — so every mapping decision can be pinned here and will run on
/// any machine, including one with no `libcomrade_jni` built. The calls
/// themselves are covered by `rust_bridge_roundtrip_test.dart`, which needs
/// the .so and skips without it; between the two, nothing about the bridge is
/// only ever checked by eye.
library;

import 'dart:convert';

import 'package:comrade/src/data/comrade_repository.dart';
import 'package:comrade/src/data/models.dart';
import 'package:comrade/src/data/rust_comrade_repository.dart';
import 'package:comrade/src/rust/api.dart' as rust;
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('describeUiError', () {
    test('passes the core\'s own message through verbatim', () {
      // The core writes these to be shown to a user (the TURN validator
      // describes only the URL's shape, never the credential), so rewording
      // them here would only lose information.
      expect(
        describeUiError(const rust.UiError.transition('Base → Base')),
        'Base → Base',
      );
      expect(
        describeUiError(const rust.UiError.storage('disk full')),
        'disk full',
      );
      expect(
        describeUiError(const rust.UiError.engine('relay refused')),
        'relay refused',
      );
    });

    test('gives the payload-free variants a sentence', () {
      expect(
        describeUiError(const rust.UiError.vaultLocked()),
        'The vault is locked.',
      );
      expect(
        describeUiError(const rust.UiError.storeLocked()),
        'The vault is locked.',
      );
      expect(
        describeUiError(const rust.UiError.noIdentity()),
        contains('unlock the vault'),
      );
    });

    test('names the workspace it did not recognise', () {
      expect(
        describeUiError(const rust.UiError.unknownWorkspace('Atlantis')),
        contains('Atlantis'),
      );
    });

    test('says the build cannot search, not that nothing was found', () {
      // The two catalogue variants must not collapse into one sentence. An
      // empty result means the recording is not in the catalogue; this means
      // the build has no catalogue, and rendering it as "not found" tells
      // somebody their song does not exist because a Cargo feature is off.
      final unavailable =
          describeUiError(const rust.UiError.catalogueUnavailable());
      expect(unavailable, contains('cannot search'));
      expect(unavailable, isNot(contains('found')));
      expect(
        describeUiError(const rust.UiError.catalogue('musicbrainz timed out')),
        'musicbrainz timed out',
      );
      expect(
        describeUiError(const rust.UiError.download('archive.example refused')),
        'archive.example refused',
      );
    });

    test('says the build cannot look places up, not that nowhere is nearby',
        () {
      // The Travel pair splits the same way the catalogue pair does: a guide
      // with nothing in it is a claim about the neighbourhood, and this is a
      // claim about the build.
      final unavailable =
          describeUiError(const rust.UiError.travelUnavailable());
      expect(unavailable, contains('cannot look up places'));
      expect(unavailable, isNot(contains('nothing')));
      expect(
        describeUiError(const rust.UiError.travel('overpass timed out')),
        'overpass timed out',
      );
    });
  });

  group('parseCallMediaKind', () {
    test('accepts the wire strings, either case', () {
      expect(parseCallMediaKind('audio'), rust.CallMediaKind.audio);
      expect(parseCallMediaKind('VIDEO'), rust.CallMediaKind.video);
      expect(parseCallMediaKind(' video '), rust.CallMediaKind.video);
    });

    test('rejects anything else rather than guessing', () {
      // Unlike the core's `from_str_lenient`, which defaults to audio. A typo
      // that silently downgrades a video call is a privacy surprise.
      expect(
          () => parseCallMediaKind('vidoe'), throwsA(isA<ComradeException>()));
      expect(() => parseCallMediaKind(''), throwsA(isA<ComradeException>()));
    });
  });

  group('parseHangupReason', () {
    test('matches the core\'s from_str_lenient, spelling variants included',
        () {
      expect(parseHangupReason('normal'), rust.HangupReason.normal);
      expect(parseHangupReason('Declined'), rust.HangupReason.declined);
      expect(parseHangupReason('cancelled'), rust.HangupReason.cancelled);
      expect(parseHangupReason('canceled'), rust.HangupReason.cancelled);
    });

    test('falls back to unknown, which is what that variant is for', () {
      expect(parseHangupReason('exploded'), rust.HangupReason.unknown);
    });
  });

  group('parseIceStrategy', () {
    test('only an explicit stun_and_turn widens the server list', () {
      expect(parseIceStrategy('stun_and_turn'), rust.IceStrategy.stunAndTurn);
      expect(parseIceStrategy('stun_only'), rust.IceStrategy.stunOnly);
      // The bias is deliberate: a malformed value must never route media
      // through a TURN relay by accident.
      expect(parseIceStrategy('turn'), rust.IceStrategy.stunOnly);
      expect(parseIceStrategy(''), rust.IceStrategy.stunOnly);
    });
  });

  group('parseCallSignal', () {
    test('reads the internally-tagged shape the core serialises', () {
      expect(
        parseCallSignal('{"kind":"offer","sdp":"v=0 offer"}'),
        const rust.CallSignal.offer(sdp: 'v=0 offer'),
      );
      expect(
        parseCallSignal('{"kind":"answer","sdp":"v=0 answer"}'),
        const rust.CallSignal.answer(sdp: 'v=0 answer'),
      );
      expect(parseCallSignal('{"kind":"ringing"}'),
          const rust.CallSignal.ringing());
      expect(parseCallSignal('{"kind":"busy"}'), const rust.CallSignal.busy());
    });

    test('carries every optional ICE field', () {
      final rust.CallSignal signal = parseCallSignal(jsonEncode(
        <String, Object?>{
          'kind': 'ice',
          'candidate': 'candidate:1 1 udp',
          'sdp_mid': '0',
          'sdp_m_line_index': 3,
        },
      ));
      expect(
        signal,
        const rust.CallSignal.ice(
          candidate: 'candidate:1 1 udp',
          sdpMid: '0',
          sdpMLineIndex: 3,
        ),
      );
    });

    test('an ICE candidate with no mid or index is still valid', () {
      expect(
        parseCallSignal('{"kind":"ice","candidate":"c"}'),
        const rust.CallSignal.ice(candidate: 'c'),
      );
    });

    test('hangup defaults to normal, matching serde(default)', () {
      expect(
        parseCallSignal('{"kind":"hangup"}'),
        const rust.CallSignal.hangup(reason: rust.HangupReason.normal),
      );
      expect(
        parseCallSignal('{"kind":"hangup","reason":"declined"}'),
        const rust.CallSignal.hangup(reason: rust.HangupReason.declined),
      );
    });

    test('malformed input throws a showable error, never a raw FormatException',
        () {
      expect(() => parseCallSignal('[]'), throwsA(isA<ComradeException>()));
      expect(() => parseCallSignal('{"kind":"teleport"}'),
          throwsA(isA<ComradeException>()));
      expect(() => parseCallSignal('{"kind":"offer"}'),
          throwsA(isA<ComradeException>()));
    });
  });

  group('mapBridgeEvent', () {
    test('an incoming DM becomes an inbound MessageInfo', () {
      final BridgeEvent? mapped = mapBridgeEvent(
        rust.BridgeEvent.incomingDirectMessage(rust.DirectMessageDto(
          id: 'e1',
          sender: 'npub1sender',
          content: 'hello',
          createdAt: BigInt.from(1730000000),
          upiIntents: const <rust.UpiIntentDto>[],
        )),
      );

      final IncomingDirectMessage event = mapped! as IncomingDirectMessage;
      expect(event.message.peer, 'npub1sender');
      expect(event.message.outgoing, isFalse);
      // BigInt narrowed at the boundary — widgets never see a BigInt.
      expect(event.message.createdAt, 1730000000);
    });

    test('mesh status narrows the peer count', () {
      final BridgeEvent? mapped = mapBridgeEvent(
        rust.BridgeEvent.meshStatusChanged(
          rust.MeshStatusDto(active: true, peerCount: BigInt.from(4)),
        ),
      );
      final MeshStatusChanged event = mapped! as MeshStatusChanged;
      expect(event.status.active, isTrue);
      expect(event.status.peerCount, 4);
    });

    test('a comrade presence edge narrows its beacon time', () {
      final BridgeEvent? mapped = mapBridgeEvent(
        rust.BridgeEvent.comradePresence(
          peer: 'npub1peer',
          name: 'amma',
          online: true,
          at: BigInt.from(1730000002),
        ),
      );
      final ComradePresenceChanged event = mapped! as ComradePresenceChanged;
      expect(event.peer, 'npub1peer');
      expect(event.name, 'amma');
      expect(event.online, isTrue);
      // BigInt narrowed at the boundary, like every other timestamp.
      expect(event.at, 1730000002);
    });

    test('an offline edge with no cached name is still delivered', () {
      // The name is a convenience for raising a notification without a store
      // round-trip; its absence must not drop the transition.
      final BridgeEvent? mapped = mapBridgeEvent(
        rust.BridgeEvent.comradePresence(
          peer: 'npub1peer',
          online: false,
          at: BigInt.from(1730000003),
        ),
      );
      final ComradePresenceChanged event = mapped! as ComradePresenceChanged;
      expect(event.name, isNull);
      expect(event.online, isFalse);
    });

    test('a nudge crosses as itself, carrying nothing about the draft', () {
      final BridgeEvent? mapped = mapBridgeEvent(
        const rust.BridgeEvent.comradeNudge(peer: 'npub1peer', name: 'amma'),
      );
      final IncomingComradeNudge event = mapped! as IncomingComradeNudge;
      expect(event.peer, 'npub1peer');
      expect(event.name, 'amma');
      // Not presence: nothing here can move a dot or advance a "last seen".
      expect(mapped, isNot(isA<ComradePresenceChanged>()));
    });

    test('a nudge with no cached name is still delivered', () {
      final BridgeEvent? mapped = mapBridgeEvent(
        const rust.BridgeEvent.comradeNudge(peer: 'npub1peer'),
      );
      expect((mapped! as IncomingComradeNudge).name, isNull);
    });

    test('a receipt is parsed into the ranked MessageStatus', () {
      final BridgeEvent? mapped = mapBridgeEvent(
        const rust.BridgeEvent.messageStatus(
          peer: 'npub1peer',
          messageIds: <String>['a', 'b'],
          status: 'read',
        ),
      );
      final MessageStatusChanged event = mapped! as MessageStatusChanged;
      expect(event.status, MessageStatus.read);
      expect(event.messageIds, <String>['a', 'b']);
    });

    test('an unknown receipt state is dropped, not crashed on', () {
      // Forward compatibility: a newer core adding a fourth state must not
      // take down an older UI, and there is no rank to place it at.
      expect(
        mapBridgeEvent(
          const rust.BridgeEvent.messageStatus(
            peer: 'npub1peer',
            messageIds: <String>['a'],
            status: 'teleported',
          ),
        ),
        isNull,
      );
    });

    test('a typed call signal is flattened for the call UI', () {
      final BridgeEvent? mapped = mapBridgeEvent(
        rust.BridgeEvent.incomingCallSignal(rust.CallSignalDto(
          callId: 'call-1',
          peer: 'npub1peer',
          media: 'video',
          signal: const rust.CallSignal.offer(sdp: 'v=0'),
          createdAt: BigInt.from(1730000001),
        )),
      );

      final IncomingCallSignal event = mapped! as IncomingCallSignal;
      expect(event.kind, 'offer');
      expect(event.sdp, 'v=0');
      expect(event.candidate, isNull);
      expect(event.reason, isNull);
    });

    test('a hangup carries its reason, an ice signal its candidate', () {
      final IncomingCallSignal hangup = mapBridgeEvent(
        rust.BridgeEvent.incomingCallSignal(rust.CallSignalDto(
          callId: 'call-1',
          peer: 'npub1peer',
          media: 'audio',
          signal:
              const rust.CallSignal.hangup(reason: rust.HangupReason.declined),
          createdAt: BigInt.zero,
        )),
      )! as IncomingCallSignal;
      expect(hangup.kind, 'hangup');
      expect(hangup.reason, 'declined');

      final IncomingCallSignal ice = mapBridgeEvent(
        rust.BridgeEvent.incomingCallSignal(rust.CallSignalDto(
          callId: 'call-1',
          peer: 'npub1peer',
          media: 'audio',
          signal: const rust.CallSignal.ice(candidate: 'candidate:1'),
          createdAt: BigInt.zero,
        )),
      )! as IncomingCallSignal;
      expect(ice.kind, 'ice');
      expect(ice.candidate, 'candidate:1');
      expect(ice.sdp, isNull);
    });

    test('every BridgeEvent variant maps to something', () {
      // A new Rust variant makes the generated switch non-exhaustive and the
      // analyzer catches it; this catches the subtler case of a variant that
      // compiles but silently returns null.
      final List<rust.BridgeEvent> all = <rust.BridgeEvent>[
        rust.BridgeEvent.incomingChitthi(rust.ChitthiDto(
          id: 'c',
          author: 'a',
          content: 'x',
          createdAt: BigInt.zero,
        )),
        rust.BridgeEvent.incomingDirectMessage(rust.DirectMessageDto(
          id: 'd',
          sender: 's',
          content: 'x',
          createdAt: BigInt.zero,
          upiIntents: const <rust.UpiIntentDto>[],
        )),
        rust.BridgeEvent.incomingMedia(rust.MediaMessageDto(
          eventId: 'm',
          url: 'https://example.invalid/blob',
          mimeType: 'image/png',
          caption: '',
          sender: 's',
          createdAt: BigInt.zero,
          size: BigInt.from(12),
          outgoing: false,
        )),
        rust.BridgeEvent.incomingCallSignal(rust.CallSignalDto(
          callId: 'call',
          peer: 'p',
          media: 'audio',
          signal: const rust.CallSignal.ringing(),
          createdAt: BigInt.zero,
        )),
        rust.BridgeEvent.incomingMessageRequest(rust.MessageRequestDto(
          peer: 'p',
          lastMessage: 'hi',
          lastAt: BigInt.zero,
        )),
        const rust.BridgeEvent.messageStatus(
          peer: 'p',
          messageIds: <String>['a'],
          status: 'sent',
        ),
        const rust.BridgeEvent.peerProfileUpdated(peer: 'p', name: 'n'),
        rust.BridgeEvent.comradePresence(
          peer: 'p',
          name: 'n',
          online: true,
          at: BigInt.zero,
        ),
        rust.BridgeEvent.meshStatusChanged(
          rust.MeshStatusDto(active: false, peerCount: BigInt.zero),
        ),
        const rust.BridgeEvent.ledgerUpdated(ledger: '# ledger'),
      ];

      for (final rust.BridgeEvent event in all) {
        expect(mapBridgeEvent(event), isNotNull,
            reason: '$event mapped to null');
      }
    });
  });
}
