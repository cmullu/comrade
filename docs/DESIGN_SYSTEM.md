# Comrade — the design system, adapted from shadcn/ui

_Status: adopted for `desktop/` and mirrored into `android/`'s theme, 2026-08-17.
Scope is the visual system and its component anatomy. It does not change what any
screen does; every decision that answers "may this happen" still lives in the
engines._

## 1. What was adopted, and what was not

[shadcn/ui](https://ui.shadcn.com) is React plus Tailwind plus Radix. Comrade's
desktop UI is deliberately no-build vanilla CSS and plain DOM
(`.claude/rules/desktop.md`: *no build step, no bundler, no framework — keep it
that way*). So what crossed over is the part that is not React:

| Adopted | How it landed |
| --- | --- |
| The **token contract** — `background`/`foreground` pairs, `card`, `popover`, `primary`, `secondary`, `muted`, `accent`, `destructive`, `border`, `input`, `ring` | `:root` in `desktop/ui/styles.css`, as plain CSS custom properties |
| One **`--radius`**, everything else derived | `--radius-sm/md/lg/xl` are `calc()` off it |
| A **light appearance** | `body.theme-light` restates every colour token |
| The **single focus-ring rule** | one `:where(…):focus-visible` selector, not per-component |
| **Component anatomy** — Button variants and sizes, Card, Badge, Input, Label, Switch, Tabs, Dialog, DropdownMenu, Tooltip, Progress, Avatar, Skeleton, Alert, Empty, Kbd, Separator | plain classes with upstream's geometry (`h-9`, `rounded-md`, `text-sm`, `gap-2`) |
| **Command** (cmdk) | a new ⌘K palette: `command_palette.mjs` + the `.palette-*` classes |
| **Sonner**'s toast behaviour | `toast_queue.mjs`: three visible, identical messages count up |

Not adopted: Tailwind, Radix, the CLI, the registry, the React components, and
oklch colour notation (the palette stays in hex, which is what the rest of this
file is written in).

## 2. The two deliberate departures

**Dark is `:root`, light is a class.** Upstream ships light in `:root` and dark
under `.dark`, and defaults to following the OS. Comrade is dark-first — it is a
night-time app and the vault door is the first screen — and defaulting to
"follow the system" would repaint every existing install white on the launch
after an update. So `:root` is dark, `body.theme-light` is the opt-in, and the
default preference is `dark` with `system` offered as a choice
(`theme.mjs`, `APPEARANCES`).

**`--primary` is the brand and modalities override it.** That is upstream's
meaning of `primary` and upstream's meaning of `accent` (the subtle
hover/selected surface). What is Comrade's own is that the Travel and Couple
skins re-theme the whole app by overriding four values — which is why every
component below is written against tokens and never against a literal colour.

## 3. Where the tokens live, on each frontend

One palette, three consumers:

- `desktop/ui/styles.css` — `:root` (dark), `body.theme-light`, and the three
  modality skins. The source of the numbers.
- `android/app/src/main/java/mullu/comrade/ui/theme/Theme.kt` — the same numbers
  as an M3 `ColorScheme`, with the token → M3-role mapping written out in a
  comment (`--card` → `surface`, `--accent` → `surfaceVariant`, `--border` →
  `outlineVariant`, and so on). Only used below Android 12; Material You
  dynamic colour wins above it.
- Radii are each frontend's own on purpose. A touch target is larger and closer
  to the eye than a pointer target, so the desktop's 10px base would read as
  sharp on a phone. `ComradeShapes` keeps Android's more generous scale, and
  says so.

`app/` (Flutter) is **not** yet on this system — see §6.

## 4. Using it

Buttons are `class="btn btn-<variant>"` plus an optional size:

```html
<button class="btn btn-primary">Send</button>       <!-- upstream "default"   -->
<button class="btn btn-secondary">…</button>
<button class="btn btn-outline btn-sm">Back</button>
<button class="btn btn-ghost">Sabha</button>        <!-- no border, hover fill -->
<button class="btn btn-destructive">Delete</button>
<button class="btn btn-link">Learn more</button>
```

`.btn-ghost` is a *true* ghost. What used to be called ghost here — transparent
with a visible border — is `.btn-outline`, which is what upstream calls it. Both
exist because both are needed: a sidebar full of outlined buttons is a sidebar
of boxes.

A panel is a Card (`.card`, `.card-header`, `.card-title`, `.card-description`,
`.card-content`, `.card-footer`). `.composer`, `.chitthi`, `.focus-card`,
`.ledger-panel` and `.vault-card` are all Cards under older names and share the
treatment.

Never write a literal colour. If a token seems to be missing, add it to both
`:root` **and** `body.theme-light` — `styles.test.mjs` fails a token that only
exists in one of them, which is how a navy surface used to end up on a white
card.

## 5. What is tested, and what a browser is still needed for

`node --test desktop/ui/*.test.mjs` covers:

- `styles.test.mjs` — the token contract itself: braces balance, every
  `var(--x)` used is declared, no `var(--x, fallback)` (four undefined tokens
  hid behind fallbacks before this test existed: `--fg`, `--hover`,
  `--surface-2`, `--accent-soft`), `:root` carries the whole shadcn set,
  `body.theme-light` restates every colour, the radius scale is derived, the
  global focus ring exists, and every `btn-*` variant the UI applies has a rule.
- `theme.mjs` / `theme.test.mjs` — appearance resolution, the toggle's cycle,
  and the `<body>` class list. That list matters because `main.js` assigns
  `className` wholesale on every workspace event: an answer that returned only
  the modality class would silently drop light mode a second later.
- `command_palette.mjs` / `.test.mjs` — ranking, grouping, cursor wrap, and
  keeping the highlight on the *entry* rather than the row number.
- `toast_queue.mjs` / `.test.mjs` — the visible cap, the repeat counter, and
  which toast makes way (never the one a person's own click just produced).

None of that says how anything *looks*. The screens in this change were driven
in a real Chromium via Playwright and read as screenshots — vault door, each
tab, both appearances, the palette, the dialogs — which is the only check this
repo has for "does it render". Android's half is **unverified visually**: this
sandbox has no Android SDK, so only `.claude/scripts/android-typecheck-compose.sh`
ran against it.

## 6. Not done

- **`app/` (Flutter) is untouched.** Dart cannot be compiled or tested in this
  sandbox and the theme there is a separate `ThemeData`; porting it is its own
  change, and doing it blind alongside this one would have put two unverifiable
  frontends in one diff.
- **`desktop/src-tauri`'s window chrome** is unchanged (that lane does not build
  here either — CLAUDE.md § *What this sandbox cannot run*).
- **Android's Compose surfaces** got the token mirror only. No screen was
  restyled: Android's own components are Material 3, and Material 3 is a design
  system already. The value of the mirror is that the two agree on colour, not
  that Compose starts imitating shadcn.
