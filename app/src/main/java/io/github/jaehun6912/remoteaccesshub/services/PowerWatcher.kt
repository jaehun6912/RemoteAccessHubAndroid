package io.github.jaehun6912.remoteaccesshub.services

import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.CancelSignal
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import io.github.jaehun6912.remoteaccesshub.core.Mono
import io.github.jaehun6912.remoteaccesshub.core.OperationCanceledException
import io.github.jaehun6912.remoteaccesshub.core.PowerSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** PC 전원 상태를 확인할 대상. [via]는 사용자에게 보여 줄 경로 이름(실제 주소는 메시지에 쓰지 않는다). */
data class PowerTarget(val host: String, val port: Int, val via: String)

enum class PcPowerState {
    /** 확인하지 않음(설정이 꺼져 있거나, 확인할 주소가 없음). */
    Disabled,

    /** 아직 확인 전. */
    Unknown,

    /** 확인 중. */
    Checking,

    /** 포트가 응답함 = 켜져 있음(확실). */
    On,

    /** 포트가 응답하지 않음. 꺼졌을 수도, 포트·방화벽 때문일 수도 있다. */
    NoAnswer,
}

data class PcPowerStatus(val state: PcPowerState, val detail: String, val checkedAt: OffsetDateTime?) {
    /** 배지에 쓸 한 줄. */
    val pillText: String
        get() = when (state) {
            PcPowerState.On -> checkedAt?.let { "PC 켜짐 · ${it.format(HM)}" } ?: "PC 켜짐"
            PcPowerState.NoAnswer -> "PC 응답 없음"
            PcPowerState.Checking -> "PC 확인 중"
            PcPowerState.Disabled -> "PC 확인 안 함"
            PcPowerState.Unknown -> "PC 확인 전"
        }

    companion object {
        private val HM = DateTimeFormatter.ofPattern("HH:mm")
        val Disabled = PcPowerStatus(PcPowerState.Disabled, "확인 안 함", null)
        val Unknown = PcPowerStatus(PcPowerState.Unknown, "확인 전", null)
    }
}

/** PC 전원 상태 판정 규칙. 시간·네트워크에 기대지 않는 순수 함수라 그대로 검사할 수 있다. */
object PowerRules {
    /** 지금 확인할 대상. 확인할 곳이 없으면 null. */
    fun target(s: AppSettings): PowerTarget? {
        if (s.powerCheck == PowerSource.Off) return null
        return if (InputRules.isValidHost(s.publicHost) && InputRules.isValidPort(s.publicRdpPort)) {
            PowerTarget(s.publicHost.trim(), s.publicRdpPort, "일반 접속 주소")
        } else {
            null
        }
    }

    /**
     * 확인 결과를 상태로 바꾼다. 포트가 응답하면 켜진 것이 확실하지만,
     * 응답이 없다고 꺼졌다고 단정하지 않는다(포트·방화벽일 수 있다).
     */
    fun decide(target: PowerTarget?, portOpen: Boolean?, routerLoggedIn: Boolean, now: OffsetDateTime): PcPowerStatus {
        if (target == null) return PcPowerStatus.Disabled
        if (portOpen == null) return PcPowerStatus.Unknown
        if (portOpen) return PcPowerStatus(PcPowerState.On, "${target.via} 응답", now)
        val why = if (routerLoggedIn) "공유기는 연결됨 · 꺼져 있거나 포트가 막힘" else "꺼져 있거나 포트가 막힘"
        return PcPowerStatus(PcPowerState.NoAnswer, why, now)
    }
}

/**
 * 설정한 주기마다 PC 포트 응답을 확인해 전원 상태를 알려 준다.
 * 다른 작업(PC 켜기·접속)이 진행 중이면 쉰다.
 */
class PowerWatcher(
    private val probe: PortProbe,
    private val log: AppLog,
    private val scope: CoroutineScope,
    private val settings: () -> AppSettings,
    private val routerLoggedIn: () -> Boolean,
    private val paused: () -> Boolean,
) {
    private var running = false
    private var nextDue = Long.MIN_VALUE
    private var signal: CancelSignal? = null

    var probeTimeoutMs: Long = 3000

    var status: PcPowerStatus = PcPowerStatus.Unknown
        private set

    /** 상태가 바뀌면 호출된다. */
    var changed: ((PcPowerStatus) -> Unit)? = null

    /** 주기가 됐으면 한 번 확인한다. 화면 타이머에서 부른다. */
    fun tick() {
        if (Mono.now() < nextDue) return
        scope.launch { check(force = false) }
    }

    /** 다음 확인을 앞당긴다(설정이 바뀌었거나 PC를 켠 직후). */
    fun checkSoon(afterMs: Long = 0) {
        nextDue = Mono.now() + afterMs
    }

    /** 지금 확인한다(배지를 눌렀을 때). */
    suspend fun checkNow() = check(force = true)

    private suspend fun check(force: Boolean) {
        val s = settings()
        val target = PowerRules.target(s)
        if (target == null) {
            nextDue = Mono.now() + interval(s)
            apply(PcPowerStatus.Disabled)
            return
        }
        // 접속·PC 켜기 작업 중에는 그 작업이 이미 포트를 확인하므로 끼어들지 않는다.
        if (!force && safe(paused)) {
            nextDue = Mono.now() + 5000
            return
        }
        if (running) return
        running = true
        val sig = CancelSignal()
        signal = sig
        try {
            if (status.state == PcPowerState.Unknown || status.state == PcPowerState.Disabled) {
                apply(PcPowerStatus(PcPowerState.Checking, target.via + " 확인 중", status.checkedAt))
            }
            val open = probe.isOpen(target.host, target.port, probeTimeoutMs, sig)
            val next = PowerRules.decide(target, open, safe(routerLoggedIn), OffsetDateTime.now())
            if (next.state != status.state) log.debug("PC 전원 확인: ${next.pillText} (${next.detail})")
            apply(next)
        } catch (_: OperationCanceledException) {
            // 종료 중 — 상태를 바꾸지 않는다.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("PC 전원 확인 실패: ${e.message}")
            apply(PcPowerStatus(PcPowerState.Unknown, "확인하지 못함", status.checkedAt))
        } finally {
            nextDue = Mono.now() + interval(settings())
            signal = null
            running = false
        }
    }

    private fun interval(s: AppSettings): Long = s.powerCheckSeconds.coerceIn(15, 3600) * 1000L

    private fun safe(f: () -> Boolean): Boolean = try {
        f()
    } catch (_: Exception) {
        false
    }

    private fun apply(next: PcPowerStatus) {
        if (status == next) return
        status = next
        changed?.invoke(next)
    }

    fun dispose() {
        signal?.cancel()
    }
}
