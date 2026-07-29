/// Comrade's visual system, derived from the two it replaces.
///
///  * Colours, shapes and the type hierarchy come from
///    `android/.../ui/theme/Theme.kt`.
///  * The surface/border ramp, the workspace accent skins (Travel amber,
///    Sakha cyan, Sakhi rose) and the "dark-mode-first" posture come from
///    `desktop/ui/styles.css` `:root` and its `body.theme-*` blocks.
///
/// One deliberate divergence, flagged in `SCREEN_INVENTORY.md`: Android opted
/// into Material You dynamic colour (`dynamicColor = true`), so on Android 12+
/// the app took the system wallpaper palette and the brand colours above were
/// only a fallback. Desktop has no such source and is brand-coloured always.
/// The unified app is **brand-coloured on every platform**: the two frontends
/// otherwise render visibly different products, and the call/crisis/status
/// colours below are load-bearing (a wallpaper-derived "error" container is
/// not guaranteed to read as alarming). Dynamic colour can come back later as
/// an explicit opt-in setting.
library;

import 'package:flutter/material.dart';

/// Identity-stable avatar hues: the same key renders the same colour on every
/// device (Telegram-style), so people become recognisable at a glance.
/// Copied verbatim from `ChatsScreen.kt`'s `AvatarPalette`.
const List<Color> kAvatarPalette = <Color>[
  Color(0xFF6366F1), // indigo
  Color(0xFF0EA5E9), // sky
  Color(0xFF10B981), // emerald
  Color(0xFFF59E0B), // amber
  Color(0xFFEF4444), // coral
  Color(0xFF8B5CF6), // violet
  Color(0xFFEC4899), // rose
  Color(0xFF14B8A6), // teal
];

/// The workspace skins `styles.css` swaps CSS variables for.
enum WorkspaceSkin {
  /// `:root` — indigo.
  base(Color(0xFF818CF8), Color(0xFF6366F1)),

  /// `body.theme-travel` — warm amber, "off the grid".
  travel(Color(0xFFFBBF24), Color(0xFFF59E0B)),

  /// `body.theme-couple-sakha` — cool cyan.
  coupleSakha(Color(0xFF7DD3FC), Color(0xFF38BDF8)),

  /// `body.theme-couple-sakhi` — warm rose.
  coupleSakhi(Color(0xFFFDA4AF), Color(0xFFFB7185));

  const WorkspaceSkin(this.darkAccent, this.lightAccent);

  final Color darkAccent;
  final Color lightAccent;

  static WorkspaceSkin fromWorkspaceKey(String key) => switch (key) {
        'OffGridTravel' => travel,
        'CoupleSandboxSakha' => coupleSakha,
        'CoupleSandboxSakhi' => coupleSakhi,
        _ => base,
      };
}

/// Colours the call overlay owns outright.
///
/// A call is full-bleed dark on both existing frontends regardless of the app
/// theme (`CallScreen.kt`'s `CallBackground`, `styles.css`'s `.call-overlay`),
/// because a bright call screen at 3am held to a face is a bug. These are not
/// part of the [ColorScheme] for exactly that reason.
abstract final class CallPalette {
  static const Color background = Color(0xFF0E1621);
  static const Color pipBackground = Color(0xFF17212B);
  static const Color accept = Color(0xFF2E7D32);
  static const Color hangup = Color(0xFFC62828);
  static const Color controlIdle = Color(0x33FFFFFF);
  static const Color controlActive = Color(0xFFFFFFFF);
  static const Color weakConnection = Color(0xFFFFA000);
  static const Color secondaryText = Color(0xFFB0BEC5);
}

/// Extra surface tokens `styles.css` has and Material's [ColorScheme] does
/// not — the panel/border ramp the desktop shell is built out of.
@immutable
class ComradeSurfaces extends ThemeExtension<ComradeSurfaces> {
  const ComradeSurfaces({
    required this.panel,
    required this.panelAlt,
    required this.border,
    required this.borderStrong,
    required this.good,
    required this.warn,
    required this.bad,
  });

  /// `--panel` — sidebar, cards, the conversation list.
  final Color panel;

  /// `--panel-2` — a hover/selected step above [panel].
  final Color panelAlt;

  /// `--border`.
  final Color border;

  /// `--border-strong`.
  final Color borderStrong;

  /// `--good` / `--warn` / `--bad` status pills.
  final Color good;
  final Color warn;
  final Color bad;

  static const ComradeSurfaces dark = ComradeSurfaces(
    panel: Color(0xFF131B2E),
    panelAlt: Color(0xFF1A2438),
    border: Color(0xFF243049),
    borderStrong: Color(0xFF34425F),
    good: Color(0xFF34D399),
    warn: Color(0xFFFBBF24),
    bad: Color(0xFFF87171),
  );

  static const ComradeSurfaces light = ComradeSurfaces(
    panel: Color(0xFFF4F6FB),
    panelAlt: Color(0xFFE8ECF6),
    border: Color(0xFFD5DBE8),
    borderStrong: Color(0xFFB6BFD2),
    good: Color(0xFF059669),
    warn: Color(0xFFB45309),
    bad: Color(0xFFDC2626),
  );

  @override
  ComradeSurfaces copyWith({
    Color? panel,
    Color? panelAlt,
    Color? border,
    Color? borderStrong,
    Color? good,
    Color? warn,
    Color? bad,
  }) =>
      ComradeSurfaces(
        panel: panel ?? this.panel,
        panelAlt: panelAlt ?? this.panelAlt,
        border: border ?? this.border,
        borderStrong: borderStrong ?? this.borderStrong,
        good: good ?? this.good,
        warn: warn ?? this.warn,
        bad: bad ?? this.bad,
      );

  @override
  ComradeSurfaces lerp(ThemeExtension<ComradeSurfaces>? other, double t) {
    if (other is! ComradeSurfaces) return this;
    return ComradeSurfaces(
      panel: Color.lerp(panel, other.panel, t)!,
      panelAlt: Color.lerp(panelAlt, other.panelAlt, t)!,
      border: Color.lerp(border, other.border, t)!,
      borderStrong: Color.lerp(borderStrong, other.borderStrong, t)!,
      good: Color.lerp(good, other.good, t)!,
      warn: Color.lerp(warn, other.warn, t)!,
      bad: Color.lerp(bad, other.bad, t)!,
    );
  }
}

/// Convenience accessor: `context.surfaces.border`.
extension ComradeThemeX on BuildContext {
  ComradeSurfaces get surfaces =>
      Theme.of(this).extension<ComradeSurfaces>() ?? ComradeSurfaces.dark;
  ColorScheme get colors => Theme.of(this).colorScheme;
  TextTheme get texts => Theme.of(this).textTheme;
}

/// Soft, generous corner radii — cards, dialogs and sheets read as one
/// rounded, modern surface system instead of the sharper M3 defaults
/// (`Theme.kt`'s `ComradeShapes`).
const ShapeBorder kCardShape = RoundedRectangleBorder(
  borderRadius: BorderRadius.all(Radius.circular(16)),
);

abstract final class ComradeRadii {
  static const double extraSmall = 8;
  static const double small = 12;
  static const double medium = 16;
  static const double large = 22;
  static const double extraLarge = 28;

  /// Chat bubbles: 18 everywhere except the "tail" corner, which is 6.
  static const double bubble = 18;
  static const double bubbleTail = 6;
}

abstract final class ComradeTheme {
  static ThemeData dark({WorkspaceSkin skin = WorkspaceSkin.base}) =>
      _build(Brightness.dark, skin);

  static ThemeData light({WorkspaceSkin skin = WorkspaceSkin.base}) =>
      _build(Brightness.light, skin);

  static ThemeData _build(Brightness brightness, WorkspaceSkin skin) {
    final bool isDark = brightness == Brightness.dark;
    final ColorScheme scheme = isDark
        ? ColorScheme.dark(
            primary: skin.darkAccent,
            onPrimary: const Color(0xFF1E1B4B),
            primaryContainer: const Color(0xFF3730A3),
            onPrimaryContainer: const Color(0xFFE0E7FF),
            secondary: const Color(0xFF34D399),
            onSecondary: const Color(0xFF022C22),
            secondaryContainer: const Color(0xFF1A2438),
            onSecondaryContainer: const Color(0xFFE6EBF5),
            tertiary: const Color(0xFFFBBF24),
            onTertiary: const Color(0xFF2A1B06),
            surface: const Color(0xFF0F1525),
            onSurface: const Color(0xFFE6EBF5),
            surfaceContainerLowest: const Color(0xFF0A0E1A),
            surfaceContainerLow: const Color(0xFF0F1525),
            surfaceContainer: const Color(0xFF131B2E),
            surfaceContainerHigh: const Color(0xFF1A2438),
            surfaceContainerHighest: const Color(0xFF1A2438),
            onSurfaceVariant: const Color(0xFF9AA7C2),
            outline: const Color(0xFF6B7894),
            outlineVariant: const Color(0xFF34425F),
            error: const Color(0xFFF87171),
            onError: const Color(0xFF3B0A0A),
            errorContainer: const Color(0xFF5A1A1A),
            onErrorContainer: const Color(0xFFFFE0E0),
          )
        : ColorScheme.light(
            primary: skin.lightAccent,
            onPrimary: Colors.white,
            primaryContainer: const Color(0xFFE0E7FF),
            onPrimaryContainer: const Color(0xFF1E1B4B),
            secondary: const Color(0xFF059669),
            onSecondary: Colors.white,
            secondaryContainer: const Color(0xFFE8ECF6),
            onSecondaryContainer: const Color(0xFF1E1B4B),
            tertiary: const Color(0xFFB45309),
            onTertiary: Colors.white,
            surface: const Color(0xFFFBFCFF),
            onSurface: const Color(0xFF141A28),
            surfaceContainerLowest: Colors.white,
            surfaceContainerLow: const Color(0xFFF7F9FE),
            surfaceContainer: const Color(0xFFF4F6FB),
            surfaceContainerHigh: const Color(0xFFE8ECF6),
            surfaceContainerHighest: const Color(0xFFE8ECF6),
            onSurfaceVariant: const Color(0xFF4A5468),
            outline: const Color(0xFF6B7894),
            outlineVariant: const Color(0xFFD5DBE8),
          );

    final TextTheme text = _typography(Typography.material2021(
      platform: TargetPlatform.android,
      colorScheme: scheme,
    ).black.apply(
          bodyColor: scheme.onSurface,
          displayColor: scheme.onSurface,
        ));

    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor:
          isDark ? const Color(0xFF0A0E1A) : const Color(0xFFFBFCFF),
      textTheme: text,
      extensions: <ThemeExtension<dynamic>>[
        isDark ? ComradeSurfaces.dark : ComradeSurfaces.light,
      ],
      cardTheme: CardThemeData(
        clipBehavior: Clip.antiAlias,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(ComradeRadii.medium),
        ),
      ),
      dialogTheme: DialogThemeData(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(ComradeRadii.extraLarge),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(ComradeRadii.small),
        ),
        isDense: true,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(0, 44),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(ComradeRadii.large),
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(0, 44),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(ComradeRadii.large),
          ),
        ),
      ),
      navigationBarTheme: const NavigationBarThemeData(height: 68),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(ComradeRadii.small),
        ),
      ),
      dividerTheme: DividerThemeData(
        color: scheme.outlineVariant.withValues(alpha: 0.4),
        space: 1,
        thickness: 1,
      ),
    );
  }

  /// Default M3 type with a firmer title hierarchy: names and headings sit
  /// semi-bold so lists scan by name first, metadata second (`Theme.kt`'s
  /// `ComradeTypography`).
  static TextTheme _typography(TextTheme base) => base.copyWith(
        headlineMedium:
            base.headlineMedium?.copyWith(fontWeight: FontWeight.bold),
        titleLarge: base.titleLarge?.copyWith(fontWeight: FontWeight.w600),
        titleMedium: base.titleMedium?.copyWith(fontWeight: FontWeight.w600),
        titleSmall: base.titleSmall?.copyWith(
          fontWeight: FontWeight.w600,
          letterSpacing: 0.1,
        ),
        labelSmall: base.labelSmall?.copyWith(letterSpacing: 0.2),
      );
}

/// The monospace style keys are rendered in everywhere (`FontFamily.Monospace`
/// on Android, `--mono` on desktop). Keys are compared by eye; a proportional
/// font makes that harder than it needs to be.
TextStyle? monoStyle(TextStyle? base) => base?.copyWith(
      fontFamily: 'monospace',
      fontFamilyFallback: const <String>['Menlo', 'Consolas', 'Roboto Mono'],
    );
