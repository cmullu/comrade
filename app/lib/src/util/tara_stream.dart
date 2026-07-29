/// Progressive delivery of Tara's reply, ported from
/// `android/app/src/main/java/mullu/comrade/ui/TaraStream.kt`.
///
/// [chunkText] is the whole rule, kept pure so it is pinned by unit tests:
/// split on whitespace boundaries into roughly word-sized pieces, never
/// mid-word, and never losing or adding a character
/// (`chunkText(t).join() == t`).
///
/// **What is and isn't happening here.** Today's reply comes from the
/// deterministic on-device reflective engine, which computes the whole
/// sentence at once — so this paces out text that already exists. That is a
/// presentation choice, not a token stream, and it is deliberately shaped as a
/// `Stream<String>` of *cumulative* text: when a real generative backend lands
/// (AUDIT §8 OQ9) it emits its tokens into this same shape and the UI does not
/// change.
///
/// Crisis replies never go through here — see `TaraScreen`: helpline numbers
/// appear complete, at once, the moment they are known.
library;

/// Roughly a short word per chunk — fast enough to read, slow enough to feel
/// alive.
const int kTaraDefaultChunkChars = 8;

/// Pause between chunks. ~28 ms lands near a brisk but readable typing speed.
const Duration kTaraDefaultChunkDelay = Duration(milliseconds: 28);

/// Split [text] into successive pieces of at least [targetChars] that each end
/// on whitespace (the final piece takes whatever is left). Concatenating the
/// result reproduces [text] exactly.
List<String> chunkText(String text, {int targetChars = kTaraDefaultChunkChars}) {
  if (targetChars <= 0) {
    throw ArgumentError.value(targetChars, 'targetChars', 'must be positive');
  }
  if (text.isEmpty) return const <String>[];
  final List<String> chunks = <String>[];
  final StringBuffer buffer = StringBuffer();
  var buffered = 0;
  // Iterate runes, not UTF-16 code units, so an emoji is never split in half.
  for (final int rune in text.runes) {
    final String ch = String.fromCharCode(rune);
    buffer.write(ch);
    buffered += ch.length;
    if (buffered >= targetChars && _isWhitespace(rune)) {
      chunks.add(buffer.toString());
      buffer.clear();
      buffered = 0;
    }
  }
  if (buffer.isNotEmpty) chunks.add(buffer.toString());
  return chunks;
}

bool _isWhitespace(int rune) {
  switch (rune) {
    case 0x09: // tab
    case 0x0A: // newline
    case 0x0B:
    case 0x0C:
    case 0x0D: // carriage return
    case 0x20: // space
    case 0x85:
    case 0xA0: // no-break space
    case 0x1680:
    case 0x2028:
    case 0x2029:
    case 0x202F:
    case 0x205F:
    case 0x3000:
      return true;
    default:
      return rune >= 0x2000 && rune <= 0x200A;
  }
}

/// Emit [text] as a growing prefix — the value to render as the reply bubble's
/// contents.
///
/// The first chunk lands immediately (no dead air after the thinking
/// indicator); the rest are paced by [perChunkDelay]. Cancelling the
/// subscription simply stops it mid-sentence.
Stream<String> streamTaraReply(
  String text, {
  Duration perChunkDelay = kTaraDefaultChunkDelay,
  int targetChars = kTaraDefaultChunkChars,
}) async* {
  final StringBuffer soFar = StringBuffer();
  final List<String> pieces = chunkText(text, targetChars: targetChars);
  for (var i = 0; i < pieces.length; i++) {
    if (i > 0 && perChunkDelay > Duration.zero) await Future<void>.delayed(perChunkDelay);
    soFar.write(pieces[i]);
    yield soFar.toString();
  }
}
