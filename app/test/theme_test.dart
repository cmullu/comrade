/// Dark mode as a property, not a hope.
///
/// Three things are asserted here, in order of how badly they break:
///
///  1. **Both schemes exist and are actually opposite.** A dark theme whose
///     surface ramp came out light — or a light one still holding the dark
///     ramp, since the two are separate objects and easy to mismatch — is the
///     kind of bug that only shows up on the platform nobody develops on.
///  2. **Every text colour is legible on every background it can land on**,
///     in *both* brightnesses and for *all four* workspace skins: 4.5:1, WCAG
///     2.1 AA for body text. This is the check that caught the real defect.
///     The light-mode skin accents were `styles.css`'s `--accent` values,
///     which are dark-background *fills* there, and reused as a light-mode
///     `primary` they put 2.1:1 text on white. `SectionCard` renders its title
///     in `primary`, so every card heading in the Travel workspace was close
///     to invisible in daylight.
///  3. **The stored choice reaches the screen** and outranks the OS.
///
/// The contrast loop is exhaustive rather than spot-checked on purpose: it is
/// the only thing standing between a future palette tweak and a repeat of (2),
/// and it costs microseconds.
library;

import 'package:comrade/src/app.dart';
import 'package:comrade/src/data/app_preferences.dart';
import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/screens/settings_screen.dart';
import 'package:comrade/src/state/providers.dart';
import 'package:comrade/src/state/settings_providers.dart';
import 'package:comrade/src/theme/comrade_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

/// WCAG 2.1 contrast ratio between two opaque colours. `computeLuminance()` is
/// already the spec's relative luminance, so this is the ratio formula on top.
double contrastRatio(Color a, Color b) {
  final double la = a.computeLuminance();
  final double lb = b.computeLuminance();
  final double hi = la > lb ? la : lb;
  final double lo = la > lb ? lb : la;
  return (hi + 0.05) / (lo + 0.05);
}

/// AA for body text. Everything the UI renders words in must clear this.
const double kAaBodyText = 4.5;

/// AA for large text and non-text UI. Only [ColorScheme.outline] is held to
/// this lower bar: it is Material's designated *quiet* role — the off pill's
/// label, the `core vX` build stamp — and the palette puts it at 3.9:1 (dark)
/// and 4.1:1 (light), recessive on purpose rather than merely unchecked.
/// Raising it is a design call, not a bug fix; pinning a floor here at least
/// stops it drifting further down.
const double kAaLargeText = 3.0;

ThemeData themeFor(Brightness brightness, WorkspaceSkin skin) =>
    brightness == Brightness.dark
        ? ComradeTheme.dark(skin: skin)
        : ComradeTheme.light(skin: skin);

void main() {
  group('both schemes exist, and are opposites', () {
    test('dark() is dark from the scaffold up', () {
      final ThemeData t = ComradeTheme.dark();
      expect(t.brightness, Brightness.dark);
      expect(t.colorScheme.brightness, Brightness.dark);
      expect(t.scaffoldBackgroundColor.computeLuminance(), lessThan(0.05));
      expect(t.colorScheme.surface.computeLuminance(), lessThan(0.05));
      // Light text, not merely "some colour".
      expect(t.colorScheme.onSurface.computeLuminance(), greaterThan(0.5));
      expect(t.textTheme.bodyMedium?.color, t.colorScheme.onSurface);
      expect(t.textTheme.headlineMedium?.color, t.colorScheme.onSurface);
    });

    test('light() is light from the scaffold up', () {
      final ThemeData t = ComradeTheme.light();
      expect(t.brightness, Brightness.light);
      expect(t.colorScheme.brightness, Brightness.light);
      expect(t.scaffoldBackgroundColor.computeLuminance(), greaterThan(0.8));
      expect(t.colorScheme.surface.computeLuminance(), greaterThan(0.8));
      expect(t.colorScheme.onSurface.computeLuminance(), lessThan(0.05));
      expect(t.textTheme.bodyMedium?.color, t.colorScheme.onSurface);
    });

    test('each brightness registers its own ramp', () {
      final ComradeSurfaces? dark =
          ComradeTheme.dark().extension<ComradeSurfaces>();
      final ComradeSurfaces? light =
          ComradeTheme.light().extension<ComradeSurfaces>();
      expect(dark, isNotNull);
      expect(light, isNotNull);
      expect(dark!.panel, ComradeSurfaces.dark.panel);
      expect(light!.panel, ComradeSurfaces.light.panel);
      expect(dark.panel.computeLuminance(), lessThan(0.05));
      expect(light.panel.computeLuminance(), greaterThan(0.8));
    });

    test('no ramp token is shared — a shared one is a leak', () {
      const ComradeSurfaces d = ComradeSurfaces.dark;
      const ComradeSurfaces l = ComradeSurfaces.light;
      expect(d.panel, isNot(l.panel));
      expect(d.panelAlt, isNot(l.panelAlt));
      expect(d.border, isNot(l.border));
      expect(d.borderStrong, isNot(l.borderStrong));
      expect(d.good, isNot(l.good));
      expect(d.warn, isNot(l.warn));
      expect(d.bad, isNot(l.bad));
    });

    test('every skin has a distinct accent per brightness', () {
      for (final WorkspaceSkin skin in WorkspaceSkin.values) {
        expect(skin.darkAccent, isNot(skin.lightAccent), reason: skin.name);
        // The bright one belongs on dark and the dark one on light, not the
        // reverse — which is exactly how the light accents went wrong.
        expect(
          skin.darkAccent.computeLuminance(),
          greaterThan(skin.lightAccent.computeLuminance()),
          reason: '${skin.name}: the dark-mode accent must be the brighter',
        );
      }
    });

    test('the status ramp agrees with the scheme beside it', () {
      // `ErrorText` colours from `colorScheme.error`; the status pills colour
      // from `surfaces.bad`. Two names for one meaning, so one colour.
      expect(ComradeTheme.dark().colorScheme.error, ComradeSurfaces.dark.bad);
      expect(
        ComradeTheme.light().colorScheme.error,
        ComradeSurfaces.light.bad,
      );
    });
  });

  // ── Contrast: every skin × every brightness ──────────────────────────────
  group('contrast', () {
    for (final WorkspaceSkin skin in WorkspaceSkin.values) {
      for (final Brightness brightness in Brightness.values) {
        test('${skin.name} in ${brightness.name} is legible', () {
          final String where = '${skin.name}/${brightness.name}';
          final ThemeData t = themeFor(brightness, skin);
          final ColorScheme c = t.colorScheme;
          final ComradeSurfaces s = t.extension<ComradeSurfaces>()!;

          // Every background a word can be painted on.
          final Map<String, Color> backgrounds = <String, Color>{
            'surface': c.surface,
            'scaffold': t.scaffoldBackgroundColor,
            'panel': s.panel,
            'panelAlt': s.panelAlt,
          };

          // Every role the UI renders text in. `primary` is here because
          // `SectionCard` titles and `_ProfileCard`'s labels use it directly;
          // good/warn/bad because the sidebar pills colour a label with them.
          final Map<String, Color> textRoles = <String, Color>{
            'onSurface': c.onSurface,
            'onSurfaceVariant': c.onSurfaceVariant,
            'primary': c.primary,
            'secondary': c.secondary,
            'tertiary': c.tertiary,
            'error': c.error,
            'good': s.good,
            'warn': s.warn,
            'bad': s.bad,
          };

          backgrounds.forEach((String bgName, Color bg) {
            textRoles.forEach((String fgName, Color fg) {
              expect(
                contrastRatio(fg, bg),
                greaterThanOrEqualTo(kAaBodyText),
                reason: '$where: $fgName on $bgName',
              );
            });
            expect(
              contrastRatio(c.outline, bg),
              greaterThanOrEqualTo(kAaLargeText),
              reason: '$where: outline on $bgName',
            );
          });

          // A filled surface against its own foreground: a FilledButton, a
          // crisis card, an error container.
          void expectPair(String label, Color fg, Color bg) {
            expect(
              contrastRatio(fg, bg),
              greaterThanOrEqualTo(kAaBodyText),
              reason: '$where: on$label over $label',
            );
          }

          expectPair('primary', c.onPrimary, c.primary);
          expectPair('secondary', c.onSecondary, c.secondary);
          expectPair('tertiary', c.onTertiary, c.tertiary);
          expectPair('error', c.onError, c.error);
          expectPair(
            'primaryContainer',
            c.onPrimaryContainer,
            c.primaryContainer,
          );
          expectPair(
            'secondaryContainer',
            c.onSecondaryContainer,
            c.secondaryContainer,
          );
          expectPair(
            'errorContainer',
            c.onErrorContainer,
            c.errorContainer,
          );
        });
      }
    }

    test('the call overlay is legible on its own dark background', () {
      // Exempt from the theme entirely, so it needs its own check or it gets
      // none.
      final Map<String, Color> foregrounds = <String, Color>{
        'secondaryText': CallPalette.secondaryText,
        'controlActive': CallPalette.controlActive,
        'weakConnection': CallPalette.weakConnection,
      };
      foregrounds.forEach((String name, Color fg) {
        expect(
          contrastRatio(fg, CallPalette.background),
          greaterThanOrEqualTo(kAaBodyText),
          reason: 'CallPalette.$name on the call background',
        );
        expect(
          contrastRatio(fg, CallPalette.pipBackground),
          greaterThanOrEqualTo(kAaBodyText),
          reason: 'CallPalette.$name on the PiP background',
        );
      });
    });
  });

  testWidgets('the ramp falls back by brightness', (
    WidgetTester tester,
  ) async {
    // A theme that never registered the extension. Until the fallback followed
    // the ambient brightness it always answered with the dark ramp, painting
    // near-black panels and borders onto a white scaffold.
    final Map<Brightness, ComradeSurfaces> seen =
        <Brightness, ComradeSurfaces>{};
    Widget probe(Brightness brightness) => Theme(
          data: ThemeData(brightness: brightness),
          child: Builder(
            builder: (BuildContext context) {
              seen[brightness] = context.surfaces;
              return const SizedBox.shrink();
            },
          ),
        );

    await tester.pumpWidget(
      Column(
        textDirection: TextDirection.ltr,
        children: <Widget>[
          probe(Brightness.light),
          probe(Brightness.dark),
        ],
      ),
    );

    expect(seen[Brightness.light]?.panel, ComradeSurfaces.light.panel);
    expect(seen[Brightness.dark]?.panel, ComradeSurfaces.dark.panel);
    expect(
      ComradeSurfaces.forBrightness(Brightness.dark).panel,
      ComradeSurfaces.dark.panel,
    );
  });

  group('the stored theme mode', () {
    test('round-trips, and junk means "follow the OS"', () {
      for (final ThemeMode mode in ThemeMode.values) {
        expect(themeModeFromKey(themeModeKey(mode)), mode);
      }
      expect(themeModeKey(ThemeMode.dark), 'dark');
      // Absent, empty, and a spelling this build does not know must all land
      // on system rather than leave the app themeless.
      expect(themeModeFromKey(null), ThemeMode.system);
      expect(themeModeFromKey(''), ThemeMode.system);
      expect(themeModeFromKey('midnight'), ThemeMode.system);
    });

    test('defaults to the system when nothing is stored', () async {
      final FakeComradeRepository repo = await unlockedFake();
      final ProviderContainer container = testContainer(repo);
      expect(container.read(themeModeProvider), ThemeMode.system);
    });

    test('is what the app boots with', () async {
      final FakeComradeRepository repo = await unlockedFake();
      final ProviderContainer container = testContainer(
        repo,
        prefs: <String, Object>{PrefKeys.themeMode: 'dark'},
      );
      expect(container.read(themeModeProvider), ThemeMode.dark);
    });

    test('writes through the preferences seam', () async {
      final FakeComradeRepository repo = await unlockedFake();
      final InMemoryPreferences prefs = InMemoryPreferences();
      final ProviderContainer container = ProviderContainer(
        overrides: <Override>[
          ...fakeOverrides(repo),
          appPreferencesProvider.overrideWithValue(prefs),
        ],
      );
      addTearDown(container.dispose);

      await container.read(themeModeProvider.notifier).set(ThemeMode.light);
      expect(container.read(themeModeProvider), ThemeMode.light);
      expect(prefs.getString(PrefKeys.themeMode), 'light');

      await container.read(themeModeProvider.notifier).set(ThemeMode.dark);
      expect(prefs.getString(PrefKeys.themeMode), 'dark');
    });
  });

  group('the choice outranks the OS', () {
    /// `MaterialApp`'s own resolution decides the rendered brightness, so this
    /// returns both halves: the mode the app handed it, and the brightness a
    /// descendant actually sees.
    Future<(ThemeMode, Brightness)> boot(
      WidgetTester tester, {
      required Brightness platform,
      String? stored,
    }) async {
      tester.platformDispatcher.platformBrightnessTestValue = platform;
      addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);

      final FakeComradeRepository repo = await unlockedFake();
      await tester.pumpWidget(
        ProviderScope(
          overrides: fakeOverrides(
            repo,
            prefs: stored == null
                ? null
                : <String, Object>{PrefKeys.themeMode: stored},
          ),
          child: const ComradeApp(),
        ),
      );
      // The fake runs at zero latency, so one frame settles the phase gate.
      await tester.pump();

      final MaterialApp app =
          tester.widget<MaterialApp>(find.byType(MaterialApp));
      final BuildContext ctx = tester.element(find.byType(Scaffold).first);
      return (app.themeMode!, Theme.of(ctx).brightness);
    }

    testWidgets('dark beats a light system', (WidgetTester tester) async {
      expect(
        await boot(tester, platform: Brightness.light, stored: 'dark'),
        (ThemeMode.dark, Brightness.dark),
      );
    });

    testWidgets('light beats a dark system', (WidgetTester tester) async {
      expect(
        await boot(tester, platform: Brightness.dark, stored: 'light'),
        (ThemeMode.light, Brightness.light),
      );
    });

    testWidgets('unset follows a dark system', (WidgetTester tester) async {
      expect(
        await boot(tester, platform: Brightness.dark),
        (ThemeMode.system, Brightness.dark),
      );
    });

    testWidgets('unset follows a light system', (WidgetTester tester) async {
      expect(
        await boot(tester, platform: Brightness.light),
        (ThemeMode.system, Brightness.light),
      );
    });
  });

  group('Settings, Appearance', () {
    /// The real control in the real screen driving the real theme — the same
    /// wiring `ComradeApp` uses, so a tap has to change what is on screen and
    /// not merely a provider's value.
    Widget app(FakeComradeRepository repo) => ProviderScope(
          overrides: fakeOverrides(repo),
          child: Consumer(
            builder: (BuildContext context, WidgetRef ref, Widget? child) =>
                MaterialApp(
              theme: ComradeTheme.light(),
              darkTheme: ComradeTheme.dark(),
              themeMode: ref.watch(themeModeProvider),
              home: const Scaffold(body: SettingsScreen()),
            ),
          ),
        );

    testWidgets('picking Dark turns the screen dark', (
      WidgetTester tester,
    ) async {
      tester.platformDispatcher.platformBrightnessTestValue = Brightness.light;
      addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
      // Tall enough that the whole settings list lays out without scrolling.
      setWindowSize(tester, const Size(1000, 1600));

      final FakeComradeRepository repo = await unlockedFake();
      await tester.pumpWidget(app(repo));
      await tester.pumpAndSettle();

      Brightness rendered() => Theme.of(
            tester.element(find.byKey(const Key('theme-mode'))),
          ).brightness;

      expect(find.byKey(const Key('theme-mode')), findsOneWidget);
      expect(rendered(), Brightness.light);

      await tester.tap(find.text('Dark'));
      await tester.pumpAndSettle();
      expect(rendered(), Brightness.dark);

      await tester.tap(find.text('Light'));
      await tester.pumpAndSettle();
      expect(rendered(), Brightness.light);

      // Back to the OS, which is still reporting light.
      await tester.tap(find.text('System'));
      await tester.pumpAndSettle();
      expect(rendered(), Brightness.light);
    });
  });
}
