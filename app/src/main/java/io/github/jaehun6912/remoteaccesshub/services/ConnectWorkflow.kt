package io.github.jaehun6912.remoteaccesshub.services

import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.CancelSignal
import io.github.jaehun6912.remoteaccesshub.core.ConnectMode
import io.github.jaehun6912.remoteaccesshub.core.CrdBootCheck
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import io.github.jaehun6912.remoteaccesshub.core.Mono
import io.github.jaehun6912.remoteaccesshub.core.OperationCanceledException
import kotlinx.coroutines.CancellationException
import java.time.Duration
import kotlin.math.max

enum class ConnectStage {
    Validating,
    WaitingPort,
    PortOpen,

    /** 크롬 원격 데스크톱: 부팅 확인 없이 바로 여는 경우. */
    BootCheckSkipped,
    LaunchingRdp,

    /** 크롬 원격 데스크톱을 여는 중. */
    LaunchingCrd,
    Done,
    Failed,
    Cancelled,
    TimedOut,
}

data class ConnectProgress(val stage: ConnectStage, val message: String)

data class ConnectOutcome(
    val stage: ConnectStage,
    val success: Boolean,
    val message: String,
    val elapsed: Duration,
    val pcRespondedOnPort: Boolean,
    val mode: ConnectMode,
) {
    val isCancelled: Boolean get() = stage == ConnectStage.Cancelled
    val isTimedOut: Boolean get() = stage == ConnectStage.TimedOut
}

/** 포트가 응답하는지(= PC가 켜져 있는지) 확인한다. */
interface PortProbe {
    suspend fun isOpen(host: String, port: Int, timeoutMs: Long, signal: CancelSignal): Boolean
}

/** 원격 데스크톱을 어디로 열었는지. */
enum class RdpOpenTarget {
    /** rdp:// 주소를 처리하는 앱으로 열었다. */
    UriScheme,

    /** .rdp 파일을 여는 앱으로 열었다. */
    RdpFile,
}

/** 원격 데스크톱 앱이 없을 때. */
class RdpClientMissingException(message: String) : Exception(message)

interface RdpLauncher {
    /** 원격 데스크톱 앱을 연다. 자격 증명은 그 앱에서 사용자가 직접 입력한다. 앱이 없으면 [RdpClientMissingException]. */
    fun launch(host: String, port: Int): RdpOpenTarget
}

/** 크롬 원격 데스크톱을 어디로 열었는지. */
enum class CrdOpenTarget {
    /** 설치된 앱으로, 원하는 주소까지 열었다. */
    App,

    /** 설치된 앱을 열었지만 주소를 넘기지 못해 앱 첫 화면(기기 목록)이 열렸다. */
    AppHome,

    /** 앱이 없어 브라우저로 열었다. */
    Browser,
}

interface CrdLauncher {
    /**
     * 크롬 원격 데스크톱을 연다. 휴대폰에 앱이 설치되어 있으면 앱으로, 없으면 브라우저로 연다.
     * 기기 ID가 있으면 그 기기의 세션 주소를, 없으면 기기 목록 화면을 연다.
     * 구글 로그인과 PIN 입력은 사용자가 직접 한다.
     */
    fun open(hostId: String?): CrdOpenTarget
}

/**
 * [PC 접속] 흐름: 원격 데스크톱 포트 응답 대기(= 실제 부팅 확인) → 원격 데스크톱 앱 열기.
 * 크롬 원격 데스크톱은 설정한 방법으로만 부팅을 확인한 뒤(안 할 수도 있음) 앱이나 브라우저로 연다.
 * Windows 버전 Services/ConnectWorkflow.cs에서 VPN을 뺀 흐름이다.
 */
class ConnectWorkflow(
    private val probe: PortProbe,
    private val rdp: RdpLauncher,
    private val crd: CrdLauncher,
    private val log: AppLog,
) {
    var portAttemptTimeoutMs: Long = 3000
    var portRetryIntervalMs: Long = 3000

    suspend fun run(settings: AppSettings, mode: ConnectMode, progress: ((ConnectProgress) -> Unit)?, signal: CancelSignal): ConnectOutcome {
        val start = Mono.now()
        fun elapsed() = Duration.ofMillis(Mono.now() - start)
        var responded = false
        try {
            progress?.invoke(ConnectProgress(ConnectStage.Validating, "설정 확인 중..."))
            val errors = settings.validateConnect(mode)
            if (errors.isNotEmpty()) return ConnectOutcome(ConnectStage.Failed, false, errors.joinToString("\n"), elapsed(), false, mode)

            if (mode == ConnectMode.Crd) {
                // 크롬 원격 데스크톱: 열어 둘 포트가 없으므로 부팅 확인은 설정한 방법으로만 한다(안 할 수도 있음).
                if (settings.crdCheck == CrdBootCheck.None) {
                    progress?.invoke(ConnectProgress(ConnectStage.BootCheckSkipped, "부팅 확인 없이 크롬 원격 데스크톱을 엽니다(열어 둔 포트가 없어 확인할 수 없음)."))
                } else {
                    val wait = waitForPort(settings, settings.publicHost.trim(), settings.publicRdpPort, progress, start, mode, signal)
                    if (wait != null) return wait
                    responded = true
                }
                progress?.invoke(ConnectProgress(ConnectStage.LaunchingCrd, "크롬 원격 데스크톱을 여는 중..."))
                val opened = crd.open(settings.crdHostId)
                val where = if (InputRules.normalizeCrdHostId(settings.crdHostId) == null) "기기 목록" else "저장된 기기"
                val how = when (opened) {
                    CrdOpenTarget.App -> "크롬 원격 데스크톱 앱을 열었습니다($where)."
                    // 앱은 열었지만 주소를 넘기지 못해 저장한 기기로 바로 가지 못한 경우.
                    CrdOpenTarget.AppHome -> "크롬 원격 데스크톱 앱을 열었습니다(기기 목록). 목록에서 기기를 고르세요."
                    CrdOpenTarget.Browser -> "브라우저로 크롬 원격 데스크톱을 열었습니다($where)."
                }
                return ConnectOutcome(ConnectStage.Done, true, "$how 구글 로그인과 PIN 입력은 직접 하세요.", elapsed(), responded, mode)
            }

            val host = settings.publicHost.trim()
            val port = settings.publicRdpPort

            // 포트 응답 대기 (= 실제 PC 부팅 확인)
            val timeout = waitForPort(settings, host, port, progress, start, mode, signal)
            if (timeout != null) return timeout
            responded = true

            progress?.invoke(ConnectProgress(ConnectStage.PortOpen, "원격 데스크톱 포트 응답 확인. 원격 데스크톱 앱을 엽니다."))
            log.info("원격 데스크톱 포트 응답 확인: ${InputRules.hostPort(host, port)}")
            progress?.invoke(ConnectProgress(ConnectStage.LaunchingRdp, "원격 데스크톱 앱 여는 중..."))
            val how = rdp.launch(host, port)
            val msg = when (how) {
                RdpOpenTarget.UriScheme -> "원격 데스크톱 앱을 열었습니다."
                RdpOpenTarget.RdpFile -> "원격 데스크톱 앱으로 연결 파일을 열었습니다."
            }
            return ConnectOutcome(ConnectStage.Done, true, "$msg 자격 증명은 원격 데스크톱 앱에서 입력하세요.", elapsed(), true, mode)
        } catch (e: OperationCanceledException) {
            log.warn("PC 접속 작업이 취소되었습니다.")
            return ConnectOutcome(ConnectStage.Cancelled, false, "작업이 취소되었습니다.", elapsed(), responded, mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RdpClientMissingException) {
            log.error("원격 데스크톱 앱 없음: ${e.message}")
            return ConnectOutcome(ConnectStage.Failed, false, e.message ?: "원격 데스크톱 앱이 없습니다.", elapsed(), responded, mode)
        } catch (e: Exception) {
            log.error("PC 접속 오류: ${e.message}")
            return ConnectOutcome(ConnectStage.Failed, false, "오류: ${e.message}", elapsed(), responded, mode)
        }
    }

    /** 포트가 응답할 때까지 기다린다(= 실제 부팅 확인). 시간이 초과되면 그 결과를, 응답하면 null을 돌려준다. */
    private suspend fun waitForPort(
        settings: AppSettings,
        host: String,
        port: Int,
        progress: ((ConnectProgress) -> Unit)?,
        start: Long,
        mode: ConnectMode,
        signal: CancelSignal,
    ): ConnectOutcome? {
        val deadline = Mono.now() + settings.bootWaitSeconds * 1000L
        var attempt = 0
        while (true) {
            signal.throwIfCancelled()
            attempt++
            val remaining = max(0L, deadline - Mono.now()) / 1000
            progress?.invoke(
                ConnectProgress(ConnectStage.WaitingPort, "${InputRules.hostPort(host, port)} 원격 데스크톱 포트 응답 대기 중... (시도 $attempt, 남은 시간 ${remaining}초)"),
            )
            if (probe.isOpen(host, port, portAttemptTimeoutMs, signal)) return null
            if (Mono.now() >= deadline) {
                log.warn("원격 데스크톱 포트 응답 없음: ${InputRules.hostPort(host, port)} (${settings.bootWaitSeconds}초)")
                return ConnectOutcome(
                    ConnectStage.TimedOut,
                    false,
                    "${settings.bootWaitSeconds}초 동안 ${InputRules.hostPort(host, port)}의 원격 데스크톱 포트가 응답하지 않았습니다. PC가 아직 켜지지 않았거나 포트포워딩/방화벽 설정을 확인하세요.",
                    Duration.ofMillis(Mono.now() - start),
                    false,
                    mode,
                )
            }
            signal.delay(portRetryIntervalMs)
        }
    }
}
