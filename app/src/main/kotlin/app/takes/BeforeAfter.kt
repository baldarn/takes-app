package app.takes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Before and After at the end of a tool's row, as the original's tool preview
 * (YouRec/EditorTools.swift:1266-1272): Before shows the clips without the
 * tool's setting, After with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BeforeAfter(original: Boolean, choose: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            for ((index, before) in listOf(true, false).withIndex()) {
                SegmentedButton(
                    selected = original == before, onClick = { choose(before) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(stringResource(if (before) R.string.pauses_before else R.string.pauses_after)) }
            }
        }
    }
}
