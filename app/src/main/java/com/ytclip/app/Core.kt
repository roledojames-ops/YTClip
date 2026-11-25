package com.ytclip.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

enum class Mode { VIDEO, AUDIO, TRANSCRIPT }

data class ClipJob(
    val url: String,
    val startSec: Long,
    val endSec: Long?,            // null = until the end of the video
    val mode: Mode,
    val videoHeight: Int,         // 0 = best
    val audioFormat: String,      // mp3 | m4a
    val audioQuality: String,     // 0 | 192K | 128K | 64K
    val subLang: String,
    val timestamps: Boolean,
    val exactCut: Boolean,
    val extraArgs: String,
    val cookies: String
)

data class UiState(
    val running: Boolean = false,
    val progress: Float = -1f,    // < 0 = unknown
    val status: String = "",
    val log: List<String> = emptyList(),
    val error: String? = null,
    val resultUri: String? = null,
    val resultName: String? = null,
    val resultMime: String? = null
)

object ClipBus {
    val state = MutableStateFlow(UiState())
    val engine = MutableStateFlow("Starting engine…")
    @Volatile var pending: ClipJob? = null
    @Volatile var processId: String? = null
}

object Engine {
    private val lock = Any()
    @Volatile private var ready = false

    fun ensure(ctx: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            val app = ctx.applicationContext
            ClipBus.engine.value = "Preparing engine… (first launch takes a moment)"
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
            } catch (e: Exception) {
                ClipBus.engine.value = "Engine failed to start: " + (e.message ?: e.toString())
                throw e
            }
            ClipBus.engine.value = "Updating yt-dlp…"
            try {
                YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            } catch (e: Exception) {
                // offline or rate-limited: keep the bundled yt-dlp
            }
            ready = true
            ClipBus.engine.value = "ready"
        }
    }

    fun update(ctx: Context): String {
        return try {
            ensure(ctx)
            val st = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            "yt-dlp update: " + (st?.toString() ?: "already up to date")
        } catch (e: Exception) {
            "Update failed: " + (e.message ?: e.toString())
        }
    }
}

fun hms(t: Long): String {
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}

fun tagOf(t: Long): String =
    String.format(Locale.ROOT, "%dh%02dm%02ds", t / 3600, (t % 3600) / 60, t % 60)

fun extractUrl(text: String): String? =
    Regex("""https?://(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)/\S+""", RegexOption.IGNORE_CASE)
        .find(text)?.value

object Vtt {
    private val TS = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})\.(\d{3})\s*-->""")
    private val TAGS = Regex("<[^>]+>")

    private fun decode(s: String): String =
        s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")

    /** Keeps only cues that START inside [from, to) and removes rolling-caption repeats. */
    fun parse(src: String, from: Double, to: Double?, stamps: Boolean): String {
        val lines = src.lines().map { it.trim() }
        val items = ArrayList<Pair<Double, String>>()
        var keep = false
        var cur = 0.0
        var prev = ""
        for (i in lines.indices) {
            val line = lines[i]
            val m = TS.find(line)
            if (m != null) {
                val h = m.groupValues[1].toLongOrNull() ?: 0L
                cur = h * 3600.0 + m.groupValues[2].toLong() * 60 +
                    m.groupValues[3].toLong() + m.groupValues[4].toLong() / 1000.0
                keep = cur >= from && (to == null || cur < to)
                continue
            }
            if (!keep || line.isEmpty()) continue
            if (line.startsWith("WEBVTT") || line.startsWith("Kind:") ||
                line.startsWith("Language:") || line.startsWith("NOTE")
            ) continue
            // cue identifier line (a bare number right before a timestamp line)
            if (line.all { it.isDigit() } && i + 1 < lines.size && TS.containsMatchIn(lines[i + 1])) continue
            val t = decode(TAGS.replace(line, "")).trim()
            if (t.isNotEmpty() && t != prev) {
                items.add(cur to t)
                prev = t
            }
        }
        return if (stamps) items.joinToString("\n") { "[" + hms(it.first.toLong()) + "] " + it.second }
        else items.joinToString(" ") { it.second }
    }
}
