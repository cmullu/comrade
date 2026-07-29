/// An icon with a slash that is *drawn on*, rather than a second glyph.
///
/// Every other video app animates this: the mic does not swap to a different
/// picture when you mute, a line is struck through the mic you were already
/// looking at. Swapping `Icons.mic` for `Icons.mic_off` is one frame of
/// substitution — the eye reads it as "the icon changed", not as "the mic is
/// off" — and at a glance the two glyphs differ by a few pixels of hairline.
///
/// So the slash is painted and animated here, and it is deliberately loud: a
/// stroke in the *button's* colour underneath the tinted stroke, which reads as
/// the line having been cut out of the icon and keeps the slash legible against
/// the icon's own strokes at any size.
///
/// Used for the microphone and the camera, which are the two controls whose
/// off-state must be unmistakable mid-call.
library;

import 'package:flutter/material.dart';

/// How long the slash takes to draw itself on, and to retract.
const Duration kSlashDuration = Duration(milliseconds: 220);

class SlashedIcon extends StatelessWidget {
  const SlashedIcon({
    required this.icon,
    required this.slashed,
    required this.color,
    required this.cutoutColor,
    this.size = 26,
    super.key,
  });

  final IconData icon;

  /// Drives the animation: the slash grows in when this turns true and
  /// retracts when it turns false.
  final bool slashed;

  final Color color;

  /// The colour behind the icon — the disc the control sits on. Painted as a
  /// wider stroke under the slash so the line reads as a gap in the glyph.
  final Color cutoutColor;

  final double size;

  @override
  Widget build(BuildContext context) => TweenAnimationBuilder<double>(
        tween: Tween<double>(begin: 0, end: slashed ? 1 : 0),
        duration: kSlashDuration,
        curve: Curves.easeOutCubic,
        builder: (BuildContext context, double progress, Widget? child) =>
            CustomPaint(
          foregroundPainter: _SlashPainter(
            progress: progress,
            color: color,
            cutoutColor: cutoutColor,
          ),
          size: Size.square(size),
          child: child,
        ),
        child: Icon(icon, size: size, color: color),
      );
}

class _SlashPainter extends CustomPainter {
  const _SlashPainter({
    required this.progress,
    required this.color,
    required this.cutoutColor,
  });

  /// 0 = no slash, 1 = corner to corner.
  final double progress;
  final Color color;
  final Color cutoutColor;

  @override
  void paint(Canvas canvas, Size size) {
    if (progress <= 0) return;
    // Top-left to bottom-right, inset so the stroke's round cap lands inside
    // the icon box rather than on the disc's edge. Matches the direction
    // Material's own `*_off` glyphs use, so a device that falls back to those
    // never contradicts this one.
    final Offset start = Offset(size.width * 0.16, size.height * 0.14);
    final Offset end = Offset(size.width * 0.84, size.height * 0.86);
    final Offset tip = Offset.lerp(start, end, progress)!;

    final double stroke = size.width * 0.09;
    canvas.drawLine(
      start,
      tip,
      Paint()
        ..color = cutoutColor
        ..strokeWidth = stroke * 2.1
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawLine(
      start,
      tip,
      Paint()
        ..color = color
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.round,
    );
  }

  @override
  bool shouldRepaint(_SlashPainter old) =>
      old.progress != progress ||
      old.color != color ||
      old.cutoutColor != cutoutColor;
}
