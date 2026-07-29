/// Responsive breakpoints for the one shell that serves both a phone and a
/// resizable desktop window.
///
/// The numbers are not invented: they are the ones the two existing frontends
/// already behave at.
///  * **840** — `desktop/ui/styles.css:1668` folds the sidebar into a top bar
///    below this width. Above it the sidebar shell is the desktop layout; below
///    it we show Android's bottom navigation. Same app, same code, one query.
///  * **1600** — `styles.css:1735` starts using the spare width on ultrawide
///    monitors (the couple panels go side by side).
///  * **600** — Material's own compact/medium line; used only to widen touch
///    targets and gutters on a big phone or a small tablet, never to change
///    navigation.
library;

import 'package:flutter/widgets.dart';

/// Which layout family the current width belongs to.
enum ComradeWindowClass {
  /// Phones, and any desktop window dragged narrow. Bottom navigation.
  compact,

  /// Large phones / small tablets. Still bottom navigation, roomier gutters.
  medium,

  /// The desktop shell: persistent sidebar, list-detail panes.
  expanded,

  /// 21:9 and wider — there is real spare width to spend.
  ultrawide;

  bool get usesBottomNav => this == compact || this == medium;
  bool get usesSidebar => this == expanded || this == ultrawide;

  /// Whether a list and its detail can sit side by side (chat list + thread).
  bool get supportsTwoPane => usesSidebar;
}

abstract final class Breakpoints {
  static const double medium = 600;
  static const double expanded = 840;
  static const double ultrawide = 1600;

  /// Sidebar track: `clamp(232px, 15vw, 288px)` in `styles.css:323`.
  static double sidebarWidth(double windowWidth) =>
      (windowWidth * 0.15).clamp(232.0, 288.0);

  /// Conversation-list track: `clamp(240px, 18vw, 320px)` in `styles.css:669`.
  static double conversationListWidth(double windowWidth) =>
      (windowWidth * 0.18).clamp(240.0, 320.0);

  /// Reading column for the feed: `clamp(720px, 66vw, 1280px)`
  /// (`styles.css:590`). Chitthis are short-form, so the ceiling is wider than
  /// a prose column but the line length is still capped.
  static double feedColumnWidth(double windowWidth) =>
      (windowWidth * 0.66).clamp(720.0, 1280.0);

  static ComradeWindowClass classify(double width) {
    if (width >= ultrawide) return ComradeWindowClass.ultrawide;
    if (width >= expanded) return ComradeWindowClass.expanded;
    if (width >= medium) return ComradeWindowClass.medium;
    return ComradeWindowClass.compact;
  }
}

/// The window class for [context]. Reads `MediaQuery` width, so it reacts to a
/// desktop window being dragged, not just to a device rotation.
ComradeWindowClass windowClassOf(BuildContext context) =>
    Breakpoints.classify(MediaQuery.sizeOf(context).width);
