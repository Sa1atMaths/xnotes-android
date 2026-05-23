package com.xnotes.platform

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A minimal local HTTP server that streams MJPEG frames to browsers (PAL §16 /
 * spec 12 §6). Routes: `/` (viewer), `/stream` (multipart/x-mixed-replace),
 * `/frame` (single JPEG), `/status` (JSON). One-way and read-only.
 */
class PresentationServer {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var currentFrame: ByteArray = ByteArray(0)
    private val clients = CopyOnWriteArrayList<Socket>()

    /** Supplies the `/status` JSON body. */
    @Volatile var statusJson: () -> String = { "{}" }

    /** Notified when the connected-client count changes. */
    @Volatile var onClientCountChanged: (Int) -> Unit = {}

    val clientCount: Int get() = clients.size
    val isRunning: Boolean get() = running

    fun start(port: Int, lan: Boolean): Result<Unit> = try {
        stop()
        val address = InetAddress.getByName(if (lan) "0.0.0.0" else "127.0.0.1")
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(address, port))
        }
        serverSocket = socket
        running = true
        Thread { acceptLoop(socket) }.apply { isDaemon = true; start() }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun stop() {
        running = false
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        onClientCountChanged(0)
    }

    /** Push a new JPEG frame to every connected stream client. */
    fun pushFrame(jpeg: ByteArray) {
        currentFrame = jpeg
        val header = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray()
        for (socket in clients) {
            try {
                val out = socket.getOutputStream()
                synchronized(socket) {
                    out.write(header)
                    out.write(jpeg)
                    out.write(CRLF)
                    out.flush()
                }
            } catch (_: Exception) {
                dropClient(socket)
            }
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            Thread { handle(client) }.apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: run { socket.close(); return }
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val out = socket.getOutputStream()
            when {
                path == "/" -> {
                    writeText(out, "text/html; charset=utf-8", VIEWER_HTML.toByteArray())
                    socket.close()
                }
                path.startsWith("/stream") -> startStream(socket, out)
                path.startsWith("/frame") -> {
                    writeBinary(out, "image/jpeg", currentFrame)
                    socket.close()
                }
                path.startsWith("/status") -> {
                    writeText(out, "application/json", statusJson().toByteArray())
                    socket.close()
                }
                else -> {
                    out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    socket.close()
                }
            }
        } catch (_: Exception) {
            runCatching { socket.close() }
        }
    }

    private fun startStream(socket: Socket, out: java.io.OutputStream) {
        val headers = "HTTP/1.0 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
            "Cache-Control: no-cache\r\nPragma: no-cache\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray())
        if (currentFrame.isNotEmpty()) {
            out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${currentFrame.size}\r\n\r\n".toByteArray())
            out.write(currentFrame)
            out.write(CRLF)
        }
        out.flush()
        clients.add(socket) // kept open; pushFrame writes subsequent parts
        onClientCountChanged(clients.size)
    }

    private fun dropClient(socket: Socket) {
        if (clients.remove(socket)) {
            runCatching { socket.close() }
            onClientCountChanged(clients.size)
        }
    }

    private fun writeText(out: java.io.OutputStream, contentType: String, body: ByteArray) {
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun writeBinary(out: java.io.OutputStream, contentType: String, body: ByteArray) = writeText(out, contentType, body)

    companion object {
        private const val BOUNDARY = "xnoteframe"
        private val CRLF = "\r\n".toByteArray()

        /** Dependency-free viewer: fits the MJPEG stream to the window, auto-reconnects. */
        val VIEWER_HTML = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>xnotes presentation</title>
            <style>
              html,body{margin:0;height:100%;background:#000;overflow:hidden}
              img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain}
              #msg{position:absolute;inset:0;display:none;align-items:center;justify-content:center;
                   color:#888;font-family:monospace;font-size:1.2rem}
            </style></head><body>
            <img id="v" src="/stream" alt="">
            <div id="msg">Presentation ended</div>
            <script>
              var v=document.getElementById('v'),m=document.getElementById('msg');
              v.onerror=function(){m.style.display='flex';setTimeout(function(){v.src='/stream?'+Date.now();m.style.display='none';},2000);};
            </script>
            </body></html>
        """.trimIndent()
    }
}
