package com.ytclip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import java.io.File

class ClipService : Service() {

    companion object {
        const val ACTION_CANCEL = "com.ytclip.app.CANCEL"
        private const val CH = "clip"
        private const val NID = 7
        private val TIME_RE = Regex("""time=(\d+):(\d+):(\d+)""")
    }

    private class Saved(val uri: Uri, val name: String, val mime: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var cancelled = false
    private var lastPct = -2

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Clip jobs", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            doCancel()
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        val clip = ClipBus.pending
        if (clip == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ClipBus.pending = null
        startForeground(NID, buildNotif("Starting…", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytclip:job")
            .apply { acquire(2 * 60 * 60 * 1000L) }
        worker = scope.launch {
            try {
                runJob(clip)
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
                worker = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun doCancel() {
        cancelled = true
        ClipBus.processId?.let { id ->
            try { YoutubeDL.getInstance().destroyProcessById(id) } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------------
    private fun runJob(c: ClipJob) {
        cancelled = false
        lastPct = -2
        ClipBus.state.value = UiState(running = true, status = "Preparing…")
        val work = File(cacheDir, "job")
        work.deleteRecursively()
        work.mkdirs()
        try {
            Engine.ensure(this)
            setStatus("Contacting YouTube…")
            val pid = "clip-" + System.currentTimeMillis()
            ClipBus.processId = pid
            val req = buildRequest(c, work)
            val total: Double? =
                if (c.mode != Mode.TRANSCRIPT && c.endSec != null) (c.endSec - c.startSec).toDouble() else null

            YoutubeDL.getInstance().execute(req, pid) { p, eta, line ->
                onProgress(p, eta, line, total)
            }
            ClipBus.processId = null

            setStatus("Saving to Downloads…")
            val tag = tagOf(c.startSec) + "-" + (if (c.endSec != null) tagOf(c.endSec) else "end")
            val saved = when (c.mode) {
                Mode.TRANSCRIPT -> saveTranscript(c, work, tag)
                else -> saveMedia(c, work, tag)
            }
            ClipBus.state.update {
                it.copy(
                    running = false, progress = 1f, status = "Saved ✔", error = null,
                    resultUri = saved.uri.toString(), resultName = saved.name, resultMime = saved.mime
                )
            }
            notifyDone("Saved: " + saved.name)
        } catch (e: Exception) {
            ClipBus.processId = null
            if (cancelled) {
                ClipBus.state.update { it.copy(running = false, status = "Cancelled", error = null) }
            } else {
                ClipBus.state.update { it.copy(running = false, status = "Failed", error = friendly(e)) }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun buildRequest(c: ClipJob, work: File): YoutubeDLRequest {
        val r = YoutubeDLRequest(c.url)
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("-o", work.absolutePath + "/%(title).80s.%(ext)s")

        val extra = c.extraArgs.trim()
        if (!extra.contains("extractor-args")) {
            // android_vr client does not need a JS runtime / PO token for most videos
            r.addOption("--extractor-args", "youtube:player_client=default,android_vr")
        }
        if (c.cookies.isNotBlank()) {
            val cf = File(filesDir, "cookies.txt")
            cf.writeText(c.cookies.trim() + "\n")
            r.addOption("--cookies", cf.absolutePath)
        }

        val wantsSection = c.mode != Mode.TRANSCRIPT && (c.startSec > 0 || c.endSec != null)
        if (wantsSection) {
            r.addOption("--download-sections", "*" + c.startSec + "-" + (c.endSec?.toString() ?: "inf"))
        }

        when (c.mode) {
            Mode.VIDEO -> {
                val h = c.videoHeight
                val fmt = if (h == 0) "bv*+ba/b" else "bv*[height<=$h]+ba/b[height<=$h]/b"
                r.addOption("-f", fmt)
                r.addOption("-S", "vcodec:h264,res,acodec:m4a")   // h264 = plays everywhere
                r.addOption("--merge-output-format", "mp4")
                if (c.exactCut && wantsSection) r.addOption("--force-keyframes-at-cuts")
            }
            Mode.AUDIO -> {
                r.addOption("-f", "ba[ext=m4a]/ba/b")
                r.addOption("-x")
                r.addOption("--audio-format", c.audioFormat)
                if (c.audioFormat == "mp3") r.addOption("--audio-quality", c.audioQuality)
                if (c.exactCut && wantsSection) r.addOption("--force-keyframes-at-cuts")
            }
            Mode.TRANSCRIPT -> {
                val l = c.subLang.trim().ifEmpty { "en" }
                r.addOption("--skip-download")
                r.addOption("--write-subs")
                r.addOption("--write-auto-subs")
                r.addOption("--sub-langs", "$l.*,$l")
                r.addOption("--sub-format", "vtt")
            }
        }
        if (extra.isNotEmpty()) {
            extra.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { r.addOption(it) }
        }
        return r
    }

    private fun onProgress(p: Float, eta: Long, line: String, total: Double?) {
        var pr = if (p >= 0f) p / 100f else -1f
        if (pr < 0f && total != null && total > 0) {
            val m = TIME_RE.findAll(line).lastOrNull()
            if (m != null) {
                val sec = m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()
                pr = (sec / total).toFloat().coerceIn(0f, 0.99f)
            }
        }
        val clean = line.trim()
        ClipBus.state.update { s ->
            var status = s.status
            if (pr >= 0f) {
                status = "Working… " + (pr * 100).toInt() + "%"
                if (p >= 0f && eta > 0) status += "  ·  " + eta + "s left"
            }
            s.copy(
                progress = if (pr >= 0f) pr else s.progress,
                status = status,
                log = if (clean.isNotEmpty()) (s.log + clean.take(200)).takeLast(30) else s.log
            )
        }
        if (pr >= 0f) {
            val pct = (pr * 100).toInt()
            if (pct != lastPct) {
                lastPct = pct
                getSystemService(NotificationManager::class.java)
                    .notify(NID, buildNotif("Working… $pct%", pct))
            }
        }
    }

    private fun setStatus(t: String) {
        ClipBus.state.update { it.copy(status = t) }
        getSystemService(NotificationManager::class.java).notify(NID, buildNotif(t, -1))
    }

    // ------------------------------------------------------------------
    private fun saveMedia(c: ClipJob, work: File, tag: String): Saved {
        val f = work.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("yt-dlp finished but produced no file.")
        val ext = f.extension.lowercase()
        val mime = when (ext) {
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
        val name = safe(f.nameWithoutExtension) + "_" + tag + "." + ext
        return Saved(copyToDownloads(f, name, mime), name, mime)
    }

    private fun saveTranscript(c: ClipJob, work: File, tag: String): Saved {
        val lang = c.subLang.trim().ifEmpty { "en" }
        val vtts = work.listFiles()?.filter { it.name.endsWith(".vtt") }.orEmpty()
        if (vtts.isEmpty()) {
            throw IllegalStateException(
                "No captions found for this video in language \"" + lang +
                    "\". Try another language code, or the video has no captions."
            )
        }
        val pick = vtts.firstOrNull { it.name.endsWith(".$lang.vtt") } ?: vtts.minByOrNull { it.name.length }!!
        val text = Vtt.parse(
            pick.readText(), c.startSec.toDouble(), c.endSec?.toDouble(), c.timestamps
        )
        if (text.isBlank()) throw IllegalStateException("The captions contain no text between these times.")
        val stem = pick.name.removeSuffix(".vtt").substringBeforeLast('.')
        val out = File(work, "transcript_out.txt")
        out.writeText(text + "\n")
        val name = safe(stem) + "_" + tag + ".txt"
        return Saved(copyToDownloads(out, name, "text/plain"), name, "text/plain")
    }

    private fun safe(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(80).ifEmpty { "clip" }

    private fun copyToDownloads(file: File, displayName: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/YTClip")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create a file in Downloads.")
        resolver.openOutputStream(uri)!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun friendly(e: Exception): String {
        val raw = e.message ?: e.toString()
        val errLine = raw.lines().lastOrNull { it.contains("ERROR", true) }
            ?: raw.lines().lastOrNull { it.isNotBlank() }
            ?: raw
        var msg = errLine.trim().take(400)
        val low = raw.lowercase()
        if (low.contains("sign in to confirm") || low.contains("not a bot")) {
            msg += "\n\nYouTube wants a signed-in session. Open Advanced and paste a cookies.txt, then try again."
        } else if (low.contains("javascript runtime") || low.contains("requested format is not available") ||
            low.contains("http error 403")
        ) {
            msg += "\n\nTry Advanced → Update yt-dlp, then try again."
        }
        return msg
    }

    // ------------------------------------------------------------------
    private fun buildNotif(text: String, pct: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, ClipService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("YT Clip")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat), "Cancel", cancel
                ).build()
            )
        if (pct in 0..100) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notifyDone(text: String) {
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("YT Clip")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .build()
        getSystemService(NotificationManager::class.java).notify(NID + 1, n)
    }
}
