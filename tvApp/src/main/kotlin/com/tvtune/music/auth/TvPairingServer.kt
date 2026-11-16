package com.tvtune.music.auth

import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections

object TvQrUtils {
    fun generateQrBitmap(content: String, size: Int = 400): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    }

    fun getDeviceIpAddress(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiIp = wifiManager?.connectionInfo?.ipAddress
            if (wifiIp != null && wifiIp != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    wifiIp and 0xff,
                    wifiIp shr 8 and 0xff,
                    wifiIp shr 16 and 0xff,
                    wifiIp shr 24 and 0xff
                )
            }
        } catch (_: Exception) {}

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress != null && addr.hostAddress!!.indexOf(':') < 0) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

class TvPairingServer(
    private val port: Int = 8888,
    private val scope: CoroutineScope,
    private val onCredentialsReceived: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                Log.i("TvPairingServer", "Pairing server started on port $port")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    launch(Dispatchers.IO) {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w("TvPairingServer", "Server exception: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            var line: String? = reader.readLine() ?: return
            val requestTokens = line!!.split(" ")
            val method = requestTokens.getOrNull(0) ?: "GET"
            val path = requestTokens.getOrNull(1) ?: "/"

            var contentLength = 0
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                if (line!!.lowercase().startsWith("content-length:")) {
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            if (method == "POST" && path.startsWith("/submit")) {
                val charArray = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val count = reader.read(charArray, readTotal, contentLength - readTotal)
                    if (count < 0) break
                    readTotal += count
                }
                val body = String(charArray, 0, readTotal)
                val params = parseFormData(body)
                val cookie = params["cookie"] ?: ""

                if (cookie.isNotBlank()) {
                    onCredentialsReceived(cookie)
                }

                val responseHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <title>Success - Tv-Tune</title>
                        <style>
                            body { background: #0E0E14; color: #FFFFFF; font-family: -apple-system, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; text-align: center; }
                            .card { background: #1B1B26; padding: 32px; border-radius: 16px; border: 1px solid #00E676; max-width: 400px; margin: 16px; }
                            h2 { color: #00E676; margin-top: 0; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <h2>✔ Connected!</h2>
                            <p>Your YouTube Music credentials have been securely sent to Tv-Tune on your TV.</p>
                            <p style="color: #999;">You can close this tab now.</p>
                        </div>
                    </body>
                    </html>
                """.trimIndent()

                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: text/html; charset=utf-8\r\n")
                writer.print("Content-Length: ${responseHtml.toByteArray().size}\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print(responseHtml)
                writer.flush()
            } else {
                val formHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <title>Tv-Tune TV Pairing</title>
                        <style>
                            body { background: #0E0E14; color: #FFFFFF; font-family: -apple-system, sans-serif; display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; }
                            .card { background: #1B1B26; padding: 28px; border-radius: 16px; border: 1px solid #333345; max-width: 500px; width: 90%; box-sizing: border-box; }
                            h2 { color: #00E676; margin-top: 0; font-size: 22px; }
                            p { color: #A0A0B0; font-size: 14px; line-height: 1.5; }
                            textarea { width: 100%; box-sizing: border-box; height: 120px; background: #262636; border: 1px solid #444455; border-radius: 8px; color: white; padding: 12px; font-family: monospace; font-size: 13px; resize: vertical; }
                            textarea:focus { outline: none; border-color: #00E676; }
                            button { width: 100%; background: #00E676; color: #000000; font-weight: bold; border: none; padding: 14px; border-radius: 8px; font-size: 16px; cursor: pointer; margin-top: 16px; }
                            button:hover { background: #00C853; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <h2>📺 Tv-Tune Pairing</h2>
                            <p>Paste your YouTube Music login cookies, SAPISID, or InnerTube token below to sign in on your TV:</p>
                            <form method="POST" action="/submit">
                                <textarea name="cookie" placeholder="Paste SAPISID=... or full cookie header here..." required></textarea>
                                <button type="submit">Sign In to TV</button>
                            </form>
                        </div>
                    </body>
                    </html>
                """.trimIndent()

                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: text/html; charset=utf-8\r\n")
                writer.print("Content-Length: ${formHtml.toByteArray().size}\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print(formHtml)
                writer.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun parseFormData(body: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pairs = body.split("&")
        for (pair in pairs) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                val key = URLDecoder.decode(parts[0], "UTF-8")
                val value = URLDecoder.decode(parts[1], "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverJob?.cancel()
        serverSocket = null
        serverJob = null
    }
}
