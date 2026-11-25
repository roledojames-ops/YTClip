package com.ytclip.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Coral = Color(0xFFFF3D5A)
private val Violet = Color(0xFF8B5CF6)

private val Scheme = darkColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    secondary = Violet,
    background = Color(0xFF0E0E13),
    surface = Color(0xFF0E0E13),
    surfaceVariant = Color(0xFF181821),
    onSurface = Color(0xFFEDEDF3),
    onSurfaceVariant = Color(0xFFA8A8B8),
    outline = Color(0xFF3A3A4A),
    secondaryContainer = Color(0xFF3B2A73),
    onSecondaryContainer = Color.White,
    error = Color(0xFFFF7B7B)
)

private val VQ = listOf("Best" to 0, "1080p" to 1080, "720p" to 720, "480p" to 480, "360p" to 360)
private val AQ = listOf("Best" to "0", "192k" to "192K", "128k" to "128K", "64k" to "64K")

private fun secs(h: String, m: String, s: String): Long =
    (h.toLongOrNull() ?: 0L) * 3600 + (m.toLongOrNull() ?: 0L) * 60 + (s.toLongOrNull() ?: 0L)

class MainActivity : ComponentActivity() {

    private val sharedUrl = mutableStateOf<String?>(null)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = Scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(sharedUrl)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val text = when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        } ?: return
        extractUrl(text)?.let { sharedUrl.value = it }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(o) })
        }
    }
}

@Composable
private fun TimeBox(value: String, unit: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(2)) },
        modifier = modifier,
        singleLine = true,
        label = { Text(unit) },
        placeholder = { Text("0") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
    )
}

@Composable
private fun TimeRow(
    label: String, h: String, m: String, s: String,
    onH: (String) -> Unit, onM: (String) -> Unit, onS: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeBox(h, "h", Modifier.weight(1f), onH)
            TimeBox(m, "m", Modifier.weight(1f), onM)
            TimeBox(s, "s", Modifier.weight(1f), onS)
        }
    }
}

@Composable
private fun MainScreen(shared: MutableState<String?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val prefs = remember { ctx.getSharedPreferences("ytclip", Context.MODE_PRIVATE) }
    val ui by ClipBus.state.collectAsState()
    val engine by ClipBus.engine.collectAsState()

    var url by rememberSaveable { mutableStateOf("") }
    var sh by rememberSaveable { mutableStateOf("") }
    var sm by rememberSaveable { mutableStateOf("") }
    var ss by rememberSaveable { mutableStateOf("") }
    var eh by rememberSaveable { mutableStateOf("") }
    var em by rememberSaveable { mutableStateOf("") }
    var es by rememberSaveable { mutableStateOf("") }

    var mode by rememberSaveable { mutableStateOf(prefs.getInt("mode", 0)) }
    var vq by rememberSaveable { mutableStateOf(prefs.getInt("vq", 0)) }
    var af by rememberSaveable { mutableStateOf(prefs.getInt("af", 0)) }
    var aq by rememberSaveable { mutableStateOf(prefs.getInt("aq", 0)) }
    var lang by rememberSaveable { mutableStateOf(prefs.getString("lang", "en") ?: "en") }
    var stamps by rememberSaveable { mutableStateOf(prefs.getBoolean("stamps", false)) }
    var exact by rememberSaveable { mutableStateOf(prefs.getBoolean("exact", false)) }
    var extra by rememberSaveable { mutableStateOf(prefs.getString("extra", "") ?: "") }
    var cookies by rememberSaveable { mutableStateOf(prefs.getString("cookies", "") ?: "") }

    var showAdv by rememberSaveable { mutableStateOf(false) }
    var formErr by remember { mutableStateOf<String?>(null) }
    var updMsg by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }

    LaunchedEffect(shared.value) {
        shared.value?.let {
            url = it
            shared.value = null
        }
    }

    fun submit() {
        formErr = null
        val link = extractUrl(url)
        val s = secs(sh, sm, ss)
        val endBlank = eh.isBlank() && em.isBlank() && es.isBlank()
        val e: Long? = if (endBlank) null else secs(eh, em, es)
        if (link == null) {
            formErr = "Paste a YouTube link first."
            return
        }
        if (e != null && e <= s) {
            formErr = "End time must be after the start time."
            return
        }
        prefs.edit()
            .putInt("mode", mode).putInt("vq", vq).putInt("af", af).putInt("aq", aq)
            .putString("lang", lang).putBoolean("stamps", stamps).putBoolean("exact", exact)
            .putString("extra", extra).putString("cookies", cookies)
            .apply()
        ClipBus.pending = ClipJob(
            url = link,
            startSec = s,
            endSec = e,
            mode = Mode.values()[mode],
            videoHeight = VQ[vq].second,
            audioFormat = if (af == 0) "mp3" else "m4a",
            audioQuality = AQ[aq].second,
            subLang = lang.trim().ifEmpty { "en" },
            timestamps = stamps,
            exactCut = exact,
            extraArgs = extra,
            cookies = cookies
        )
        ClipBus.state.value = UiState(running = true, status = "Starting…")
        ctx.startForegroundService(Intent(ctx, ClipService::class.java))
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text(
                "YT Clip",
                style = MaterialTheme.typography.headlineLarge.copy(
                    brush = Brush.linearGradient(listOf(Coral, Violet)),
                    fontWeight = FontWeight.ExtraBold
                )
            )
            Text("Save just the part you need", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("YouTube link") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://youtu.be/…") },
                trailingIcon = {
                    TextButton(onClick = {
                        val t = clipboard.getText()?.text
                        if (t != null) url = extractUrl(t) ?: t
                    }) { Text("Paste") }
                }
            )
        }

        Section("Section to save") {
            TimeRow("Start", sh, sm, ss, { sh = it }, { sm = it }, { ss = it })
            TimeRow("End  (empty = until the end)", eh, em, es, { eh = it }, { em = it }, { es = it })
        }

        Section("Save as") {
            ChipRow(listOf("Video", "Audio", "Transcript"), mode) { mode = it }
            when (mode) {
                0 -> {
                    Text("Video quality", style = MaterialTheme.typography.labelLarge)
                    ChipRow(VQ.map { it.first }, vq) { vq = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = exact, onCheckedChange = { exact = it })
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Exact cut")
                            Text(
                                "Slower, frame-accurate. Off = may start a few seconds early.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                1 -> {
                    Text("Format", style = MaterialTheme.typography.labelLarge)
                    ChipRow(listOf("MP3", "M4A"), af) { af = it }
                    if (af == 0) {
                        Text("Audio quality", style = MaterialTheme.typography.labelLarge)
                        ChipRow(AQ.map { it.first }, aq) { aq = it }
                    } else {
                        Text(
                            "M4A keeps YouTube's original AAC audio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    OutlinedTextField(
                        value = lang,
                        onValueChange = { lang = it.take(8) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Caption language code (en, fa, ar, de…)") }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = stamps, onCheckedChange = { stamps = it })
                        Spacer(Modifier.width(10.dp))
                        Text("Timestamp on every line")
                    }
                }
            }
        }

        if (formErr != null) {
            Text(formErr ?: "", color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = { submit() },
            enabled = !ui.running,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                when (mode) {
                    0 -> "Save video clip"
                    1 -> "Save audio clip"
                    else -> "Save transcript"
                },
                fontWeight = FontWeight.Bold
            )
        }

        if (engine != "ready" && !ui.running) {
            Text(engine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (ui.running) {
            Section("Working") {
                Text(ui.status)
                if (ui.progress >= 0f) {
                    LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = {
                    ctx.startService(Intent(ctx, ClipService::class.java).setAction(ClipService.ACTION_CANCEL))
                }) { Text("Cancel") }
            }
        } else if (ui.status == "Cancelled") {
            Text("Cancelled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (ui.error != null) {
            Section("Something went wrong") {
                Text(ui.error ?: "", color = MaterialTheme.colorScheme.error)
            }
        }

        if (ui.resultUri != null && !ui.running) {
            Section("Saved ✔") {
                Text("Download/YTClip/" + (ui.resultName ?: ""))
                Button(onClick = {
                    val i = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse(ui.resultUri), ui.resultMime ?: "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        ctx.startActivity(i)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(ctx, "No app can open this file.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Open") }
            }
        }

        if (ui.log.isNotEmpty() && (ui.running || ui.error != null)) {
            Text(
                ui.log.takeLast(6).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 8,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TextButton(onClick = { showAdv = !showAdv }) {
            Text(if (showAdv) "Advanced ▲" else "Advanced ▼")
        }
        if (showAdv) {
            Section("Advanced") {
                OutlinedButton(
                    onClick = {
                        updating = true
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) { Engine.update(ctx) }
                            updMsg = msg
                            updating = false
                        }
                    },
                    enabled = !updating && !ui.running
                ) { Text(if (updating) "Updating…" else "Update yt-dlp") }
                if (updMsg != null) {
                    Text(updMsg ?: "", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = extra,
                    onValueChange = { extra = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Extra yt-dlp options") },
                    placeholder = { Text("--extractor-args youtube:player_client=tv") }
                )
                OutlinedTextField(
                    value = cookies,
                    onValueChange = { cookies = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    label = { Text("cookies.txt contents (only if YouTube asks you to sign in)") }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
