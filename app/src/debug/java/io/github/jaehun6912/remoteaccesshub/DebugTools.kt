package io.github.jaehun6912.remoteaccesshub

import android.content.Context
import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/** 디버그 빌드 전용 도구: 모의 공유기. */
object DebugTools {
    const val AVAILABLE = true

    fun startMockRouter(context: Context, log: AppLog): MockRouterHandle =
        MockRouterServer(context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }, log).also { it.start() }
}

data class MockPc(val name: String, val mac: String)

/**
 * 모의 ipTIME 공유기(Windows 버전 SelfTest/MockRouterServer.cs를 옮김). 실제 공유기에서 확인한 API 형식을 흉내 낸다:
 * POST /cgi/service.cgi {"method":"...","params":...} → {"result":...} 또는 {"result":null,"error":{"code":-31998,"message":"Unauthenticated"}}
 * 화면은 Windows 자체검사와 같은 모의 페이지(SelfTest/Mock/index.html)다.
 */
class MockRouterServer(private val indexHtml: String, private val log: AppLog) : MockRouterHandle {
    private val gate = Any()
    private val sessions = HashSet<String>()
    private val signalList = ArrayList<String>()
    private lateinit var http: ServerSocket
    private lateinit var rdp: ServerSocket
    @Volatile private var closed = false

    override var baseUrl: String = ""
        private set
    override var fakeRdpPort: Int = 0
        private set

    @Volatile var pcList: List<MockPc> = listOf(
        MockPc("OTHER-PC-1", "00:11:22:33:44:01"),
        MockPc(MockRouterHandle.DEFAULT_TARGET_NAME, "00:11:22:33:44:02"),
        MockPc("OTHER-PC-2", "00:11:22:33:44:03"),
    )
    @Volatile var loginShouldFail = false
    @Volatile var signalShouldFail = false
    @Volatile var loginCount = 0; private set
    @Volatile var logoutCount = 0; private set

    val signals: List<String> get() = synchronized(gate) { signalList.toList() }
    val sessionCount: Int get() = synchronized(gate) { sessions.size }

    fun expireSessions() = synchronized(gate) { sessions.clear() }
    fun clearSignals() = synchronized(gate) { signalList.clear() }

    fun start() {
        val loopback = InetAddress.getByName("127.0.0.1")
        http = ServerSocket(0, 50, loopback)
        baseUrl = "http://127.0.0.1:${http.localPort}/"
        rdp = ServerSocket(0, 50, loopback)
        fakeRdpPort = rdp.localPort
        thread(name = "mock-router", isDaemon = true) {
            while (!closed) {
                val s = try {
                    http.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { handle(s) }
            }
        }
        thread(name = "mock-rdp", isDaemon = true) {
            while (!closed) {
                try {
                    rdp.accept().close()
                } catch (_: Exception) {
                    break
                }
            }
        }
        log.info("모의 공유기 서버: $baseUrl")
    }

    override fun close() {
        closed = true
        try { http.close() } catch (_: Exception) {}
        try { rdp.close() } catch (_: Exception) {}
    }

    private class Request(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: String)

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 64 * 1024) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val q = target.indexOf('?')
        return Request(parts[0], if (q >= 0) target.substring(0, q) else target, if (q >= 0) target.substring(q + 1) else "", headers, String(body, 0, read, Charsets.UTF_8))
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val req = readRequest(input) ?: return
                val path = req.path
                when {
                    path == "/" -> write(out, 200, "text/html; charset=utf-8", "<script>location.href=\"/ui\";</script>")
                    // 실제 공유기는 /ui/wol 같은 앱 경로에도 같은 index.html을 준다
                    path == "/ui" || (path.startsWith("/ui/") && !path.substringAfterLast('/').contains('.')) || path == "/ui/index.html" ->
                        write(out, 200, "text/html; charset=utf-8", indexHtml)
                    path == "/cgi/service.cgi" && req.method == "POST" -> {
                        // ?abort=1: 모의 페이지가 곧 취소할 요청(실기기에서 본 net::ERR_ABORTED 재현). WOL 신호는 처리하지 않은 것으로 둔다.
                        if (req.query.contains("abort=1")) {
                            Thread.sleep(600)
                            return
                        }
                        val sid = cookie(req.headers["cookie"], "mocksid")
                        val (status, json, setCookie) = handleApi(req.body, sid)
                        // ?hold=1: 처리하고 본문까지 보낸 뒤 연결을 잠시 열어 둔다(페이지가 먼저 닫으면 "응답을 받은 뒤 끊김").
                        if (req.query.contains("hold=1")) {
                            val bytes = json.toByteArray(Charsets.UTF_8)
                            val sb = StringBuilder()
                            sb.append("HTTP/1.1 $status OK\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n")
                            if (setCookie != null) sb.append("Set-Cookie: $setCookie\r\n")
                            sb.append("\r\n")
                            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                            out.write((Integer.toHexString(bytes.size) + "\r\n").toByteArray(Charsets.ISO_8859_1))
                            out.write(bytes)
                            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                            out.flush()
                            Thread.sleep(1500)
                            try {
                                out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                                out.flush()
                            } catch (_: Exception) {
                            }
                            return
                        }
                        write(out, status, "application/json; charset=utf-8", json, setCookie)
                    }
                    else -> write(out, 404, "text/plain; charset=utf-8", "not found")
                }
            } catch (e: Exception) {
                log.debug("모의 서버 오류: ${e.message}")
            }
        }
    }

    private fun cookie(header: String?, name: String): String? =
        header?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun write(out: OutputStream, status: Int, contentType: String, body: String, setCookie: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else if (status == 404) "Not Found" else "Status"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n")
        if (setCookie != null) sb.append("Set-Cookie: $setCookie\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun unauth() = "{\"result\":null, \"error\":{\"code\":-31998, \"message\":\"Unauthenticated\"}}"

    private fun handleApi(body: String, sid: String?): Triple<Int, String, String?> {
        val method: String
        val params: JsonElement?
        try {
            val o = Json.parseToJsonElement(body).jsonObject
            method = (o["method"] as? JsonPrimitive)?.content ?: ""
            params = o["params"]
        } catch (_: Exception) {
            return Triple(400, "{\"result\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}", null)
        }
        val authed = synchronized(gate) { sid != null && sessions.contains(sid) }
        return when (method) {
            "session/login" -> {
                loginCount++
                if (loginShouldFail) return Triple(200, "{\"result\":null, \"error\":{\"code\":-31999, \"message\":\"Login failed\"}}", null)
                val newSid = UUID.randomUUID().toString().replace("-", "")
                synchronized(gate) { sessions.add(newSid) }
                Triple(200, "{\"result\":{\"login\":true}}", "mocksid=$newSid; Path=/; HttpOnly")
            }
            "session/logout" -> {
                logoutCount++
                if (sid != null) synchronized(gate) { sessions.remove(sid) }
                Triple(200, "{\"result\":true}", null)
            }
            "session/info" -> if (authed) Triple(200, "{\"result\":{\"user\":\"admin\",\"remain\":600}}", null) else Triple(200, unauth(), null)
            "session/update" -> if (authed) Triple(200, "{\"result\":true}", null) else Triple(200, unauth(), null)
            "product/info" -> Triple(200, "{\"result\":{\"model\":\"MOCK-AX2004T\",\"version\":\"15.36.6\"}}", null)
            "wol/show" -> {
                if (!authed) return Triple(200, unauth(), null)
                val list = buildJsonObject {
                    put("result", buildJsonObject {
                        put("list", buildJsonArray {
                            for (pc in pcList) add(buildJsonObject { put("name", pc.name); put("mac", pc.mac) })
                        })
                    })
                }
                Triple(200, list.toString(), null)
            }
            "wol/signal" -> {
                if (!authed) return Triple(200, unauth(), null)
                val mac = when (params) {
                    is JsonArray -> (params.firstOrNull() as? JsonPrimitive)?.content ?: ""
                    is JsonObject -> params.toString()
                    is JsonPrimitive -> params.content
                    else -> ""
                }
                if (signalShouldFail) return Triple(200, "{\"result\":null, \"error\":{\"code\":-32000, \"message\":\"WOL failed\"}}", null)
                synchronized(gate) { signalList.add(mac) }
                log.info("모의 공유기: wol/signal 수신 " + InputRules.maskMac(mac))
                Triple(200, "{\"result\":\"ok\"}", null)
            }
            else -> if (authed) Triple(200, "{\"result\":null}", null) else Triple(200, unauth(), null)
        }
    }
}
