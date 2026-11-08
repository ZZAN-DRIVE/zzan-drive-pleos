package com.zzandrive.probe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * Probe: can the Pleos emulator show images / animations / video / 3D at a chosen moment?
 *
 * Trigger from a terminal (this is the "specific moment" stand-in):
 *   adb shell am start --user 10 -n com.zzandrive.probe/.MediaActivity --es kind <kind> [--ez gate false]
 * kind: image | anim | gif | video | webm | glb | codecs | clear
 * gate (default true): only show when the gear is P (spec rule: animations only while parked).
 *
 * Also auto-triggers the "anim" when the battery SOC rises (a charging event).
 */
class MediaActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var stage: FrameLayout
    private var vehicle: Vehicle? = null
    private var vehicleReady = false
    private var lastSoc: Int? = null
    private var pending: Pair<String, Boolean>? = null
    private var webView: WebView? = null
    private var currentPath: String? = null

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(p0: Int?) {
            val prev = lastSoc
            lastSoc = p0
            log("SOC update $prev -> $p0")
            if (prev != null && p0 != null && p0 > prev) {
                log("TRIGGER battery SOC rose (+${p0 - prev}) -> show anim (stand-in for 'savings confirmed')")
                main.post { request("anim", gate = true) }
            }
        }
        override fun onFailed(e: Exception) = log("SOC listener failed: $e")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#2b3a55"), Color.parseColor("#7a8aa6"))
            )
        }
        stage = FrameLayout(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#99000000"))
        }
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        log("== MediaActivity start ==")
        startVehicle()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val kind = i?.getStringExtra("kind") ?: return
        currentPath = i.getStringExtra("path")
        request(kind, i.getBooleanExtra("gate", true))
    }

    // ---------------------------------------------------------------- gating

    private fun startVehicle() {
        val v = Vehicle(applicationContext)
        vehicle = v
        try {
            v.initialize()
            main.postDelayed({
                vehicleReady = true
                try { v.getEvBattery().registerEvBatteryStateOfCharge(socListener) } catch (e: Exception) { log("register SOC failed: $e") }
                pending?.let { (k, g) -> pending = null; request(k, g) }
            }, 2000)
        } catch (e: Exception) {
            log("Vehicle.initialize THREW: $e"); vehicleReady = true
        }
    }

    /** Show [kind] now, unless [gate] is on and the car is not parked. */
    private fun request(kind: String, gate: Boolean) {
        if (!vehicleReady && gate) { pending = kind to gate; return }
        if (!gate || kind == "clear" || kind == "codecs") { show(kind); return }
        try {
            vehicle!!.getGear().getCurrentGearSelectionState(
                { g ->
                    val s = g?.toString() ?: "null"
                    if (s.contains("PARK")) { log("gate OK: gear=$s -> showing '$kind'"); main.post { show(kind) } }
                    else log("BLOCKED '$kind': car is not parked (gear=$s)  <- spec rule R-33")
                },
                { e -> log("gear read failed ($e); showing anyway"); main.post { show(kind) } }
            )
        } catch (e: Exception) {
            log("gate check threw ($e); showing anyway"); show(kind)
        }
    }

    // ---------------------------------------------------------------- media

    private fun clearStage() {
        stage.removeAllViews()
        webView?.let { it.stopLoading(); it.destroy() }
        webView = null
    }

    private fun show(kind: String) {
        val t0 = SystemClock.elapsedRealtime()
        log(">> show '$kind'")
        clearStage()
        try {
            when (kind) {
                "clear" -> log("cleared")
                "codecs" -> logCodecs()
                "matrix" -> runMatrix()
                "file" -> testFile(currentPath ?: "", hold = true) { log("FILE $it") }
                "image" -> {
                    val bmp = assets.open("media/char_static.png").use { BitmapFactory.decodeStream(it) }
                    stage.addView(ImageView(this).apply { setImageBitmap(bmp) }, centered(600, 600))
                    log("image shown: ${bmp.width}x${bmp.height} hasAlpha=${bmp.hasAlpha()} in ${SystemClock.elapsedRealtime() - t0} ms")
                }
                "anim", "gif" -> {
                    val file = if (kind == "anim") "media/char_eating.webp" else "media/char_eating.gif"
                    val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(assets, file))
                    val iv = ImageView(this).apply { setImageDrawable(d) }
                    stage.addView(iv, centered(600, 600))
                    if (d is AnimatedImageDrawable) {
                        d.repeatCount = 2
                        d.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                            override fun onAnimationStart(drawable: Drawable?) = log("$kind animation started (+${SystemClock.elapsedRealtime() - t0} ms)")
                            override fun onAnimationEnd(drawable: Drawable?) { log("$kind animation finished"); main.post { clearStage() } }
                        })
                        d.start()
                    } else log("$kind decoded as a STILL (${d.javaClass.simpleName}) - animation not supported here")
                }
                "video", "webm" -> {
                    val name = if (kind == "video") "sample_h264.mp4" else "sample_vp9.webm"
                    val f = File(cacheDir, name)
                    if (!f.exists()) assets.open("media/$name").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    val vv = VideoView(this)
                    stage.addView(vv, centered(960, 540))
                    vv.setOnPreparedListener { mp: MediaPlayer -> log("$kind prepared: ${mp.videoWidth}x${mp.videoHeight}, ${mp.duration} ms (+${SystemClock.elapsedRealtime() - t0} ms)"); mp.start() }
                    vv.setOnInfoListener { _, what, _ ->
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) log("$kind FIRST FRAME rendered (+${SystemClock.elapsedRealtime() - t0} ms)")
                        true
                    }
                    vv.setOnCompletionListener { log("$kind completed"); main.post { clearStage() } }
                    vv.setOnErrorListener { _, w, e -> log("$kind ERROR what=$w extra=$e"); true }
                    vv.setVideoPath(f.absolutePath)
                }
                "glb" -> {
                    val loader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
                    val wv = WebView(this).apply {
                        settings.javaScriptEnabled = true
                        setBackgroundColor(Color.TRANSPARENT)
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage): Boolean { log("WEB  ${m.message()}"); return true }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(r.url)
                            override fun onPageFinished(v: WebView, url: String) = log("webview page loaded (+${SystemClock.elapsedRealtime() - t0} ms)")
                        }
                    }
                    webView = wv
                    stage.addView(wv, centered(900, 700))
                    log("WebView provider: " + (WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"))
                    wv.loadUrl("https://appassets.androidplatform.net/assets/viewer/viewer.html")
                }
                else -> log("unknown kind '$kind'")
            }
        } catch (e: Throwable) {
            log("show '$kind' FAILED: $e")
        }
    }

    // ---------------------------------------------------------------- format matrix

    private val imageExt = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif", "heic", "heif", "apng")
    private val videoExt = setOf("mp4", "webm", "mkv", "3gp", "mov")
    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()

    /** Runs every image / video / lottie file in assets/media and logs one "MATRIX <file> | OK/FAIL | ..." line each. */
    private fun runMatrix() {
        val files = (assets.list("media") ?: emptyArray())
            .filter { ext(it) in imageExt || ext(it) in videoExt || ext(it) == "json" }.sorted()
        log("MATRIX start: ${files.size} files")
        val results = mutableListOf<String>()
        fun next(i: Int) {
            if (i >= files.size) {
                results.forEach { Log.i("ZzanProbe", "MATRIX $it") }
                log("MATRIX done"); main.post { clearStage() }; return
            }
            testFile("media/${files[i]}", hold = false) { r -> results.add(r); log(r); main.postDelayed({ next(i + 1) }, 300) }
        }
        next(0)
    }

    private fun testFile(path: String, hold: Boolean, done: (String) -> Unit) {
        val name = path.substringAfterLast('/')
        val e = ext(name)
        clearStage()
        when {
            e in imageExt -> testImage(path, name, hold, done)
            e in videoExt -> testVideo(path, name, hold, done)
            e == "json" -> testLottie(path, name, hold, done)
            else -> done("$name | SKIP | unknown extension")
        }
    }

    private fun testImage(path: String, name: String, hold: Boolean, done: (String) -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
        var size = ""; var mime = ""
        try {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(assets, path)) { _, info, _ ->
                size = "${info.size.width}x${info.size.height}"; mime = info.mimeType
            }
            val ms = SystemClock.elapsedRealtime() - t0
            val animated = d is AnimatedImageDrawable
            val alpha = if (animated) "n/a" else try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(assets, path)).hasAlpha().toString()
            } catch (_: Throwable) { "?" }
            stage.addView(ImageView(this).apply { setImageDrawable(d) }, centered(500, 500))
            if (d is AnimatedImageDrawable) { d.repeatCount = if (hold) AnimatedImageDrawable.REPEAT_INFINITE else 0; d.start() }
            val bytes = assets.open(path).use { it.readBytes().size }
            done("$name | OK | $size | $mime | ${if (animated) "animated" else "still"} | hasAlpha=$alpha | decode ${ms} ms | $bytes B")
        } catch (e: Throwable) {
            done("$name | FAIL | ${e.javaClass.simpleName}: ${e.message?.take(90)}")
        }
    }

    private fun testVideo(path: String, name: String, hold: Boolean, done: (String) -> Unit) {
        val f = File(cacheDir, name)
        if (!f.exists()) assets.open(path).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        var meta = ""
        try {
            val r = MediaMetadataRetriever(); r.setDataSource(f.absolutePath)
            meta = "${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)}x${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)}" +
                " ${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)}" +
                " ${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)} ms" +
                " ${(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0) / 1000} kbps | ${f.length()} B"
            r.release()
        } catch (e: Throwable) { meta = "metadata-fail:${e.javaClass.simpleName}" }
        val t0 = SystemClock.elapsedRealtime()
        var prep = -1L
        var finished = false
        val vv = VideoView(this)
        stage.addView(vv, centered(960, 540))
        fun finish(res: String) {
            if (finished) return
            finished = true
            if (!hold) try { vv.stopPlayback() } catch (_: Throwable) {}
            done("$name | $res | $meta")
        }
        vv.setOnPreparedListener { mp -> prep = SystemClock.elapsedRealtime() - t0; if (hold) mp.isLooping = true; mp.start() }
        vv.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) finish("OK | prepared ${prep} ms, first frame ${SystemClock.elapsedRealtime() - t0} ms")
            true
        }
        vv.setOnErrorListener { _, w, e -> finish("FAIL | MediaPlayer error what=$w extra=$e"); true }
        main.postDelayed({ finish("FAIL | timeout 8 s (no first frame)") }, 8000)
        vv.setVideoPath(f.absolutePath)
    }

    private fun testLottie(path: String, name: String, hold: Boolean, done: (String) -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
        var finished = false
        fun finish(res: String) { if (!finished) { finished = true; done("$name | $res") } }
        try {
            val json = assets.open(path).bufferedReader().use { it.readText() }
            val lv = LottieAnimationView(this)
            lv.setFailureListener { e -> finish("FAIL | ${e.javaClass.simpleName}: ${e.message?.take(80)}") }
            lv.addLottieOnCompositionLoadedListener { c ->
                finish("OK | lottie ${c.bounds.width()}x${c.bounds.height()} ${c.duration.toLong()} ms, ${c.frameRate} fps | composition loaded ${SystemClock.elapsedRealtime() - t0} ms | ${json.length} B")
            }
            lv.repeatCount = if (hold) LottieDrawable.INFINITE else 0
            stage.addView(lv, centered(500, 500))
            lv.setAnimationFromJson(json, name)
            lv.playAnimation()
        } catch (e: Throwable) { finish("FAIL | ${e.javaClass.simpleName}: ${e.message?.take(80)}") }
    }

    private fun logCodecs() {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        for (mime in listOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/x-vnd.on2.vp8", "video/av01")) {
            val names = list.filter { ci -> ci.supportedTypes.any { it.equals(mime, true) } }
                .joinToString { it.name + (if (it.isHardwareAccelerated) "(hw)" else "(sw)") }
            log("decoder $mime: ${if (names.isEmpty()) "NONE" else names}")
        }
    }

    private fun centered(w: Int, h: Int) = FrameLayout.LayoutParams(w, h, Gravity.CENTER)

    private fun log(msg: String) {
        Log.i("ZzanProbe", "MEDIA $msg")
        main.post { status.append(msg + "\n") }
    }

    override fun onDestroy() {
        try { vehicle?.getEvBattery()?.unregisterEvBatteryStateOfCharge(socListener); vehicle?.release() } catch (_: Exception) {}
        clearStage()
        super.onDestroy()
    }
}
