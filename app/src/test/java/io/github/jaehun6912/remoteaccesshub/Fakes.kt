package io.github.jaehun6912.remoteaccesshub

import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.CancelSignal
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import io.github.jaehun6912.remoteaccesshub.core.ProbeRect
import io.github.jaehun6912.remoteaccesshub.core.ProbeSnapshot
import io.github.jaehun6912.remoteaccesshub.core.SessionProbeResult
import io.github.jaehun6912.remoteaccesshub.core.SessionTracker
import io.github.jaehun6912.remoteaccesshub.router.ClickResult
import io.github.jaehun6912.remoteaccesshub.router.NetworkObserver
import io.github.jaehun6912.remoteaccesshub.router.RouterDriver
import io.github.jaehun6912.remoteaccesshub.router.SessionProbeDetail
import io.github.jaehun6912.remoteaccesshub.services.CrdLauncher
import io.github.jaehun6912.remoteaccesshub.services.CrdOpenTarget
import io.github.jaehun6912.remoteaccesshub.services.PortProbe
import io.github.jaehun6912.remoteaccesshub.services.RdpLauncher
import io.github.jaehun6912.remoteaccesshub.services.RdpOpenTarget
import io.github.jaehun6912.remoteaccesshub.services.RemoteLinks
import kotlinx.coroutines.delay

class FakePortProbe : PortProbe {
    var openAfterAttempts = 1
    var neverOpen = false
    var attemptDelayMs = 1L
    var attempts = 0
    val targets = mutableListOf<String>()

    override suspend fun isOpen(host: String, port: Int, timeoutMs: Long, signal: CancelSignal): Boolean {
        attempts++
        targets.add(InputRules.hostPort(host, port))
        delay(attemptDelayMs)
        signal.throwIfCancelled()
        return !neverOpen && attempts >= openAfterAttempts
    }
}

class FakeRdpLauncher : RdpLauncher {
    val launches = mutableListOf<Pair<String, Int>>()
    var result = RdpOpenTarget.UriScheme
    override fun launch(host: String, port: Int): RdpOpenTarget {
        RemoteLinks.rdpUri(host, port) // 실제 실행기와 같은 입력 검증
        launches.add(host to port)
        return result
    }
}

class FakeCrdLauncher : CrdLauncher {
    val opened = mutableListOf<String>()
    var target = CrdOpenTarget.App
    var lastOpenWith: io.github.jaehun6912.remoteaccesshub.core.CrdOpenWith? = null
    override fun open(hostId: String?, openWith: io.github.jaehun6912.remoteaccesshub.core.CrdOpenWith): CrdOpenTarget {
        lastOpenWith = openWith
        opened.add(RemoteLinks.crdUrl(hostId))
        return target
    }
}

/** 페이지 안 관찰 스크립트(netwatch.js)의 기록을 흉내 낸다. */
class FakeNet {
    private val doc = "doc1"
    private var ver = 0L
    private var seq = 0
    private val calls = mutableListOf<MutableMap<String, Any?>>()

    fun add(method: String, status: Int? = 200, done: Boolean = true, failed: Boolean = false, err: String? = null, checked: Boolean = true, ok: Boolean? = true, code: Int? = null, msg: String? = null, params: String? = null): Int {
        val id = ++seq
        calls.add(mutableMapOf("id" to id, "t" to System.currentTimeMillis(), "method" to method, "status" to status, "done" to done, "failed" to failed, "err" to err,
            "checked" to checked, "ok" to ok, "code" to code, "msg" to msg, "params" to params, "path" to "http://r/cgi/service.cgi", "v" to ++ver))
        return id
    }

    fun json(): String {
        fun v(x: Any?): String = when (x) {
            null -> "null"
            is String -> "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            else -> x.toString()
        }
        val items = calls.joinToString(",") { c -> "{" + c.entries.joinToString(",") { (k, x) -> "\"$k\":${v(x)}" } + "}" }
        return "{\"doc\":\"$doc\",\"ver\":$ver,\"calls\":[$items]}"
    }
}

/**
 * 가짜 공유기 화면. [page]를 판독 결과로 돌려주고, 누르기는 [onClick]으로 화면·기록을 바꾼다.
 * 실제 WebView 없이 화면 이동·WOL 자동화 흐름을 검사한다.
 */
class FakeRouterDriver(log: AppLog = AppLog(null)) : RouterDriver {
    val net = FakeNet()
    override val session = SessionTracker()
    override val network = NetworkObserver(log) { net.json() }
    override val isReady = true
    var page: ProbeSnapshot = ProbeSnapshot(error = "빈 화면")
    var sessionResult = SessionProbeResult.Ok
    val clicks = mutableListOf<String>()
    var onClick: (String) -> Unit = {}
    var pushedRoutes = mutableListOf<String>()
    var onPushRoute: (String) -> Boolean = { false }

    override suspend fun probe(signal: CancelSignal): ProbeSnapshot {
        signal.throwIfCancelled()
        return page
    }

    override suspend fun ensureSemantics(waitMs: Long, signal: CancelSignal): Boolean = true

    override suspend fun probeSession(signal: CancelSignal): SessionProbeDetail = when (sessionResult) {
        SessionProbeResult.Ok -> SessionProbeDetail(SessionProbeResult.Ok, "session/info 정상", 200, null)
        SessionProbeResult.Unauthenticated -> SessionProbeDetail(SessionProbeResult.Unauthenticated, "인증되지 않음", 200, -31998)
        SessionProbeResult.Unavailable -> SessionProbeDetail(SessionProbeResult.Unavailable, "판독 불가", 0, null)
    }

    override suspend fun clickSemanticsNode(nodeId: String, expectedLabel: String, expectedRect: ProbeRect, signal: CancelSignal): ClickResult {
        val n = page.nodes.firstOrNull { it.id == nodeId } ?: return ClickResult(false, "missing")
        if (n.rect != expectedRect) return ClickResult(false, "moved")
        clicks.add(nodeId)
        onClick(nodeId)
        return ClickResult(true, "clicked")
    }

    override suspend fun clickVerifiedParagraph(text: String, expected: ProbeRect, signal: CancelSignal): ClickResult {
        clicks.add("para:$text")
        onClick("para:$text")
        return ClickResult(true, "clicked")
    }

    override suspend fun clickAt(x: Double, y: Double, signal: CancelSignal): Boolean {
        clicks.add("at:${x.toInt()},${y.toInt()}")
        onClick("at:${x.toInt()},${y.toInt()}")
        return true
    }

    override suspend fun pushRoute(path: String, signal: CancelSignal): Boolean {
        pushedRoutes.add(path)
        return onPushRoute(path)
    }

    override suspend fun navigate(url: String, timeoutMs: Long, signal: CancelSignal): Boolean = true
}
