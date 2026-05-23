package com.xnotes.presentation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xnotes.canvas.CanvasState
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.ImageCodec
import com.xnotes.platform.PresentationServer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Owns the presentation run state (spec 12 §2): on/off, mode, quality, frame
 * rate. Emits frames on change, throttled to the max FPS — never as constant
 * video when idle. Rendering reads the model on the main thread; JPEG encode and
 * network push happen off-thread.
 */
class PresentationController(
    state: CanvasState,
    private val imageCodec: ImageCodec,
    liveStroke: () -> Pair<Int, Stroke>?,
    private val onStateChanged: () -> Unit,
) {
    private val server = PresentationServer()
    private val frameSource = PresentationFrameSource(state, liveStroke)
    private val handler = Handler(Looper.getMainLooper())
    private val encoder = Executors.newSingleThreadExecutor()
    private val state = state

    var mode: String = "page"
        private set
    var quality: String = "medium"
        private set
    var maxFps: Int = 30
        private set
    var port: Int = 8000
        private set
    var lan: Boolean = false
        private set
    val running: Boolean get() = server.isRunning
    val clientCount: Int get() = server.clientCount

    private var lastFrameAt = 0L
    private var scheduled = false

    init {
        server.onClientCountChanged = { handler.post { onStateChanged() } }
        server.statusJson = { statusJson() }
    }

    /** Start the server; returns an error message on failure, or null on success. */
    fun start(port: Int, lan: Boolean, mode: String, quality: String, maxFps: Int): String? {
        this.port = port
        this.lan = lan
        this.mode = mode
        this.quality = quality
        this.maxFps = maxFps
        val result = server.start(port, lan)
        return if (result.isSuccess) {
            onStateChanged()
            notifyChanged()
            null
        } else {
            "Could not start on port $port (${result.exceptionOrNull()?.message ?: "in use"})."
        }
    }

    fun stop() {
        server.stop()
        onStateChanged()
    }

    fun setMode(mode: String) {
        this.mode = mode
        if (running) notifyChanged()
        onStateChanged()
    }

    /** Request a (throttled) frame; rides the canvas's repaint cadence. */
    fun notifyChanged() {
        if (!running) return
        val interval = (1000L / maxFps).coerceAtLeast(16L)
        val elapsed = SystemClock.uptimeMillis() - lastFrameAt
        if (elapsed >= interval) {
            produceFrame()
        } else if (!scheduled) {
            scheduled = true
            handler.postDelayed({ scheduled = false; produceFrame() }, interval - elapsed)
        }
    }

    private fun produceFrame() {
        if (!running) return
        lastFrameAt = SystemClock.uptimeMillis()
        val cap = longEdge()
        val surface = try {
            if (mode == "follow") frameSource.renderFollow(cap) else frameSource.renderPage(cap)
        } catch (_: Exception) {
            return
        }
        val q = jpegQuality()
        encoder.execute {
            try {
                server.pushFrame(imageCodec.encodeJpeg(surface, q))
            } catch (_: Exception) {
                // drop this frame
            } finally {
                surface.recycle()
            }
        }
    }

    fun url(): String = "http://${host()}:$port/"

    private fun host(): String {
        if (!lan) return "localhost"
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: "0.0.0.0"
    }

    private fun statusJson(): String {
        val title = state.document.title.replace("\"", "\\\"")
        return """{"presenting":$running,"mode":"$mode","page":${state.currentPageIndex()},""" +
            """"page_count":${state.document.pages.size},"title":"$title"}"""
    }

    private fun longEdge(): Int = when (quality) {
        "low" -> 960
        "high" -> 1920
        else -> 1440
    }

    private fun jpegQuality(): Double = when (quality) {
        "low" -> 0.7
        "high" -> 0.85
        else -> 0.8
    }
}
