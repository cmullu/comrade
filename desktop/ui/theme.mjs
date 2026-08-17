/**
 * Appearance (light / dark / follow the system), as decisions rather than DOM.
 *
 * The visual system in `styles.css` is token-driven the way shadcn/ui's is:
 * one set of names (`--background`, `--card`, `--primary`, …) with a second
 * set of values under `body.theme-light`. Which of the two is live is the only
 * thing that has to be decided, and it is decided here so it can be tested —
 * `main.js` assigns `document.body.className` wholesale when the workspace
 * changes, so the appearance class and the modality class have to be produced
 * together or the next workspace event silently drops the appearance.
 */

/**
 * What a person can choose, in the order the toggle cycles them.
 *
 * Dark leads because this app is dark-first and always has been — upstream
 * shadcn defaults to following the OS, but adopting that here would repaint
 * every existing install white on first launch, which is a redesign nobody
 * asked for rather than a design system being adopted. `system` is offered,
 * it is simply not the default.
 */
export const APPEARANCES = ["dark", "light", "system"];

/** What an absent or unreadable stored choice means. */
export const DEFAULT_APPEARANCE = "dark";

/** Where the choice is kept. Read by `main.js`; named here so it is one string. */
export const APPEARANCE_STORAGE_KEY = "comrade.appearance";

/**
 * The modality skins that bring their own surfaces (not just their own
 * accent). Entering one is going somewhere else, and that somewhere has a
 * fixed look — so the light appearance does not apply inside it, which also
 * avoids `color-scheme: light` landing on a screen painted in deep cyan.
 */
const SELF_SKINNED = new Set(["theme-couple-sakha", "theme-couple-sakhi"]);

/** Anything unrecognised (a stale or hand-edited store) reads as the default. */
export function normalizeAppearance(raw) {
  return APPEARANCES.includes(raw) ? raw : DEFAULT_APPEARANCE;
}

/** The order the toggle cycles in: dark → light → follow the system → dark. */
export function nextAppearance(pref) {
  const i = APPEARANCES.indexOf(normalizeAppearance(pref));
  return APPEARANCES[(i + 1) % APPEARANCES.length];
}

/**
 * The appearance actually painted, once the OS has been consulted.
 * `systemPrefersDark` is `matchMedia("(prefers-color-scheme: dark)").matches`.
 */
export function resolveAppearance(pref, systemPrefersDark) {
  const p = normalizeAppearance(pref);
  if (p === "light") return "light";
  if (p === "dark") return "dark";
  return systemPrefersDark ? "dark" : "light";
}

/** What the toggle says it will do — the *choice*, not the resolved result. */
export function appearanceLabel(pref) {
  switch (normalizeAppearance(pref)) {
    case "light":
      return "Light";
    case "dark":
      return "Dark";
    default:
      return "Match system";
  }
}

/** The toggle's glyph. Ambiguous on its own, which is why it has a label. */
export function appearanceGlyph(pref) {
  switch (normalizeAppearance(pref)) {
    case "light":
      return "☀";
    case "dark":
      return "☾";
    default:
      // Half-filled: "whichever the system is". A sun or a moon here would
      // claim a state this option does not have.
      return "◐";
  }
}

/**
 * Every class `<body>` should carry: the modality skin first, then the
 * appearance if one applies.
 *
 * Returned as a list and joined by the caller, because the caller assigns
 * `className` wholesale — a partial answer here is an appearance that survives
 * until the next relay status event and then vanishes.
 */
export function bodyClasses(modalityClass, pref, systemPrefersDark) {
  const modality = modalityClass || "theme-base";
  const classes = [modality];
  if (SELF_SKINNED.has(modality)) return classes;
  if (resolveAppearance(pref, systemPrefersDark) === "light") classes.push("theme-light");
  return classes;
}

/** `bodyClasses`, ready to assign to `element.className`. */
export function bodyClassName(modalityClass, pref, systemPrefersDark) {
  return bodyClasses(modalityClass, pref, systemPrefersDark).join(" ");
}
