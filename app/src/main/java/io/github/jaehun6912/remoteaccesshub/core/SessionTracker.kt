package io.github.jaehun6912.remoteaccesshub.core

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

enum class SessionState {
    /** 아직 한 번도 확인하지 못함. */
    Unknown,

    /** 공유기가 "인증되지 않음"이라고 응답함(확정). */
    LoggedOut,

    /** 공유기가 세션 정보를 정상 응답함(확정). */
    LoggedIn,
}

enum class SessionProbeResult {
    /** session/info 정상 응답 → 로그인 상태 확정. */
    Ok,

    /** 공유기가 Unauthenticated(-31998) 등 인증 실패를 명시적으로 응답 → 로그아웃 확정. */
    Unauthenticated,

    /** 판독 불가(페이지가 공유기가 아님, 네트워크 오류, 응답 형식 불명 등). 상태를 바꾸지 않는다. */
    Unavailable,
}

/**
 * 로그인 상태 래치. "판독 불가"는 절대 로그아웃으로 취급하지 않는다.
 * 상태는 확정적 증거(Ok / Unauthenticated)로만 바뀐다.
 */
class SessionTracker {
    private val gate = Any()

    var state: SessionState = SessionState.Unknown
        private set
    var lastConfirmedAt: OffsetDateTime? = null
        private set
    var lastProbeAt: OffsetDateTime? = null
        private set
    var lastReason: String = ""
        private set
    var consecutiveUnavailable: Int = 0
        private set

    private val listeners = mutableListOf<(SessionState, SessionState, String) -> Unit>()

    /** 상태가 바뀌면 (이전, 새 상태, 이유)로 호출한다. */
    fun onStateChanged(listener: (SessionState, SessionState, String) -> Unit) {
        listeners += listener
    }

    val isLoggedIn: Boolean get() = state == SessionState.LoggedIn

    fun apply(result: SessionProbeResult, reason: String, now: OffsetDateTime? = null): SessionState {
        val t = now ?: OffsetDateTime.now()
        val old: SessionState
        val cur: SessionState
        synchronized(gate) {
            old = state
            lastProbeAt = t
            when (result) {
                SessionProbeResult.Ok -> {
                    state = SessionState.LoggedIn
                    lastConfirmedAt = t
                    consecutiveUnavailable = 0
                    lastReason = reason
                }
                SessionProbeResult.Unauthenticated -> {
                    state = SessionState.LoggedOut
                    consecutiveUnavailable = 0
                    lastReason = reason
                }
                SessionProbeResult.Unavailable -> {
                    consecutiveUnavailable++
                    lastReason = "확인 불가: $reason"
                }
            }
            cur = state
        }
        if (old != cur) listeners.toList().forEach { it(old, cur, reason) }
        return cur
    }

    /** 공유기 URL이 바뀌는 등 이전 증거가 무효화될 때만 호출. */
    fun reset(reason: String) {
        val old: SessionState
        synchronized(gate) {
            old = state
            state = SessionState.Unknown
            lastConfirmedAt = null
            consecutiveUnavailable = 0
            lastReason = reason
        }
        if (old != SessionState.Unknown) listeners.toList().forEach { it(old, SessionState.Unknown, reason) }
    }

    fun describe(): String = synchronized(gate) {
        var s = when (state) {
            SessionState.LoggedIn -> "로그인됨"
            SessionState.LoggedOut -> "로그인 필요"
            else -> "확인 전"
        }
        val c = lastConfirmedAt
        if (c != null && state == SessionState.LoggedIn) s += " (확인 ${c.format(DateTimeFormatter.ofPattern("HH:mm:ss"))})"
        if (consecutiveUnavailable > 0) s += " [판독 불가 ${consecutiveUnavailable}회, 상태 유지]"
        s
    }
}
