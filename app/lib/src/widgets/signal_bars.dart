/// The in-call network-strength indicator: four ascending bars, Telegram-style.
///
/// This replaces the 4-emoji short-authentication-string row that used to sit
/// in the same corner of the call screen. The two are not the same kind of
/// signal and the swap is deliberate — see `docs/COMMS_ARCHITECTURE.md` and the
/// note in `screens/call_screen.dart` — but the *slot* is the same, so the code
/// lives where the SAS row lived.
///
/// Design rules, all of them load-bearing:
///  * **Four bars, always drawn.** Empty bars stay visible at low opacity, so
///    "one bar" reads as one-of-four rather than as a lone tick nobody can
///    calibrate against. This is what makes the indicator legible at a glance.
///  * **[CallQuality.unknown] fills nothing.** No reading yet — or stats that
///    could not be parsed — is not "zero bars of signal", but it is also not a
///    number this app is allowed to invent. Empty bars plus no label is the
///    honest rendering, and it is what the first two seconds of every call
///    show while `getStats` has nothing to report.
///  * The bars are the *only* always-on part; the word label appears solely
///    when the connection has actually degraded, so a healthy call carries no
///    text noise.
library;

import 'package:flutter/material.dart';

import '../state/call_providers.dart' show CallQuality;
import '../theme/comrade_theme.dart';

/// How many of the four bars a quality reading fills.
///
/// A pure function, exported for tests and for any other surface that wants
/// the same mapping (the Compose and desktop call UIs use the same numbers).
/// `medium` deliberately fills two rather than three: three-of-four reads as
/// "basically fine", which is the opposite of what a MEDIUM sample means.
int signalBarsFor(CallQuality quality) => switch (quality) {
      CallQuality.good => 4,
      CallQuality.medium => 2,
      CallQuality.poor => 1,
      CallQuality.unknown => 0,
    };

/// The short human label for a degraded connection, or `null` when there is
/// nothing worth saying (good, or nothing measured yet).
String? signalLabelFor(CallQuality quality) => switch (quality) {
      CallQuality.poor => 'Poor connection',
      CallQuality.medium => 'Weak signal',
      CallQuality.good || CallQuality.unknown => null,
    };

/// The colour the filled bars take. Good is plain white so it disappears into
/// the chrome; degraded readings borrow the call palette's warning colours.
Color signalColorFor(CallQuality quality) => switch (quality) {
      CallQuality.poor => CallPalette.hangup,
      CallQuality.medium => CallPalette.weakConnection,
      CallQuality.good || CallQuality.unknown => Colors.white,
    };

/// Four ascending bars, [signalBarsFor] of them filled.
///
/// Colour and fill animate rather than snapping: quality is sampled every two
/// seconds, and an un-animated indicator flicking between two and four bars
/// reads as a glitch instead of as a measurement.
class SignalStrengthBars extends StatelessWidget {
  const SignalStrengthBars({
    required this.quality,
    this.height = 14,
    this.barWidth = 3,
    this.spacing = 2,
    super.key,
  });

  final CallQuality quality;

  /// Height of the tallest (fourth) bar. The first is 45% of it.
  final double height;
  final double barWidth;
  final double spacing;

  static const int barCount = 4;

  @override
  Widget build(BuildContext context) {
    final int filled = signalBarsFor(quality);
    final Color color = signalColorFor(quality);
    return Semantics(
      label: quality == CallQuality.unknown
          ? 'Connection strength: measuring'
          : 'Connection strength: $filled of $barCount bars',
      child: Row(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: <Widget>[
          for (int i = 0; i < barCount; i++) ...<Widget>[
            if (i > 0) SizedBox(width: spacing),
            AnimatedContainer(
              duration: const Duration(milliseconds: 220),
              curve: Curves.easeOut,
              width: barWidth,
              height: height * (0.45 + 0.55 * (i / (barCount - 1))),
              decoration: BoxDecoration(
                color: i < filled ? color : const Color(0x40FFFFFF),
                borderRadius: BorderRadius.circular(barWidth / 2),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// The bars plus, only while degraded, the word for what they mean.
///
/// Sits in a translucent pill so it stays readable over a bright video frame —
/// the same treatment the name/duration pill beside it gets.
class ConnectionStrengthIndicator extends StatelessWidget {
  const ConnectionStrengthIndicator({
    required this.quality,
    this.compact = false,
    super.key,
  });

  final CallQuality quality;

  /// Bars only, no pill and no label — for the picture-in-picture tile, where
  /// there is room for an indicator but not for a sentence.
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final String? label = signalLabelFor(quality);
    if (compact) return SignalStrengthBars(quality: quality, height: 10);
    return Container(
      key: const Key('call-signal'),
      decoration: BoxDecoration(
        color: const Color(0x66000000),
        borderRadius: BorderRadius.circular(14),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          SignalStrengthBars(quality: quality),
          if (label != null) ...<Widget>[
            const SizedBox(width: 8),
            Text(
              label,
              style: TextStyle(color: signalColorFor(quality), fontSize: 13),
            ),
          ],
        ],
      ),
    );
  }
}
