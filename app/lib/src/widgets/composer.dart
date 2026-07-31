/// The message composer — Telegram's layout, with this app's honesty rules.
///
/// ```text
///  ╭───────────────────────────────────────────────╮      ╭────╮
///  │ 🙂   Message…                             📎  │  ⧉   │ 🎤 │
///  ╰───────────────────────────────────────────────╯      ╰────╯
///     emoji        text field           attach     swap    mic → send
/// ```
///
/// * **Emoji on the left, inside the field.** Opens a dependency-free picker
///   (`emoji_picker.dart`) that inserts at the caret, not at the end.
/// * **Paper clip on the right, inside the field.** The document picker.
/// * **One round button outside, on the right.** It is *Send* the moment there
///   is text to send — which is what makes the layout work on a phone and a
///   desktop equally — and otherwise the current capture control.
/// * **A small swap control beside it**, present only when this device offers
///   more than one way to capture, showing the glyph of the mode it would move
///   *to*. Telegram hides that switch behind a press-and-hold on the mic; that
///   is undiscoverable with a mouse *and* with a finger, and worse, it shares a
///   target with a tap that starts recording — so it is a visible button here.
///
/// Everything is capability-gated from one startup probe. A platform with no
/// recorder and no camera gets a plain Send button rather than controls that
/// fail on tap; the same rule the settings screen states as "no fake switches".
/// That is also why the mic exists at all now: divergence D13 dropped voice
/// notes because press-and-hold has no meaning with a mouse — the *gesture* was
/// the problem, not the feature, so recording is tap-to-start / tap-to-send.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'emoji_picker.dart';
import 'media_attachment.dart';

/// What the round button does when there is no text to send.
///
/// Three, not two, because a device that can take a photo can usually also
/// record video, and a two-way mic/camera toggle leaves video unreachable.
enum ComposerCaptureMode { voice, photo, video }

class MessageComposer extends ConsumerStatefulWidget {
  const MessageComposer({
    required this.controller,
    required this.focusNode,
    required this.onSend,
    required this.onAttachment,
    this.sending = false,
    this.attaching = false,
    this.hintText = 'Message',
    super.key,
  });

  final TextEditingController controller;
  final FocusNode focusNode;

  /// Send the text currently in [controller].
  final Future<void> Function() onSend;

  /// Send one attachment — picked, captured, or recorded.
  final Future<void> Function(PickedAttachment attachment) onAttachment;

  /// A text send is in flight.
  final bool sending;

  /// An attachment send is in flight (upload + encrypt).
  final bool attaching;

  final String hintText;

  @override
  ConsumerState<MessageComposer> createState() => _MessageComposerState();
}

class _MessageComposerState extends ConsumerState<MessageComposer> {
  ComposerCaptureMode _mode = ComposerCaptureMode.voice;

  bool _recording = false;
  int _recordedSeconds = 0;
  Timer? _tick;

  /// True while a picker/camera/recorder round trip is open, so a second tap
  /// cannot start a second one.
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    // The round button swaps between Send and capture as the draft changes.
    widget.controller.addListener(_onDraftChanged);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onDraftChanged);
    _tick?.cancel();
    super.dispose();
  }

  void _onDraftChanged() {
    if (mounted) setState(() {});
  }

  bool get _hasText => widget.controller.text.trim().isNotEmpty;

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)
        ?.showSnackBar(SnackBar(content: Text(message)));
  }

  // ── Emoji ─────────────────────────────────────────────────────────────────

  Future<void> _openEmoji() async {
    // Keep the caret where it was: opening a sheet unfocuses the field, and
    // inserting into an unfocused field with a stale selection is how emoji end
    // up in the middle of a word.
    final TextSelection selection = widget.controller.selection;
    if (!selection.isValid) {
      widget.controller.selection = TextSelection.collapsed(
        offset: widget.controller.text.length,
      );
    }
    await showEmojiPicker(
      context,
      onPick: (String emoji) => insertEmoji(widget.controller, emoji),
    );
    if (mounted) widget.focusNode.requestFocus();
  }

  // ── Attach / capture ──────────────────────────────────────────────────────

  Future<void> _guarded(Future<PickedAttachment?> Function() source) async {
    if (_busy || widget.attaching) return;
    setState(() => _busy = true);
    try {
      final PickedAttachment? picked = await source();
      if (picked != null) await widget.onAttachment(picked);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _attach() => _guarded(() async {
        final AttachmentPicker picker = ref.read(attachmentPickerProvider);
        final PickedAttachment? picked = await picker.pick();
        if (picked == null && mounted) {
          // Cancelled and unsupported look identical from here; the picker's own
          // platform error would have thrown. Say the neutral thing.
          _maybeSayNoPicker();
        }
        return picked;
      });

  void _maybeSayNoPicker() {
    if (ref.read(attachmentPickerProvider) is NoAttachmentPicker) {
      _snack('No file picker is wired up on this platform yet.');
    }
  }

  Future<void> _capturePhoto() =>
      _guarded(() => ref.read(mediaCaptureProvider).capturePhoto());

  Future<void> _captureVideo() =>
      _guarded(() => ref.read(mediaCaptureProvider).captureVideo());

  // ── Voice notes ───────────────────────────────────────────────────────────

  Future<void> _startRecording() async {
    if (_busy || _recording) return;
    final VoiceNoteRecorder recorder = ref.read(voiceNoteRecorderProvider);
    final bool started = await recorder.start();
    if (!started) {
      _snack('Could not start recording — the microphone is unavailable.');
      return;
    }
    if (!mounted) return;
    setState(() {
      _recording = true;
      _recordedSeconds = 0;
    });
    _tick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() => _recordedSeconds++);
    });
  }

  Future<void> _finishRecording({required bool send}) async {
    _tick?.cancel();
    _tick = null;
    final VoiceNoteRecorder recorder = ref.read(voiceNoteRecorderProvider);
    if (mounted) setState(() => _recording = false);
    if (!send) {
      await recorder.cancel();
      return;
    }
    final PickedAttachment? note = await recorder.stop();
    if (note == null) {
      // Too short to be a note — an accidental tap, not an error.
      _snack('Too short — hold on a moment longer.');
      return;
    }
    await widget.onAttachment(note);
  }

  // ── The round button and its mode ─────────────────────────────────────────

  /// The modes this device can actually offer, in cycle order.
  ///
  /// `read`, not `watch`, because this is also called from the swap handler
  /// (where `watch` is illegal) and because the answer cannot change while the
  /// app runs — it comes from one probe at startup.
  List<ComposerCaptureMode> _availableModes() {
    final MediaCaptureDelegate capture = ref.read(mediaCaptureProvider);
    return <ComposerCaptureMode>[
      if (ref.read(voiceNoteRecorderProvider).available)
        ComposerCaptureMode.voice,
      if (capture.canCapturePhoto) ComposerCaptureMode.photo,
      if (capture.canCaptureVideo) ComposerCaptureMode.video,
    ];
  }

  /// The current mode, corrected to something this device has. A stale `_mode`
  /// (a camera that vanished, or a default that never applied) must never leave
  /// the button doing nothing.
  ComposerCaptureMode? _effectiveMode(List<ComposerCaptureMode> available) {
    if (available.isEmpty) return null;
    return available.contains(_mode) ? _mode : available.first;
  }

  /// Move to the next available mode.
  ///
  /// A visible control rather than a long-press: holding is undiscoverable with
  /// a mouse *and* with a finger, and the one thing worse than a hidden gesture
  /// is a hidden gesture that shares a target with a tap that records.
  void _cycleMode() {
    final List<ComposerCaptureMode> available = _availableModes();
    if (available.length < 2) return;
    final ComposerCaptureMode current =
        _effectiveMode(available) ?? available.first;
    final int next = (available.indexOf(current) + 1) % available.length;
    setState(() => _mode = available[next]);
  }

  static IconData _iconFor(ComposerCaptureMode mode) => switch (mode) {
        ComposerCaptureMode.voice => Icons.mic_none,
        ComposerCaptureMode.photo => Icons.photo_camera_outlined,
        ComposerCaptureMode.video => Icons.videocam_outlined,
      };

  static String _labelFor(ComposerCaptureMode mode) => switch (mode) {
        ComposerCaptureMode.voice => 'Record a voice note',
        ComposerCaptureMode.photo => 'Take a photo',
        ComposerCaptureMode.video => 'Record a video',
      };

  @override
  Widget build(BuildContext context) {
    if (_recording) return _recordingStrip(context);

    final ColorScheme colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: <Widget>[
          Expanded(
            child: TextField(
              key: const Key('dm-input'),
              controller: widget.controller,
              focusNode: widget.focusNode,
              minLines: 1,
              maxLines: 5,
              textInputAction: TextInputAction.send,
              onSubmitted: (_) => widget.onSend(),
              decoration: InputDecoration(
                hintText: widget.hintText,
                isDense: true,
                filled: true,
                fillColor: colors.surfaceContainerHighest,
                contentPadding:
                    const EdgeInsets.symmetric(horizontal: 4, vertical: 10),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(26),
                  borderSide: BorderSide.none,
                ),
                // Emoji on the left, paper clip on the right — both *inside*
                // the field, so the composer reads as one pill.
                prefixIcon: IconButton(
                  key: const Key('dm-emoji'),
                  tooltip: 'Emoji',
                  onPressed: _openEmoji,
                  icon: const Icon(Icons.emoji_emotions_outlined),
                ),
                suffixIcon: IconButton(
                  key: const Key('dm-attach'),
                  tooltip: 'Attach a file (max 10 MB, encrypted)',
                  onPressed: (widget.attaching || _busy) ? null : _attach,
                  icon: (widget.attaching || _busy)
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.attach_file),
                ),
              ),
            ),
          ),
          ..._trailing(context),
        ],
      ),
    );
  }

  /// The swap control (when there is more than one way to capture) and the round
  /// action button.
  List<Widget> _trailing(BuildContext context) {
    final List<ComposerCaptureMode> available = _availableModes();
    final ComposerCaptureMode? mode = _effectiveMode(available);
    return <Widget>[
      if (!_hasText && mode != null && available.length > 1)
        IconButton(
          key: const Key('dm-swap-capture'),
          tooltip: 'Switch to '
              '${_labelFor(available[(available.indexOf(mode) + 1) % available.length]).toLowerCase()}',
          onPressed: _busy ? null : _cycleMode,
          // The glyph is the mode you would move *to*, so the control shows
          // what tapping it gets you rather than what you already have.
          icon: Icon(
            _iconFor(
                available[(available.indexOf(mode) + 1) % available.length]),
            size: 20,
          ),
        ),
      const SizedBox(width: 6),
      _actionButton(context, mode),
    ];
  }

  /// Send when there is text; otherwise the current capture control — and a
  /// plain (disabled) Send where the platform has neither.
  Widget _actionButton(BuildContext context, ComposerCaptureMode? mode) {
    if (_hasText || mode == null) {
      // `mode == null` means nothing to capture here: keep Send in the same
      // place so the layout does not jump, disabled when there is nothing to
      // send.
      final bool canSend = _hasText && !widget.sending;
      return IconButton.filled(
        key: const Key('dm-send'),
        tooltip: 'Send',
        onPressed: canSend ? () => widget.onSend() : null,
        icon: widget.sending
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.send),
      );
    }

    return IconButton.filled(
      key: Key(switch (mode) {
        ComposerCaptureMode.voice => 'dm-record',
        ComposerCaptureMode.photo => 'dm-camera',
        ComposerCaptureMode.video => 'dm-video',
      }),
      tooltip: _labelFor(mode),
      onPressed: _busy
          ? null
          : switch (mode) {
              ComposerCaptureMode.voice => _startRecording,
              ComposerCaptureMode.photo => _capturePhoto,
              ComposerCaptureMode.video => _captureVideo,
            },
      icon: _busy
          ? const SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : Icon(_iconFor(mode)),
    );
  }

  /// While recording, the whole row becomes the recorder: discard · elapsed ·
  /// send. Nothing else is reachable, because nothing else should be.
  Widget _recordingStrip(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    final String elapsed =
        '${(_recordedSeconds ~/ 60)}:${(_recordedSeconds % 60).toString().padLeft(2, '0')}';
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      child: Row(
        children: <Widget>[
          IconButton(
            key: const Key('dm-record-cancel'),
            tooltip: 'Discard',
            onPressed: () => _finishRecording(send: false),
            icon: Icon(Icons.delete_outline, color: colors.error),
          ),
          Icon(Icons.fiber_manual_record, size: 14, color: colors.error),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'Recording  $elapsed',
              key: const Key('dm-record-elapsed'),
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ),
          IconButton.filled(
            key: const Key('dm-record-send'),
            tooltip: 'Send voice note',
            onPressed: () => _finishRecording(send: true),
            icon: const Icon(Icons.send),
          ),
        ],
      ),
    );
  }
}
