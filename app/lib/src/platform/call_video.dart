/// Call video: the Dart half of the answer to "an `org.webrtc.VideoTrack`
/// cannot cross a `MethodChannel`".
///
/// `CallManager` keeps the `PeerConnection` and keeps the track. The native
/// [Channels.callVideo] handler sinks that track into an EGL surface backed by
/// a `SurfaceTexture` and hands Dart an integer texture id; [CallVideoView]
/// composites it with a plain `Texture` widget. The track never leaves Kotlin.
///
/// This is option (b) from
/// `../../../android/app/src/main/kotlin/mullu/comrade/PLATFORM_CHANNELS.md` §6 —
/// keep `CallManager` native rather than rewriting it onto `flutter_webrtc`.
/// §6.2 has the reasoning; the short version is that under option (a) the
/// `PeerConnection` would live in the Dart isolate, and an incoming call
/// arriving with the engine detached would have nowhere to go.
///
/// The API shape here is deliberately the same one `flutter_webrtc` exposes
/// (`RTCVideoRenderer` + `RTCVideoView`), so a future switch to option (a) —
/// which becomes the right answer if iOS enters the roadmap — is a swap of this
/// file, not of every call widget.
library;

import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'channels.dart';

/// Which of `CallManager`'s two video flows a renderer follows.
enum CallVideoSource {
  /// The local camera preview (video calls only).
  local,

  /// The peer's camera, once their video arrives.
  remote,
}

const MethodChannel _methods = MethodChannel(Channels.callVideo);
const EventChannel _events = EventChannel(Channels.callVideoEvents);

Stream<Map<Object?, Object?>>? _geometry;

/// One shared subscription to the frame-geometry stream, fanned out by
/// `rendererId`. Every renderer filtering its own events off one broadcast
/// stream is cheaper than N `EventChannel` subscriptions, and the native side
/// only maintains one sink.
Stream<Map<Object?, Object?>> get _geometryStream => _geometry ??= _events
    .receiveBroadcastStream()
    .map((e) => e as Map<Object?, Object?>)
    .asBroadcastStream();

/// A native video renderer, addressed by texture id.
///
/// Lifecycle mirrors `flutter_webrtc`'s `RTCVideoRenderer`: construct, await
/// [initialize], hand to a [CallVideoView], and [dispose] when the widget goes
/// away. Notifies listeners when the texture id or the frame geometry changes.
///
/// **Renderers die with the Flutter engine, and that is correct.** The
/// `SurfaceTexture` belongs to the engine's texture registry, so a detach
/// releases it — while the call itself (audio, signalling, the foreground
/// service, the ongoing notification) is completely untouched, because none of
/// those are this object's. There is no visible surface to draw to while
/// detached. On reattach, create a new renderer.
class CallVideoRenderer extends ChangeNotifier {
  CallVideoRenderer({required this.source, bool mirror = false})
      : _mirror = mirror;

  final CallVideoSource source;

  int? _rendererId;
  int? _textureId;
  bool _mirror;
  int _width = 0;
  int _height = 0;
  int _rotation = 0;
  StreamSubscription<Map<Object?, Object?>>? _sub;

  /// The Flutter texture id, or null before [initialize] / after [dispose].
  int? get textureId => _textureId;

  bool get mirror => _mirror;

  /// On-screen frame size of the last frame, already rotation-corrected — a
  /// 1280×720 frame at rotation 90 reports 720×1280.
  ///
  /// Zero until the first frame arrives, which is the honest "nothing to show
  /// yet" state; [CallVideoView] renders black for it, matching what the
  /// Compose renderer did while the shared EGL context was still null.
  int get frameWidth => _width;

  int get frameHeight => _height;

  double? get aspectRatio =>
      (_width > 0 && _height > 0) ? _width / _height : null;

  int get frameRotation => _rotation;

  /// Allocate the native renderer and its texture.
  ///
  /// Returns as soon as the texture exists. EGL initialisation is deferred
  /// natively until the first non-null track arrives, because
  /// `CallManager.eglBaseContext` is null until the WebRTC factory has been
  /// built during call setup — so calling this from a widget's `initState`,
  /// before the call connects, is fine and expected.
  Future<void> initialize() async {
    if (_rendererId != null) return;
    final result = await _methods.invokeMapMethod<String, Object?>(
      'createRenderer',
      {'source': source.name, 'mirror': _mirror},
    );
    if (result == null) return;
    _rendererId = (result['rendererId'] as num).toInt();
    _textureId = (result['textureId'] as num).toInt();
    _sub = _geometryStream
        .where((e) => (e['rendererId'] as num?)?.toInt() == _rendererId)
        .listen(_onGeometry);
    notifyListeners();
  }

  void _onGeometry(Map<Object?, Object?> event) {
    _width = (event['width'] as num?)?.toInt() ?? 0;
    _height = (event['height'] as num?)?.toInt() ?? 0;
    _rotation = (event['rotation'] as num?)?.toInt() ?? 0;
    notifyListeners();
  }

  /// Flip horizontally — the front camera's self-preview reads correctly
  /// mirrored. Applied live, because which tile shows the front camera can
  /// change mid-call.
  Future<void> setMirror(bool value) async {
    _mirror = value;
    final id = _rendererId;
    if (id != null) {
      await _methods
          .invokeMethod<void>('setMirror', {'rendererId': id, 'mirror': value});
    }
    notifyListeners();
  }

  @override
  Future<void> dispose() async {
    super.dispose();
    await _sub?.cancel();
    _sub = null;
    final id = _rendererId;
    _rendererId = null;
    _textureId = null;
    if (id != null) {
      // Disposing an already-released renderer is a no-op natively, not an
      // error: a widget dispose can race the engine detach that released
      // everything already.
      await _methods.invokeMethod<void>('disposeRenderer', {'rendererId': id});
    }
  }
}

/// How a video frame fills its box.
enum CallVideoFit {
  /// Fill the box, cropping overflow — what a full-screen call wants, and what
  /// the Compose renderer used (`SCALE_ASPECT_FILL`).
  cover,

  /// Fit inside the box, letterboxing — for a thumbnail where cropping a face
  /// out of frame would be worse than black bars.
  contain,
}

/// Renders a [CallVideoRenderer]'s texture.
///
/// Black until the first frame, which is the same thing the Compose version did
/// while the shared EGL context was null — an honest "no picture yet" rather
/// than a spinner over a stream that may never start.
class CallVideoView extends StatelessWidget {
  const CallVideoView(
    this.renderer, {
    super.key,
    this.fit = CallVideoFit.cover,
    this.placeholderColor = const Color(0xFF000000),
  });

  final CallVideoRenderer renderer;
  final CallVideoFit fit;
  final Color placeholderColor;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: renderer,
      builder: (context, _) {
        final textureId = renderer.textureId;
        final width = renderer.frameWidth;
        final height = renderer.frameHeight;
        if (textureId == null || width <= 0 || height <= 0) {
          return ColoredBox(
              color: placeholderColor, child: const SizedBox.expand());
        }
        // Mirroring is a widget transform rather than a renderer flag on this
        // path — the native renderer honours setMirror too, but doing it here
        // means no round trip when the UI swaps which tile shows the front
        // camera. Both are wired; prefer this one for pure-UI flips.
        Widget video = Texture(textureId: textureId);
        if (renderer.mirror) {
          video = Transform(
            alignment: Alignment.center,
            // diagonal3Values rather than Matrix4.scale: the latter is
            // deprecated in current vector_math and this is exactly a
            // horizontal flip, nothing more.
            transform: Matrix4.diagonal3Values(-1, 1, 1),
            child: video,
          );
        }
        // Size the texture at its true rotated pixel dimensions, then let
        // FittedBox scale it — cover for a full-screen call (crop rather than
        // letterbox, matching SCALE_ASPECT_FILL), contain for a thumbnail where
        // cropping a face out of frame would be worse than black bars.
        return ClipRect(
          child: ColoredBox(
            color: placeholderColor,
            child: FittedBox(
              fit: fit == CallVideoFit.cover ? BoxFit.cover : BoxFit.contain,
              clipBehavior: Clip.hardEdge,
              child: SizedBox(
                width: width.toDouble(),
                height: height.toDouble(),
                child: video,
              ),
            ),
          ),
        );
      },
    );
  }
}

/// The `SurfaceViewRenderer` fallback, for a device where the texture path
/// misbehaves.
///
/// Registered natively (`CallVideoPlatformView.kt`) but **not** the default —
/// see `PLATFORM_CHANNELS.md` §6.3 for why `Texture` wins: no hybrid-composition
/// raster penalty, transforms and z-order that work like any other widget, and
/// no `SurfaceView`-versus-Flutter-surface conflict. Kept so a bad device is a
/// one-line switch rather than a feature gap. Neither path has been run on
/// hardware (§10).
class CallVideoSurfaceView extends StatelessWidget {
  const CallVideoSurfaceView({
    super.key,
    required this.source,
    this.mirror = false,
    this.overlay = false,
  });

  final CallVideoSource source;
  final bool mirror;

  /// Stacks this surface above the other one. Two `SurfaceView`s have undefined
  /// z-order, so a picture-in-picture tile over a full-screen renderer must set
  /// this — the exact wart the `Texture` path removes.
  final bool overlay;

  @override
  Widget build(BuildContext context) => AndroidView(
        viewType: Channels.callVideoSurfaceView,
        creationParams: {
          'source': source.name,
          'mirror': mirror,
          'overlay': overlay,
        },
        creationParamsCodec: const StandardMessageCodec(),
      );
}
