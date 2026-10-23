package com.cizeylem.sample.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cizeylem.bir.Cizeylem
import com.cizeylem.bir.Consent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Uygulama şemasıyla açıldıysa (adb shell am start -d cizeylemsample://x?utm_source=a) link işlenir.
        intent?.data?.let { Cizeylem.handleDeepLink(it.toString()) }
        setContent { MaterialTheme { Screen() } }
    }
}

@androidx.compose.runtime.Composable
private fun Screen() {
    var analytics by remember { mutableStateOf(false) }
    var attribution by remember { mutableStateOf(false) }
    var adSharing by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(Cizeylem.stats().toString()) }
    fun apply() = Cizeylem.setConsent(Consent(analytics, attribution, adSharing))

    Column(
        Modifier.statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Rıza", style = MaterialTheme.typography.titleLarge)
        Toggle("analytics", analytics) { analytics = it; apply() }
        Toggle("attribution", attribution) { attribution = it; apply() }
        Toggle("adSharing", adSharing) { adSharing = it; apply() }

        Text("Event", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { Cizeylem.track("ornek_dugme", mapOf("n" to 1)) }) { Text("track") }
        Button(onClick = { Cizeylem.identify("ornek-kullanici") }) { Text("identify") }
        Button(onClick = { Cizeylem.flush() }) { Text("flush") }
        Button(onClick = { Cizeylem.reset() }) { Text("reset") }
        Button(onClick = {
            Cizeylem.handleDeepLink("cizeylemsample://ac?utm_source=ornek&utm_campaign=deneme&click_id=c1")
        }) { Text("deep link dene") }

        Text("Durum", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { stats = Cizeylem.stats().toString() }) { Text("stats yenile") }
        Text(stats)
        Text("distinct_id: ${Cizeylem.getDistinctId() ?: "yok"}")
    }
}

@androidx.compose.runtime.Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
