package mullu.comrade.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mullu.comrade.ComradeCore
import mullu.comrade.R

/**
 * A journal note somebody chose to share, drawn inside their chat bubble.
 *
 * Three decisions worth keeping:
 *
 * - **It is a header, not a different kind of message.** The bubble keeps its
 *   side, its colour, its ticks and its reply gesture, because that is what it
 *   is: an ordinary DM. What the header adds is the one thing the words alone
 *   cannot say — that these were written somewhere private first, which changes
 *   how they should be read.
 * - **It says whose journal.** "From their journal" on an incoming note and
 *   "From your journal" on your own: the same line on both sides would be
 *   ambiguous exactly where ambiguity is expensive.
 * - **The header is attribution, not proof.** Core reads it off a text marker
 *   any client could write (`comrade_core::note`), so this says what the
 *   sending Comrade claims — the same standing as Tara's label above it.
 *
 * Long notes collapse to [notePreview]'s fold with a "show more" — a journal
 * entry is written to be read alone, and a chat is read in a scroll of other
 * people's messages.
 */
@Composable
fun SharedNoteBody(
    note: ComradeCore.SharedNoteInfo,
    outgoing: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(note.text) { mutableStateOf(false) }
    val preview = remember(note.text) { notePreview(note.text) }
    Column(
        modifier = modifier.testTag("journal-note"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(
                    if (outgoing) R.string.journal_note_from_you else R.string.journal_note_from_them,
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("journal-note-label"),
            )
            note.mood?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        }
        Text(
            if (expanded) note.text else preview.text,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (preview.truncated) {
            // A plain tappable line rather than a button: inside a bubble a
            // Material button reads as an action on the conversation, and this
            // only unfolds text that is already here.
            Text(
                stringResource(
                    if (expanded) R.string.journal_note_show_less else R.string.journal_note_show_more,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("journal-note-expand")
                    .clickable { expanded = !expanded },
            )
        }
    }
}
