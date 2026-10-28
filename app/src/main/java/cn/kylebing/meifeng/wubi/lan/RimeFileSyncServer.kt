package cn.kylebing.meifeng.wubi.lan

import cn.kylebing.meifeng.wubi.data.AppPaths
import cn.kylebing.meifeng.wubi.data.RimeConfigDeploy
import cn.kylebing.meifeng.wubi.data.RimeFileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主 App 前台本地 HTTP：电脑浏览器管理 Rime 配置目录。
 * 路由与 iOS RimeFileSyncServer 一致，端口默认 8765。
 */
class RimeFileSyncServer(
    val port: Int = 8765,
    private val onState: (running: Boolean, ips: List<String>, error: String?) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val maxBodyBytes = 96 * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())

    private fun emit(runningNow: Boolean, error: String?) {
        val ips = ipv4Addresses()
        main.post { onState(runningNow, ips, error) }
    }

    fun start() {
        stop()
        try {
            val sock = ServerSocket(port)
            sock.reuseAddress = true
            server = sock
            running.set(true)
            emit(true, null)
            pool.execute {
                while (running.get()) {
                    try {
                        val client = sock.accept()
                        pool.execute { handle(client) }
                    } catch (_: Exception) {
                        if (running.get()) emit(false, "监听已断开")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            running.set(false)
            emit(false, e.message)
        }
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        emit(false, null)
    }

    fun refreshAddresses() {
        emit(running.get(), null)
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val headerBuf = ByteArrayOutputStream()
            val crlf = byteArrayOf(13, 10, 13, 10)
            val one = ByteArray(1)
            while (true) {
                val n = input.read(one)
                if (n < 0) return
                headerBuf.write(one[0].toInt())
                val arr = headerBuf.toByteArray()
                if (arr.size >= 4 && endsWith(arr, crlf)) break
                if (arr.size > 64 * 1024) {
                    respondJson(socket.getOutputStream(), 400, json("ok" to false, "error" to "bad request"))
                    return
                }
            }
            val headerText = headerBuf.toByteArray().toString(StandardCharsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull() ?: ""
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respondJson(socket.getOutputStream(), 400, json("ok" to false, "error" to "bad request"))
                return
            }
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val headers = mutableMapOf<String, String>()
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > maxBodyBytes) {
                respondJson(
                    socket.getOutputStream(),
                    413,
                    json("ok" to false, "error" to "单次上传过大（>${maxBodyBytes / 1024 / 1024}MB），请分批或缩小目录")
                )
                return
            }
            val body = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)
            dispatch(socket.getOutputStream(), method, rawPath, headers, body)
        } catch (e: Exception) {
            try {
                respondJson(socket.getOutputStream(), 500, json("ok" to false, "error" to (e.message ?: "error")))
            } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun dispatch(
        out: OutputStream,
        method: String,
        rawPath: String,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        if (method == "OPTIONS") {
            respond(out, 204, ByteArray(0), "text/plain")
            return
        }
        val (path, query) = splitPathQuery(rawPath)
        try {
            when (method to path) {
                "GET" to "/", "GET" to "/index.html" -> {
                    val html = AppPaths.context.assets.open("lan/index.html").bufferedReader().readText()
                    respond(out, 200, html.toByteArray(StandardCharsets.UTF_8), "text/html; charset=utf-8")
                }
                "GET" to "/api/info" -> {
                    val obj = JSONObject()
                        .put("ok", true)
                        .put("root", RimeFileStore.root.path)
                        .put("count", RimeFileStore.listAll().size)
                        .put("ips", JSONArray(ipv4Addresses()))
                    respond(out, 200, obj.toString().toByteArray(), "application/json; charset=utf-8")
                }
                "GET" to "/api/files" -> {
                    val files = JSONArray()
                    RimeFileStore.listAll().forEach { e ->
                        files.put(
                            JSONObject()
                                .put("path", e.path)
                                .put("size", e.size)
                                .put("isDirectory", e.isDirectory)
                                .put("mtime", e.mtime)
                        )
                    }
                    respond(
                        out, 200,
                        JSONObject().put("ok", true).put("files", files).toString().toByteArray(),
                        "application/json; charset=utf-8"
                    )
                }
                "GET" to "/api/download" -> {
                    val rel = query["path"].orEmpty()
                    if (rel.isEmpty()) {
                        respondJson(out, 400, json("ok" to false, "error" to "missing path"))
                        return
                    }
                    val (data, name) = RimeFileStore.readFile(rel)
                    respond(
                        out, 200, data, "application/octet-stream",
                        mapOf("Content-Disposition" to "attachment; filename=\"$name\"")
                    )
                }
                "POST" to "/api/delete" -> {
                    val paths = jsonStringArray(body, "paths")
                    val n = RimeFileStore.delete(paths)
                    respondJson(out, 200, json("ok" to true, "deleted" to n))
                }
                "POST" to "/api/clear" -> {
                    val n = RimeFileStore.clearAll()
                    respondJson(out, 200, json("ok" to true, "cleared" to n))
                }
                "POST" to "/api/restore-bundled" -> {
                    val result = RimeConfigDeploy.resetToFactory()
                    if (result.isSuccess) {
                        respondJson(out, 200, json("ok" to true, "message" to "reset to factory and deployed"))
                    } else {
                        respondJson(out, 500, json("ok" to false, "error" to (result.exceptionOrNull()?.message ?: "失败")))
                    }
                }
                "POST" to "/api/deploy" -> {
                    val result = RimeConfigDeploy.deployNow()
                    if (result.isSuccess) {
                        respondJson(out, 200, json("ok" to true, "message" to "deployed"))
                    } else {
                        respondJson(out, 500, json("ok" to false, "error" to (result.exceptionOrNull()?.message ?: "失败")))
                    }
                }
                "POST" to "/api/upload" -> {
                    val ctype = headers["content-type"].orEmpty()
                    val boundary = multipartBoundary(ctype)
                    if (boundary == null) {
                        respondJson(out, 400, json("ok" to false, "error" to "expect multipart"))
                        return
                    }
                    val parts = parseMultipart(body, boundary)
                    var saved = 0
                    var dirs = 0
                    val relpaths = mutableListOf<String>()
                    val fileParts = mutableListOf<MultipartPart>()
                    for (part in parts) {
                        when (part.name) {
                            "dirs" -> {
                                val trimmed = part.asText().trim()
                                if (trimmed.isNotEmpty()) {
                                    RimeFileStore.makeDirectory(trimmed)
                                    dirs++
                                }
                            }
                            "relpaths", "paths" -> {
                                val trimmed = part.asText().trim()
                                if (trimmed.isNotEmpty()) relpaths.add(trimmed)
                            }
                            "files", "file" -> fileParts.add(part)
                            else -> {
                                val filename = part.filename
                                if (!filename.isNullOrEmpty()) {
                                    RimeFileStore.writeUpload(filename, part.data)
                                    saved++
                                }
                            }
                        }
                    }
                    fileParts.forEachIndexed { idx, part ->
                        val path = when {
                            idx < relpaths.size -> relpaths[idx]
                            !part.filename.isNullOrEmpty() -> part.filename!!
                            else -> return@forEachIndexed
                        }
                        RimeFileStore.writeUpload(path, part.data)
                        saved++
                    }
                    respondJson(out, 200, json("ok" to true, "saved" to saved, "dirs" to dirs))
                }
                "POST" to "/api/mkdir" -> {
                    val path = try {
                        JSONObject(String(body, StandardCharsets.UTF_8)).optString("path")
                    } catch (_: Exception) {
                        String(body, StandardCharsets.UTF_8)
                    }
                    RimeFileStore.makeDirectory(path)
                    respondJson(out, 200, json("ok" to true))
                }
                else -> respondJson(out, 404, json("ok" to false, "error" to "not found"))
            }
        } catch (e: Exception) {
            respondJson(out, 500, json("ok" to false, "error" to (e.message ?: "error")))
        }
    }

    private fun respondJson(out: OutputStream, status: Int, obj: JSONObject) {
        respond(out, status, obj.toString().toByteArray(StandardCharsets.UTF_8), "application/json; charset=utf-8")
    }

    private fun respond(
        out: OutputStream,
        status: Int,
        body: ByteArray,
        contentType: String,
        extra: Map<String, String> = emptyMap()
    ) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Cache-Control: no-store\r\n")
            extra.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private data class MultipartPart(val name: String?, val filename: String?, val data: ByteArray) {
        fun asText() = String(data, StandardCharsets.UTF_8)
    }

    companion object {
        fun ipv4Addresses(): List<String> {
            val out = mutableListOf<String>()
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.254.")) out.add(ip)
                    }
                }
            }
            return out
        }

        private fun splitPathQuery(raw: String): Pair<String, Map<String, String>> {
            val decoded = URLDecoder.decode(raw, "UTF-8")
            val q = decoded.indexOf('?')
            val path = if (q >= 0) decoded.substring(0, q) else decoded
            val query = mutableMapOf<String, String>()
            if (q >= 0) {
                decoded.substring(q + 1).split('&').forEach { pair ->
                    val kv = pair.split('=', limit = 2)
                    if (kv.isNotEmpty()) {
                        query[kv[0]] = if (kv.size > 1) URLDecoder.decode(kv[1], "UTF-8") else ""
                    }
                }
            }
            return path to query
        }

        private fun json(vararg pairs: Pair<String, Any>): JSONObject {
            val o = JSONObject()
            pairs.forEach { (k, v) -> o.put(k, v) }
            return o
        }

        private fun jsonStringArray(body: ByteArray, key: String): List<String> {
            val obj = JSONObject(String(body, StandardCharsets.UTF_8))
            val arr = obj.optJSONArray(key) ?: error("invalid json")
            return (0 until arr.length()).map { arr.getString(it) }
        }

        private fun multipartBoundary(contentType: String): String? {
            contentType.split(';').forEach { part ->
                val p = part.trim()
                if (p.lowercase().startsWith("boundary=")) {
                    return p.substring("boundary=".length).trim().trim('"')
                }
            }
            return null
        }

        private fun parseMultipart(body: ByteArray, boundary: String): List<MultipartPart> {
            val sep = "--$boundary".toByteArray(StandardCharsets.ISO_8859_1)
            val parts = mutableListOf<MultipartPart>()
            var searchStart = 0
            while (true) {
                val r = indexOf(body, sep, searchStart) ?: break
                searchStart = r + sep.size
                if (searchStart + 1 < body.size && body[searchStart] == '-'.code.toByte() && body[searchStart + 1] == '-'.code.toByte()) {
                    break
                }
                if (searchStart + 1 < body.size && body[searchStart] == 13.toByte() && body[searchStart + 1] == 10.toByte()) {
                    searchStart += 2
                }
                val next = indexOf(body, sep, searchStart) ?: break
                var partData = body.copyOfRange(searchStart, next)
                if (partData.size >= 2 && partData[partData.size - 2] == 13.toByte() && partData.last() == 10.toByte()) {
                    partData = partData.copyOf(partData.size - 2)
                }
                val headerEnd = indexOf(partData, byteArrayOf(13, 10, 13, 10), 0)
                if (headerEnd != null) {
                    val headerStr = String(partData.copyOfRange(0, headerEnd), StandardCharsets.ISO_8859_1)
                    val content = partData.copyOfRange(headerEnd + 4, partData.size)
                    var name: String? = null
                    var filename: String? = null
                    headerStr.split("\r\n").forEach { line ->
                        if (line.lowercase().startsWith("content-disposition:")) {
                            name = extractHeaderParam(line, "name")
                            filename = extractHeaderParam(line, "filename")
                        }
                    }
                    parts.add(MultipartPart(name, filename, content))
                }
                searchStart = next
            }
            return parts
        }

        private fun extractHeaderParam(line: String, key: String): String? {
            val pattern = "$key=\""
            val i = line.indexOf(pattern, ignoreCase = true)
            if (i < 0) return null
            val rest = line.substring(i + pattern.length)
            val end = rest.indexOf('"')
            if (end < 0) return null
            return rest.substring(0, end)
        }

        private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int? {
            if (needle.isEmpty()) return from
            outer@ for (i in from..(data.size - needle.size)) {
                for (j in needle.indices) {
                    if (data[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return null
        }

    }

    private fun endsWith(data: ByteArray, suffix: ByteArray): Boolean {
        if (data.size < suffix.size) return false
        for (i in suffix.indices) {
            if (data[data.size - suffix.size + i] != suffix[i]) return false
        }
        return true
    }

    private fun readExact(input: BufferedInputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return if (off == len) buf else buf.copyOf(off)
    }
}
