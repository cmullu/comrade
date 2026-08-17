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
///
/// The dark accents are `--primary-hover` verbatim (spelled `--accent-2`
/// before `styles.css` adopted shadcn/ui's token names — see
/// `docs/DESIGN_SYSTEM.md`). The light ones are **not** `--primary`, which is
/// what the first port of this file used: `styles.css` is dark-first, so every
/// value in `:root` was tuned against a near-black background, and there
/// `--primary` is a *fill* carrying `--primary-foreground` text — never text
/// itself. Reused as a light-mode
/// `primary` it becomes small text on white, and `SectionCard` titles in the
/// Travel workspace measured 2.1:1 against the surface (WCAG AA wants 4.5:1
/// for body text). A `FilledButton` was worse still: white on `#f59e0b` is
/// 2.15:1. Each light accent is therefore the same hue taken several steps
/// darker, chosen so the colour clears 4.5:1 both as text on every surface in
/// the ramp *and* under white — `theme_test.dart` asserts exactly that, for
/// every skin, so a future palette edit cannot quietly reintroduce it.
enum WorkspaceSkin {
  /// `:root` — indigo.
  base(Color(0xFF818CF8), Color(0xFF4F46E5)),

  /// `body.theme-travel` — warm amber, "off the grid".
  travel(Color(0xFFFBBF24), Color(0xFF92400E)),

  /// `body.theme-couple-sakha` — cool cyan.
  coupleSakha(Color(0xFF7DD3FC), Color(0xFF0369A1)),

  /// `body.theme-couple-sakhi` — warm rose.
  coupleSakhi(Color(0xFFFDA4AF), Color(0xFFBE123C));

  const WorkspaceSkin(this.darkAccent, this.lightAccent);

  /// Used on a dark background: `--primary-hover`, bright.
  final Color darkAccent;

  /// Used on a light background: the same hue, dark enough to read as text.
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

  /// `--card` — sidebar, cards, the conversation list.
  final Color panel;

  /// `--accent` — a hover/selected step above [panel].
  final Color panelAlt;

  /// `--border`.
  final Color border;

  /// `--input`, the firmer border form controls take.
  final Color borderStrong;

  /// `--success` / `--warning` / `--destructive` status pills.
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

  /// Same note as [WorkspaceSkin]'s light accents: `good`/`warn`/`bad` are
  /// rendered as *text* (the sidebar status pills in `home_shell.dart` colour
  /// their label with them, over an 18%-alpha wash of the same colour), so the
  /// dark ramp's `--success`/`--warning`/`--destructive` are too light to
  /// reuse here. These are the 700/800 steps of the same hues.
  static const ComradeSurfaces light = ComradeSurfaces(
    panel: Color(0xFFF4F6FB),
    panelAlt: Color(0xFFE8ECF6),
    border: Color(0xFFD5DBE8),
    borderStrong: Color(0xFFB6BFD2),
    good: Color(0xFF065F46),
    warn: Color(0xFF92400E),
    bad: Color(0xFFB91C1C),
  );

  static ComradeSurfaces forBrightness(Brightness brightness) =>
      brightness == Brightness.dark ? dark : light;

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
  /// The fallback follows the ambient [Brightness] rather than always being
  /// the dark ramp. A widget built under a theme that never registered the
  /// extension — a stock `ThemeData`, a local `Theme` override, a dialog
  /// someone re-themes — would otherwise paint dark panels and dark borders
  /// onto a light scaffold, which is the exact failure this ramp exists to
  /// avoid. It should never happen; when it does it stays legible.
  ComradeSurfaces get surfaces =>
      Theme.of(this).extension<ComradeSurfaces>() ??
      ComradeSurfaces.forBrightness(Theme.of(this).brightness);
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
            // Was `#6B7894` in both schemes, which is the one thing a shared
            // value cannot be: legible on a near-black surface *and* on a white
            // one. It reached 3.5:1 over `panelAlt` — under AA even for large
            // text — while carrying real information in small type (a message's
            // clock time, delivery ticks, the "mDNS off" pill). Each scheme now
            // gets its own step, still quieter than `onSurfaceVariant` so the
            // hierarchy holds, but no longer quiet to the point of unreadable.
            outline: const Color(0xFF8794B0),
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
            // Kept in step with `ComradeSurfaces.light`'s good/warn, for the
            // same reason: both roles can end up as text on a light surface.
            secondary: const Color(0xFF065F46),
            onSecondary: Colors.white,
            secondaryContainer: const Color(0xFFE8ECF6),
            onSecondaryContainer: const Color(0xFF1E1B4B),
            tertiary: const Color(0xFF92400E),
            onTertiary: Colors.white,
            surface: const Color(0xFFFBFCFF),
            onSurface: const Color(0xFF141A28),
            surfaceContainerLowest: Colors.white,
            surfaceContainerLow: const Color(0xFFF7F9FE),
            surfaceContainer: const Color(0xFFF4F6FB),
            surfaceContainerHigh: const Color(0xFFE8ECF6),
            surfaceContainerHighest: const Color(0xFFE8ECF6),
            onSurfaceVariant: const Color(0xFF4A5468),
            // The light counterpart of the dark scheme's `outline` note: darker
            // rather than lighter, same reason, same 4.5:1 bar.
            outline: const Color(0xFF566072),
            outlineVariant: const Color(0xFFD5DBE8),
            // Stated rather than inherited. `ColorScheme.light`'s default error
            // is Material's own `#b00020`, which left the light theme's error
            // colour unrelated to `ComradeSurfaces.light.bad` even though
            // `ErrorText` reads the first and the status pills read the second.
            // The dark scheme already matched its ramp; now both do.
            error: const Color(0xFFB91C1C),
            onError: Colors.white,
            errorContainer: const Color(0xFFFEE2E2),
            onErrorContainer: const Color(0xFF7F1D1D),
          );

    // `.white` for dark, `.black` for light — then recoloured to `onSurface`
    // anyway. The `.apply` below already covers all fifteen styles, so the
    // choice is invisible today; it is made correctly so that removing the
    // recolour later cannot silently produce black text on a dark surface.
    final Typography typography = Typography.material2021(
      platform: TargetPlatform.android,
      colorScheme: scheme,
    );
    final TextTheme text = _typography(
      (isDark ? typography.white : typography.black).apply(
        bodyColor: scheme.onSurface,
        displayColor: scheme.onSurface,
      ),
    );

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
