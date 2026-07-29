/// The full-screen call overlay: ringing, connecting, active, ended.
///
/// Port of `call/CallScreen.kt`, with the desktop overlay's SAS copy. It
/// observes [callProvider] and renders every phase; the media itself comes
/// from whatever [CallEngine] is installed (see `state/call_providers.dart`
/// for why that is a seam and not an implementation).
///
/// Details kept because they were deliberate:
///  * one composition subtree for Connecting **and** Active, so the video
///    surfaces are not torn down and recreated exactly as the first frames
///    arrive;
///  * tap the picture-in-picture tile to swap it with the full-screen view;
///  * the local track mirrors wherever it renders, the remote never does;
///  * the SAS row appears only while Active and only with a real derived
///    code — "can't verify" renders as nothing, never as a fabricated code;
///  * the connection-quality dot appears only for MEDIUM/POOR.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../state/call_providers.dart';
import '../theme/comrade_theme.dart';
import '../util/display_name.dart';
import '../widgets/peer_avatar.dart';

/// Shows the call overlay when a call is in flight, nothing when idle.
/// Callers place this last in their layout stack so it covers the app.
class CallOverlay extends ConsumerWidget {
  const CallOverlay({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final CallSession session = ref.watch(callProvider);
    final CallUiState state = session.state;
    if (state is CallIdle) return const SizedBox.shrink();

    return Positioned.fill(
      child: Material(
        color: CallPalette.background,
        child: switch (state) {
          CallRinging() => _RingingContent(state: state),
          // One subtree for both in-call phases.
          CallConnecting() || CallActive() => _InCallContent(session: session),
          CallEnded() => _EndedContent(state: state),
          CallIdle() => const SizedBox.shrink(),
        },
      ),
    );
  }
}

class _RingingContent extends ConsumerWidget {
  const _RingingContent({required this.state});

  final CallRinging state;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final String status = state.isIncoming
        ? (state.isVideo ? 'Incoming video call' : 'Incoming voice call')
        : (state.remoteRinging ? 'Ringing…' : 'Calling…');
    final CallController controller = ref.read(callProvider.notifier);
    return Column(
      children: <Widget>[
        Expanded(
          child: Padding(
            padding: const EdgeInsets.only(top: 72),
            child: _PeerHeader(
              peer: state.peerNpub,
              label: state.label,
              status: status,
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 44, vertical: 44),
          child: Row(
            mainAxisAlignment: state.isIncoming
                ? MainAxisAlignment.spaceBetween
                : MainAxisAlignment.center,
            children: <Widget>[
              if (state.isIncoming) ...<Widget>[
                CallActionButton(
                  icon: Icons.call_end,
                  label: 'Decline',
                  background: CallPalette.hangup,
                  size: 68,
                  onPressed: controller.reject,
                ),
                CallActionButton(
                  icon: state.isVideo ? Icons.videocam : Icons.call,
                  label: 'Accept',
                  background: CallPalette.accept,
                  size: 68,
                  onPressed: controller.accept,
                ),
              ] else
                CallActionButton(
                  icon: Icons.call_end,
                  label: 'Cancel',
                  background: CallPalette.hangup,
                  size: 68,
                  onPressed: controller.hangup,
                ),
            ],
          ),
        ),
      ],
    );
  }
}

class _InCallContent extends ConsumerStatefulWidget {
  const _InCallContent({required this.session});

  final CallSession session;

  @override
  ConsumerState<_InCallContent> createState() => _InCallContentState();
}

class _InCallContentState extends ConsumerState<_InCallContent> {
  /// Tap the picture-in-picture tile to swap it with the full-screen view.
  /// Only changes which track renders where — the tracks are untouched.
  bool _swapped = false;

  @override
  Widget build(BuildContext context) {
    final CallSession session = widget.session;
    final CallUiState state = session.state;
    final bool connecting = state is CallConnecting;
    final CallController controller = ref.read(callProvider.notifier);
    final CallEngine engine = ref.watch(callEngineProvider);

    // Quality stats and the SAS are only meaningful once the call is Active.
    final bool showWeak = !connecting &&
        (session.quality == CallQuality.medium ||
            session.quality == CallQuality.poor);
    final List<String>? sas =
        (!connecting && (session.sasEmojis?.isNotEmpty ?? false))
            ? session.sasEmojis
            : null;

    return Stack(
      fit: StackFit.expand,
      children: <Widget>[
        if (state.video)
          _videoStage(context, session, engine, connecting, showWeak, sas)
        else
          Center(
            child: _PeerHeader(
              peer: state.peer ?? '',
              label: state.peerLabel,
              status: connecting
                  ? 'Connecting…'
                  : _CallTimer.labelFor(state as CallActive),
              extra: <Widget>[
                if (showWeak) ConnectionQualityBadge(quality: session.quality),
                if (sas != null) SasRow(emojis: sas),
              ],
              liveTimerFor: connecting ? null : state as CallActive,
            ),
          ),
        Align(
          alignment: Alignment.bottomCenter,
          child: DecoratedBox(
            decoration: state.video
                ? const BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: <Color>[Colors.transparent, Color(0xB3000000)],
                    ),
                  )
                : const BoxDecoration(),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(24, 28, 24, 36),
              child: Wrap(
                alignment: WrapAlignment.center,
                spacing: 26,
                runSpacing: 16,
                children: <Widget>[
                  CallActionButton(
                    icon: session.muted ? Icons.mic_off : Icons.mic,
                    label: session.muted ? 'Unmute' : 'Mute',
                    background: session.muted
                        ? CallPalette.controlActive
                        : CallPalette.controlIdle,
                    tint: session.muted ? CallPalette.background : Colors.white,
                    onPressed: controller.toggleMute,
                  ),
                  AudioRouteButton(session: session),
                  if (state.video)
                    CallActionButton(
                      icon: session.cameraOn
                          ? Icons.videocam
                          : Icons.videocam_off,
                      label: 'Camera',
                      background: session.cameraOn
                          ? CallPalette.controlIdle
                          : CallPalette.controlActive,
                      tint: session.cameraOn
                          ? Colors.white
                          : CallPalette.background,
                      onPressed: controller.toggleCamera,
                    ),
                  CallActionButton(
                    icon: Icons.call_end,
                    label: 'End',
                    background: CallPalette.hangup,
                    onPressed: controller.hangup,
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _videoStage(
    BuildContext context,
    CallSession session,
    CallEngine engine,
    bool connecting,
    bool showWeak,
    List<String>? sas,
  ) {
    final CallUiState state = session.state;
    final Widget? mainView =
        engine.videoView(local: _swapped, mirror: _swapped);
    final bool pipIsLocal = !_swapped;
    final Widget? pipView =
        engine.videoView(local: pipIsLocal, mirror: pipIsLocal);

    return Stack(
      fit: StackFit.expand,
      children: <Widget>[
        if (mainView != null)
          mainView
        else
          // No frames for the big view yet — show who the call is with rather
          // than a raw black screen.
          Center(
            child: _PeerHeader(peer: state.peer ?? '', label: state.peerLabel),
          ),
        Positioned(
          top: 16,
          right: 16,
          child: GestureDetector(
            onTap: () => setState(() => _swapped = !_swapped),
            child: Semantics(
              button: true,
              label: 'Swap video',
              child: Container(
                width: 110,
                height: 156,
                clipBehavior: Clip.antiAlias,
                decoration: BoxDecoration(
                  color: CallPalette.pipBackground,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Stack(
                  fit: StackFit.expand,
                  children: <Widget>[
                    if (pipView != null && (session.cameraOn || !pipIsLocal))
                      pipView
                    else
                      const Center(
                        child: Icon(
                          Icons.videocam_off,
                          size: 30,
                          color: Color(0x66FFFFFF),
                        ),
                      ),
                    if (pipIsLocal && session.cameraOn)
                      Align(
                        alignment: Alignment.bottomCenter,
                        child: Padding(
                          padding: const EdgeInsets.only(bottom: 6),
                          child: CallActionButton(
                            icon: Icons.flip_camera_ios,
                            label: null,
                            background: const Color(0x66000000),
                            size: 34,
                            onPressed:
                                ref.read(callProvider.notifier).switchCamera,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
        // Name + duration/status pill, kept clear of the self-preview tile.
        Positioned(
          left: 16,
          top: 20,
          right: 142,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Container(
                decoration: BoxDecoration(
                  color: const Color(0x66000000),
                  borderRadius: BorderRadius.circular(14),
                ),
                padding:
                    const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Text(
                      state.peerLabel,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Colors.white, fontSize: 15),
                    ),
                    if (connecting)
                      const Text(
                        'Connecting…',
                        style: TextStyle(
                          color: CallPalette.secondaryText,
                          fontSize: 13,
                          fontFamily: 'monospace',
                        ),
                      )
                    else
                      _CallTimer(active: state as CallActive),
                  ],
                ),
              ),
              if (showWeak) ...<Widget>[
                const SizedBox(height: 6),
                ConnectionQualityBadge(quality: session.quality),
              ],
              if (sas != null) ...<Widget>[
                const SizedBox(height: 6),
                SasRow(emojis: sas),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _EndedContent extends ConsumerWidget {
  const _EndedContent({required this.state});

  final CallEnded state;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final String label = switch (state.outcome) {
      'missed' => 'No answer',
      'failed' => "Couldn't connect",
      'declined' => 'Call declined',
      'busy' => 'Busy',
      'cancelled' => 'Call cancelled',
      _ => 'Call ended',
    };
    return GestureDetector(
      onTap: ref.read(callProvider.notifier).dismissEnded,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: <Widget>[
          PeerAvatar(title: state.label, seed: state.peerNpub, size: 96),
          const SizedBox(height: 20),
          Text(
            state.label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: Colors.white, fontSize: 22),
          ),
          const SizedBox(height: 8),
          Text(
            label,
            style:
                const TextStyle(color: CallPalette.secondaryText, fontSize: 15),
          ),
        ],
      ),
    );
  }
}

class _PeerHeader extends StatelessWidget {
  const _PeerHeader({
    required this.peer,
    required this.label,
    this.status,
    this.extra = const <Widget>[],
    this.liveTimerFor,
  });

  final String peer;
  final String label;
  final String? status;
  final List<Widget> extra;
  final CallActive? liveTimerFor;

  @override
  Widget build(BuildContext context) => Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: <Widget>[
          PeerAvatar(title: label, seed: peer, size: 112),
          const SizedBox(height: 24),
          Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: const TextStyle(color: Colors.white, fontSize: 26),
          ),
          if (liveTimerFor != null) ...<Widget>[
            const SizedBox(height: 10),
            _CallTimer(active: liveTimerFor!, fontSize: 16),
          ] else if (status != null) ...<Widget>[
            const SizedBox(height: 10),
            Text(
              status!,
              textAlign: TextAlign.center,
              style: const TextStyle(
                  color: CallPalette.secondaryText, fontSize: 16),
            ),
          ],
          for (final Widget widget in extra) ...<Widget>[
            const SizedBox(height: 6),
            widget,
          ],
        ],
      );
}

/// Live `m:ss` (or `h:mm:ss`) duration, ticking twice a second like the
/// Compose original.
class _CallTimer extends StatefulWidget {
  const _CallTimer({required this.active, this.fontSize = 13});

  final CallActive active;
  final double fontSize;

  static String labelFor(CallActive active) => formatCallClock(
        (DateTime.now().millisecondsSinceEpoch - active.connectedAtMs) ~/ 1000,
      );

  @override
  State<_CallTimer> createState() => _CallTimerState();
}

class _CallTimerState extends State<_CallTimer> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(
      const Duration(milliseconds: 500),
      (_) => setState(() {}),
    );
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Text(
        _CallTimer.labelFor(widget.active),
        style: TextStyle(
          color: CallPalette.secondaryText,
          fontSize: widget.fontSize,
          fontFamily: 'monospace',
        ),
      );
}

/// A small, unobtrusive dot + label shown only while the connection has
/// degraded: amber for MEDIUM, red for POOR.
class ConnectionQualityBadge extends StatelessWidget {
  const ConnectionQualityBadge({required this.quality, super.key});

  final CallQuality quality;

  @override
  Widget build(BuildContext context) => Row(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: quality == CallQuality.poor
                  ? CallPalette.hangup
                  : CallPalette.weakConnection,
            ),
          ),
          const SizedBox(width: 6),
          const Text(
            'Weak connection',
            style: TextStyle(color: Colors.white, fontSize: 13),
          ),
        ],
      );
}

/// The 4-emoji short authentication string.
///
/// This is a real security signal, not decoration: it is derived from both
/// sides' DTLS-SRTP certificate fingerprints, so the same 4 emoji appearing on
/// both devices is what rules out a man-in-the-middle on the call's media
/// path. Tapping the row explains that rather than leaving it unexplained.
class SasRow extends StatelessWidget {
  const SasRow({required this.emojis, super.key});

  final List<String> emojis;

  @override
  Widget build(BuildContext context) => InkWell(
        key: const Key('call-sas'),
        borderRadius: BorderRadius.circular(14),
        onTap: () => showDialog<void>(
          context: context,
          builder: (BuildContext context) => AlertDialog(
            title: const Text('Call verification code'),
            content: const Text(
              "These 4 symbols are derived from both devices' connection "
              'security codes. Read them aloud — if the other person sees the '
              'same 4, your call is not being intercepted. If they see '
              'something different, hang up.',
            ),
            actions: <Widget>[
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Got it'),
              ),
            ],
          ),
        ),
        child: Container(
          decoration: BoxDecoration(
            color: CallPalette.controlIdle,
            borderRadius: BorderRadius.circular(14),
          ),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              const Text(
                'Verify:',
                style:
                    TextStyle(color: CallPalette.secondaryText, fontSize: 13),
              ),
              const SizedBox(width: 8),
              Text(emojis.join(' '), style: const TextStyle(fontSize: 20)),
            ],
          ),
        ),
      );
}

/// The audio-output control: a button showing the current route that opens a
/// menu of every currently-present route.
class AudioRouteButton extends ConsumerWidget {
  const AudioRouteButton({required this.session, super.key});

  final CallSession session;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bool active = session.audioRoute != AudioRoute.earpiece;
    return PopupMenuButton<AudioRoute>(
      tooltip: 'Speaker: ${session.audioRoute.label}',
      onSelected: (AudioRoute route) =>
          ref.read(callProvider.notifier).setAudioRoute(route),
      itemBuilder: (BuildContext context) => <PopupMenuEntry<AudioRoute>>[
        for (final AudioRoute route in session.availableRoutes)
          PopupMenuItem<AudioRoute>(value: route, child: Text(route.label)),
      ],
      child: CallActionButton(
        icon: Icons.volume_up,
        label: session.audioRoute.label,
        background:
            active ? CallPalette.controlActive : CallPalette.controlIdle,
        tint: active ? CallPalette.background : Colors.white,
        onPressed: null,
      ),
    );
  }
}

/// A round call control with an optional label beneath — the uniform shape
/// every control on this screen uses.
class CallActionButton extends StatelessWidget {
  const CallActionButton({
    required this.icon,
    required this.label,
    required this.background,
    required this.onPressed,
    this.tint = Colors.white,
    this.size = 60,
    super.key,
  });

  final IconData icon;
  final String? label;
  final Color background;
  final Color tint;
  final double size;

  /// `null` when the surrounding widget owns the gesture (see
  /// [AudioRouteButton], whose popup wraps this).
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Semantics(
            button: true,
            label: label,
            child: InkWell(
              onTap: onPressed,
              customBorder: const CircleBorder(),
              child: Container(
                width: size,
                height: size,
                decoration:
                    BoxDecoration(shape: BoxShape.circle, color: background),
                alignment: Alignment.center,
                child: Icon(icon, color: tint, size: size * 0.44),
              ),
            ),
          ),
          if (label != null) ...<Widget>[
            const SizedBox(height: 6),
            Text(
              label!,
              maxLines: 1,
              style: const TextStyle(color: Color(0xB3FFFFFF), fontSize: 12),
            ),
          ],
        ],
      );
}
