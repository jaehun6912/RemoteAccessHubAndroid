package io.github.jaehun6912.remoteaccesshub.router

import io.github.jaehun6912.remoteaccesshub.core.CancelSignal
import io.github.jaehun6912.remoteaccesshub.core.Mono
import io.github.jaehun6912.remoteaccesshub.core.ProbeRect
import io.github.jaehun6912.remoteaccesshub.core.ProbeSnapshot
import io.github.jaehun6912.remoteaccesshub.core.SessionProbeResult
import io.github.jaehun6912.remoteaccesshub.core.SessionTracker

data class SessionProbeDetail(val result: SessionProbeResult, val reason: String, val httpStatus: Int, val errorCode: Int?)

data class ClickResult(val clicked: Boolean, val reason: String)

data class LogoutResult(val confirmed: Boolean, val alreadyLoggedOut: Boolean, val message: String)

/**
 * 공유기 화면을 읽고 누르는 기능. 실제 구현은 WebView를 쓰는 [RouterBrowser]이고,
 * 화면 이동·WOL 자동화([RouterNavigator], [WolAutomation])는 이 인터페이스에만 기대므로 가짜 화면으로 검사할 수 있다.
 */
interface RouterDriver {
    val session: SessionTracker
    val network: NetworkObserver
    val isReady: Boolean

    /** 화면 판독(probe.js). 실패해도 예외 대신 [ProbeSnapshot.error]를 채워 돌려준다. */
    suspend fun probe(signal: CancelSignal = CancelSignal.None): ProbeSnapshot

    /** Flutter "Enable accessibility" 자리표시자를 눌러 접근성 트리를 켠다. 이미 켜져 있으면 true. */
    suspend fun ensureSemantics(waitMs: Long, signal: CancelSignal = CancelSignal.None): Boolean

    /** 페이지 안에서 공유기 API(session/info)를 호출해 세션 유효성을 확인한다. */
    suspend fun probeSession(signal: CancelSignal = CancelSignal.None): SessionProbeDetail

    /** 접근성 노드를 id로 찾아 라벨과 위치를 다시 검증한 뒤 누른다(검증 실패 시 누르지 않음). */
    suspend fun clickSemanticsNode(nodeId: String, expectedLabel: String, expectedRect: ProbeRect, signal: CancelSignal = CancelSignal.None): ClickResult

    /** 장면 텍스트 위치를 누른다. 누르기 직전에 다시 판독해 같은 글자·같은 위치이고 다른 항목 위가 아닐 때만 누른다. */
    suspend fun clickVerifiedParagraph(text: String, expected: ProbeRect, signal: CancelSignal = CancelSignal.None): ClickResult

    /** 화면 좌표(CSS px)를 실제 터치로 누른다. */
    suspend fun clickAt(x: Double, y: Double, signal: CancelSignal = CancelSignal.None): Boolean

    /** 페이지를 다시 불러오지 않고 공유기 앱 안에서 경로를 바꾼다. */
    suspend fun pushRoute(path: String, signal: CancelSignal = CancelSignal.None): Boolean

    /** 주소를 열고 페이지를 다 불러올 때까지(최대 [timeoutMs]) 기다린다. 실패해도 예외를 내지 않는다. */
    suspend fun navigate(url: String, timeoutMs: Long, signal: CancelSignal = CancelSignal.None): Boolean
}

/** 세션 확인 후 래치에 반영한다. */
suspend fun RouterDriver.refreshSession(signal: CancelSignal = CancelSignal.None): SessionProbeDetail {
    val d = probeSession(signal)
    session.apply(d.result, d.reason)
    return d
}

/** 조건이 참이 될 때까지 주기적으로 판독한다. 마지막 스냅샷을 돌려준다. */
suspend fun RouterDriver.waitFor(
    predicate: (ProbeSnapshot) -> Boolean,
    timeoutMs: Long,
    intervalMs: Long,
    signal: CancelSignal = CancelSignal.None,
): Pair<Boolean, ProbeSnapshot> {
    val deadline = Mono.now() + timeoutMs
    while (true) {
        signal.throwIfCancelled()
        val last = probe(signal)
        if (predicate(last)) return true to last
        if (Mono.now() >= deadline) return false to last
        signal.delay(intervalMs)
    }
}
