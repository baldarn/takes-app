package app.takes

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val ORIGINAL = "https://github.com/antirez/YouRecApp"
private const val PRIVACY = "https://github.com/baldarn/takes-app/blob/main/PRIVACY.md"

/**
 * Where the app comes from and the licenses of everything shipped in it. The
 * texts are bundled: the app has no internet access, links open in a browser.
 */
@Composable
fun CreditsScreen(report: (String) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        listOf(R.raw.credits, R.raw.rnnoise_notices, R.raw.apache_2_0).joinToString("\n") { id ->
            context.resources.openRawResource(id).bufferedReader().use { it.readText() }
        }
    }
    val open = { address: String ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
        } catch (_: Exception) {
            report("No app on this device can open $address")
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Credits and licenses", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = close) { Text("Done") }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                TextButton(onClick = { open(ORIGINAL) }) { Text("YouRec, the original app") }
                TextButton(onClick = { open(PRIVACY) }) { Text("Privacy policy") }
                Text(text, color = Color(0xFFD0D3D8), fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}
