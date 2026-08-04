/// The last look before an attachment leaves the device.
///
/// Telegram's shape: pick a file and you get the file itself, a caption box, and
/// two ways out. This app had none of it — a pick went straight to encrypt and
/// upload, so the first time the sender saw what they had chosen was in their own
/// thread, already delivered. Three things follow from that:
///
///  * **A wrong pick is recoverable.** The gallery grid, the document picker and
///    the camera all hand back something; a thumbnail plus the file's own name
///    and size is what tells you it was the wrong something.
///  * **The caption is composed against the picture**, not typed before it into a
///    box that then empties. It is still *seeded* from the composer, so the habit
///    of typing-then-attaching keeps working (see [captionForAttachment]).
///  * **Backing out costs nothing.** Nothing is encrypted, uploaded or reported
///    until Send, and the composer's draft is untouched until the send succeeds.
///
/// Ports: `android/.../ui/AttachmentPreview.kt`,
/// `openAttachmentPreview` in `desktop/ui/main.js`.
library;

import 'package:flutter/material.dart';

import '../theme/comrade_theme.dart';
import '../util/attachment_caption.dart';
import 'media_attachment.dart';

/// Show [attachment] and return the caption the sender confirmed, or `null` if
/// they backed out.
///
/// `null` covers Cancel, the ✕, the scrim and the system back gesture alike —
/// all of them mean "don't send this", and none of them should be distinguishable
/// from the caller's side.
Future<String?> showAttachmentPreview(
  BuildContext context, {
  required PickedAttachment attachment,
  required String seedCaption,
}) =>
    showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (BuildContext _) => AttachmentPreviewSheet(
        attachment: attachment,
        seedCaption: seedCaption,
      ),
    );

class AttachmentPreviewSheet extends StatefulWidget {
  const AttachmentPreviewSheet({
    required this.attachment,
    required this.seedCaption,
    super.key,
  });

  final PickedAttachment attachment;

  /// What the caption box opens with — the composer's draft, per
  /// [captionForAttachment], or empty while a reply is pending.
  final String seedCaption;

  @override
  State<AttachmentPreviewSheet> createState() => _AttachmentPreviewSheetState();
}

class _AttachmentPreviewSheetState extends State<AttachmentPreviewSheet> {
  late final TextEditingController _caption =
      TextEditingController(text: widget.seedCaption);

  @override
  void dispose() {
    _caption.dispose();
    super.dispose();
  }

  void _cancel() => Navigator.of(context).pop();

  /// Normalised on the way out, so the sender's own thread and the recipient's
  /// show the same string rather than one trimmed copy and one untrimmed one.
  void _send() => Navigator.of(context).pop(normalizeCaption(_caption.text));

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    final TextTheme text = Theme.of(context).textTheme;
    final PickedAttachment file = widget.attachment;
    // The sheet must not grow into the keyboard: the caption box and Send are
    // the point of it, and a tall photo will happily push both off screen.
    final double keyboard = MediaQuery.viewInsetsOf(context).bottom;

    return Padding(
      key: const Key('attachment-preview'),
      padding: EdgeInsets.only(bottom: keyboard),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            _heading(colors, text, file),
            Flexible(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: _preview(colors, text, file),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
              child: TextField(
                key: const Key('attachment-preview-caption'),
                controller: _caption,
                // Deliberately not autofocused. A keyboard rising over the
                // picture the instant the sheet opens defeats the sheet: the
                // first job is to *see* what was picked, and a seeded caption is
                // often ready to send as-is.
                minLines: 1,
                maxLines: 4,
                maxLength: maxCaptionLength,
                textInputAction: TextInputAction.newline,
                decoration: InputDecoration(
                  hintText: 'Add a caption…',
                  isDense: true,
                  filled: true,
                  fillColor: colors.surfaceContainerHighest,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(ComradeRadii.small),
                    borderSide: BorderSide.none,
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: <Widget>[
                  TextButton(
                    key: const Key('attachment-preview-cancel'),
                    onPressed: _cancel,
                    child: const Text('Cancel'),
                  ),
                  const SizedBox(width: 8),
                  FilledButton.icon(
                    key: const Key('attachment-preview-send'),
                    onPressed: _send,
                    icon: const Icon(Icons.send, size: 18),
                    label: const Text('Send'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _heading(
    ColorScheme colors,
    TextTheme text,
    PickedAttachment file,
  ) =>
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 4),
        child: Row(
          children: <Widget>[
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text(
                    mediaQuoteLabel(mimeType: file.mimeType, caption: ''),
                    style: text.titleMedium,
                  ),
                  Text(
                    // The one place the device-chosen filename belongs: it
                    // answers "is this the file I meant", and it is deliberately
                    // not what gets sent as the caption.
                    attachmentPreviewDetail(
                      name: file.name,
                      bytes: file.bytes.length,
                    ),
                    key: const Key('attachment-preview-detail'),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: text.bodySmall?.copyWith(
                      color: colors.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              key: const Key('attachment-preview-close'),
              tooltip: 'Discard',
              onPressed: _cancel,
              icon: const Icon(Icons.close),
            ),
          ],
        ),
      );

  Widget _preview(ColorScheme colors, TextTheme text, PickedAttachment file) {
    switch (attachmentPreviewKind(file.mimeType)) {
      case AttachmentPreviewKind.image:
        return ClipRRect(
          borderRadius: BorderRadius.circular(ComradeRadii.small),
          child: Image.memory(
            file.bytes,
            key: const Key('attachment-preview-image'),
            fit: BoxFit.contain,
            // Bounded by the shared rule, so a portrait photo cannot occupy the
            // whole sheet and leave the caption box below the fold.
            height: attachmentPreviewMediaHeight(
              MediaQuery.sizeOf(context).height,
            ),
            width: double.infinity,
            // A picker can hand back something that is labelled an image and is
            // not one. Say so rather than showing a broken-image glyph — and
            // still allow the send, because the bytes are the sender's to send
            // and another device may well decode them.
            errorBuilder: (BuildContext _, Object __, StackTrace? ___) => _card(
              colors,
              text,
              file,
              note: 'This device cannot display it, but it can still be sent.',
            ),
          ),
        );
      // No still frame and no player here: Flutter has neither without a
      // plugin, and staging the plaintext to disk to feed one would put an
      // unsent attachment on disk (AUDIT S-4). The card is the honest option.
      case AttachmentPreviewKind.video:
      case AttachmentPreviewKind.audio:
      case AttachmentPreviewKind.file:
        return _card(colors, text, file);
    }
  }

  Widget _card(
    ColorScheme colors,
    TextTheme text,
    PickedAttachment file, {
    String? note,
  }) =>
      Container(
        key: const Key('attachment-preview-card'),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
        decoration: BoxDecoration(
          color: colors.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(ComradeRadii.small),
        ),
        child: Column(
          children: <Widget>[
            Text(
              mediaKindGlyph(file.mimeType),
              style: const TextStyle(fontSize: 40),
            ),
            const SizedBox(height: 8),
            Text(mediaKindLabel(file.mimeType), style: text.bodyMedium),
            if (note != null) ...<Widget>[
              const SizedBox(height: 4),
              Text(
                note,
                textAlign: TextAlign.center,
                style: text.bodySmall?.copyWith(color: colors.onSurfaceVariant),
              ),
            ],
          ],
        ),
      );
}
