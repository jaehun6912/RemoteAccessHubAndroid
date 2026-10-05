package io.github.jaehun6912.remoteaccesshub.ui

import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.ConnectMode
import io.github.jaehun6912.remoteaccesshub.core.CrdBootCheck
import io.github.jaehun6912.remoteaccesshub.core.CrdOpenWith
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import io.github.jaehun6912.remoteaccesshub.core.SessionState
import io.github.jaehun6912.remoteaccesshub.core.WolMatchStatus
import io.github.jaehun6912.remoteaccesshub.router.StageStatus
import io.github.jaehun6912.remoteaccesshub.router.WolOutcome
import io.github.jaehun6912.remoteaccesshub.router.WolStep
import io.github.jaehun6912.remoteaccesshub.services.ConnectOutcome
import io.github.jaehun6912.remoteaccesshub.services.ConnectProgress
import io.github.jaehun6912.remoteaccesshub.services.ConnectStage
import io.github.jaehun6912.remoteaccesshub.services.PcPowerState
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

enum class StepState { Pending, Active, Done, Warning, Failed }

data class StepInfo(val title: String, val state: StepState = StepState.Pending, val detail: String = "대기", val progress: Double? = null) {
    override fun toString(): String = "$title: $state $detail"
}

/**
 * 화면 단계 표시용 상태 모델: ① 공유기 로그인 ② PC 켜기 ③ 부팅 확인 ④ 원격 접속.
 * 자동화 결과(세션 상태, WOL 단계, 접속 단계)를 받아 사람이 읽는 상태로 바꾼다. 화면과 분리해 단위 검사한다.
 * "버튼 클릭 / 공유기 처리 / 실제 부팅"의 구분은 그대로 유지한다(Windows 버전 UI/FlowTracker.cs와 같음).
 */
class FlowTracker {
    var login = StepInfo("공유기 로그인"); private set
    var wake = StepInfo("PC 켜기"); private set
    var boot = StepInfo("부팅 확인"); private set
    var remote = StepInfo("원격 접속"); private set

    val steps: List<StepInfo> get() = listOf(login, wake, boot, remote)

    /** 진단·자체검사용: "WOL 버튼 클릭: 완료" 형식. */
    var wolClickText = "WOL 버튼 클릭: -"; private set
    var wolRouterText = "공유기 처리: -"; private set
    var wolBootText = "PC 부팅: -"; private set

    var changed: (() -> Unit)? = null

    private var waitStartedAt: OffsetDateTime? = null
    private var bootWaitSeconds = 1
    private var needAdminSelect = false
    private var preparingAdmin = false
    private var session = SessionState.Unknown
    private var confirmedAt: OffsetDateTime? = null

    private fun hm(t: OffsetDateTime): String = t.format(HM)
    private fun raise() = changed?.invoke()

    // ------------------------------------------------------------------ 로그인

    fun onSession(state: SessionState, confirmedAt: OffsetDateTime?) {
        session = state
        this.confirmedAt = confirmedAt
        if (state != SessionState.LoggedIn) {
            needAdminSelect = false
            preparingAdmin = false
        }
        updateLogin()
        raise()
    }

    fun onAdminSelectNeeded(needed: Boolean) {
        needAdminSelect = needed
        updateLogin()
        raise()
    }

    /** 로그인 직후 관리 화면 준비([관리도구] 선택) 중인지. */
    fun onAdminPreparing(preparing: Boolean) {
        preparingAdmin = preparing
        updateLogin()
        raise()
    }

    private fun updateLogin() {
        login = when {
            session == SessionState.LoggedIn && needAdminSelect -> login.copy(state = StepState.Warning, detail = "[관리도구] 선택 필요", progress = null)
            session == SessionState.LoggedIn && preparingAdmin -> login.copy(state = StepState.Active, detail = "관리 화면 준비 중", progress = null)
            session == SessionState.LoggedIn -> login.copy(state = StepState.Done, detail = confirmedAt?.let { "로그인됨 · ${hm(it)}" } ?: "로그인됨", progress = null)
            session == SessionState.LoggedOut -> login.copy(state = StepState.Active, detail = "공유기 화면에서 로그인", progress = null)
            else -> login.copy(state = StepState.Pending, detail = "확인 전", progress = null)
        }
    }

    /** 새 공유기 로그인: 이전 실행의 PC 켜기·부팅 확인·원격 접속 결과를 지운다(진행 중인 단계는 그대로). */
    fun resetForNewSession() {
        if (wake.state != StepState.Active) wake = wake.copy(state = StepState.Pending, detail = "대기", progress = null)
        if (boot.state != StepState.Active) boot = boot.copy(state = StepState.Pending, detail = "대기", progress = null)
        if (remote.state != StepState.Active) remote = remote.copy(state = StepState.Pending, detail = "대기", progress = null)
        waitStartedAt = null
        wolClickText = "WOL 버튼 클릭: -"
        wolRouterText = "공유기 처리: -"
        wolBootText = "PC 부팅: -"
        raise()
    }

    // ------------------------------------------------------------------ PC 켜기

    fun onWolStarted() {
        wake = wake.copy(state = StepState.Active, detail = "시작", progress = null)
        boot = boot.copy(state = StepState.Pending, detail = "대기", progress = null)
        remote = remote.copy(state = StepState.Pending, detail = "대기", progress = null)
        wolClickText = "WOL 버튼 클릭: 대기"
        wolRouterText = "공유기 처리: 대기"
        wolBootText = "PC 부팅: 미확인"
        raise()
    }

    fun onWolStage(step: WolStep, status: StageStatus, message: String) {
        fun mark(st: StageStatus) = when (st) {
            StageStatus.Running -> "진행 중"
            StageStatus.Done -> "완료"
            StageStatus.Failed -> "실패"
            StageStatus.Unknown -> "확인 불가"
            StageStatus.Pending -> "-"
        }
        if (step == WolStep.Click) wolClickText = "WOL 버튼 클릭: " + mark(status)
        if (step == WolStep.RouterResponse) wolRouterText = "공유기 처리: " + mark(status)

        if (status == StageStatus.Running || (status == StageStatus.Done && step != WolStep.RouterResponse)) {
            var detail = when (step) {
                WolStep.SessionCheck -> "세션 확인"
                WolStep.NavigateToWol -> "WOL 화면으로 이동"
                WolStep.Match -> "대상 PC 찾기"
                WolStep.Click -> "[PC 켜기] 누르는 중"
                WolStep.Confirm -> "확인창 처리"
                WolStep.RouterResponse -> "공유기 응답 대기"
            }
            if (status == StageStatus.Done && step == WolStep.Click) detail = "버튼 누름 · 확인창 대기"
            wake = if (message.contains("누르세요")) {
                wake.copy(state = StepState.Warning, detail = "확인창 [확인] 필요", progress = null)
            } else {
                wake.copy(state = StepState.Active, detail = detail, progress = null)
            }
        }
        raise()
    }

    fun onWolOutcome(o: WolOutcome, now: OffsetDateTime) {
        wake = when {
            o.success && o.routerStatus == StageStatus.Done -> wake.copy(state = StepState.Done, detail = "공유기 처리 완료 · ${hm(now)}", progress = null)
            o.success -> wake.copy(state = StepState.Warning, detail = "요청함 · 처리 응답 미확인", progress = null)
            !o.requestAborted && o.message.contains("취소") -> wake.copy(state = StepState.Warning, detail = "취소됨", progress = null)
            else -> {
                val detail = when (o.lastStep) {
                    WolStep.SessionCheck -> "로그인 필요"
                    WolStep.NavigateToWol -> "WOL 화면 이동 실패"
                    WolStep.Match -> when (o.match?.status) {
                        WolMatchStatus.TargetNotFound -> "대상 PC 없음"
                        WolMatchStatus.Ambiguous -> "대상 구분 불가"
                        WolMatchStatus.MacMismatch -> "MAC 불일치"
                        WolMatchStatus.ListEmpty -> "등록된 PC 없음"
                        else -> "대상 찾기 실패"
                    }
                    WolStep.Click -> "누르지 않음"
                    WolStep.Confirm -> "확인창 미처리"
                    WolStep.RouterResponse -> when {
                        o.requestAborted -> "요청 취소됨"
                        o.routerStatus == StageStatus.Failed -> "공유기 오류"
                        else -> "응답 미확인"
                    }
                }
                val state = if (o.clickDone && o.routerStatus == StageStatus.Unknown) StepState.Warning else StepState.Failed
                wake.copy(state = state, detail = detail, progress = null)
            }
        }
        raise()
    }

    // ------------------------------------------------------------------ 부팅 확인 · 원격 접속

    fun onConnectStarted(mode: ConnectMode, bootWaitSeconds: Int) {
        this.bootWaitSeconds = maxOf(1, bootWaitSeconds)
        waitStartedAt = null
        boot = boot.copy(state = StepState.Active, detail = if (mode == ConnectMode.Crd) "크롬 원격 데스크톱 준비" else "준비", progress = null)
        remote = remote.copy(state = StepState.Pending, detail = "대기", progress = null)
        raise()
    }

    fun onConnectProgress(p: ConnectProgress, now: OffsetDateTime) {
        when (p.stage) {
            ConnectStage.WaitingPort -> {
                if (waitStartedAt == null) waitStartedAt = now
                updateWait(now)
            }
            ConnectStage.PortOpen -> {
                waitStartedAt = null
                boot = boot.copy(state = StepState.Done, detail = "응답 확인 · ${hm(now)}", progress = null)
                wolBootText = "PC 부팅: 응답 확인 ${now.format(HMS)}"
            }
            ConnectStage.BootCheckSkipped -> {
                // 크롬 원격 데스크톱은 열어 둔 포트가 없어 부팅을 확인하지 않는다. 확인한 척하지 않는다.
                waitStartedAt = null
                boot = boot.copy(state = StepState.Pending, detail = "확인 안 함", progress = null)
                wolBootText = "PC 부팅: 확인 안 함"
            }
            ConnectStage.LaunchingRdp -> remote = remote.copy(state = StepState.Active, detail = "원격 데스크톱 여는 중", progress = null)
            ConnectStage.LaunchingCrd -> remote = remote.copy(state = StepState.Active, detail = "크롬 원격 데스크톱 여는 중", progress = null)
            else -> Unit
        }
        raise()
    }

    /** 응답 대기 중 경과 시간 갱신(1초 주기). 변경이 있으면 true. */
    fun tick(now: OffsetDateTime): Boolean {
        if (waitStartedAt == null || boot.state != StepState.Active) return false
        updateWait(now)
        raise()
        return true
    }

    private fun updateWait(now: OffsetDateTime) {
        val start = waitStartedAt ?: return
        val elapsed = maxOf(0L, Duration.between(start, now).seconds).toInt()
        boot = boot.copy(
            state = StepState.Active,
            detail = "원격 데스크톱 응답 대기 · $elapsed/${bootWaitSeconds}초",
            progress = minOf(1.0, elapsed / bootWaitSeconds.toDouble()),
        )
    }

    fun onConnectOutcome(o: ConnectOutcome, now: OffsetDateTime) {
        waitStartedAt = null
        when {
            o.success -> {
                // 부팅 확인을 하지 않은 경우(크롬 원격 데스크톱)는 확인한 것처럼 표시하지 않는다.
                if (boot.state != StepState.Done && o.pcRespondedOnPort) boot = boot.copy(state = StepState.Done, detail = "응답 확인 · ${hm(now)}", progress = null)
                remote = remote.copy(
                    state = StepState.Done,
                    detail = if (o.mode == ConnectMode.Crd) "크롬 원격 데스크톱 열림 · ${hm(now)}" else "원격 데스크톱 앱 열림 · ${hm(now)}",
                    progress = null,
                )
            }
            o.isCancelled -> {
                if (boot.state == StepState.Active) boot = boot.copy(state = StepState.Warning, detail = "취소됨", progress = null)
                if (remote.state == StepState.Active) remote = remote.copy(state = StepState.Warning, detail = "취소됨", progress = null)
            }
            o.isTimedOut -> {
                boot = boot.copy(state = StepState.Failed, detail = "응답 없음(시간 초과)", progress = null)
                wolBootText = "PC 부팅: 응답 없음(시간 초과)"
            }
            o.pcRespondedOnPort -> remote = remote.copy(state = StepState.Failed, detail = "열기 실패", progress = null)
            else -> {
                val detail = if (o.stage == ConnectStage.Failed && o.elapsed.isZero) "설정 확인 필요" else "실패"
                boot = boot.copy(state = StepState.Failed, detail = detail, progress = null)
            }
        }
        raise()
    }

    fun summary(): String = steps.joinToString(" / ") { "${it.title} ${it.detail}" }

    companion object {
        private val HM = DateTimeFormatter.ofPattern("HH:mm")
        private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/** 동작 버튼([PC 켜고 접속]·[PC 켜기]·[PC 접속])을 누를 수 있는지 정한다(Windows 버전 UI/ActionGate.cs와 같음). */
object ActionGate {
    data class Gate(val wake: Boolean, val wakeConnect: Boolean, val connect: Boolean)

    /**
     * @param loggedIn 공유기 로그인 확인됨
     * @param busy PC 켜기·접속 작업 진행 중
     * @param exiting [종료] 진행 중
     * @param preparingAdmin 로그인 직후 관리 화면 준비([관리도구] 선택) 중 — "관리 화면이 준비됐습니다"가 뜨기 전
     */
    fun compute(loggedIn: Boolean, busy: Boolean, exiting: Boolean, preparingAdmin: Boolean): Gate {
        val idle = !busy && !exiting
        val preparing = loggedIn && preparingAdmin
        val wake = loggedIn && idle && !preparing
        // [PC 접속]은 공유기 로그인 없이도 이미 켜진 PC에 바로 접속할 수 있어야 하므로 로그인 전에는 막지 않는다.
        // 로그인 뒤 관리 화면을 준비하는 동안에만 다른 두 버튼과 함께 막는다.
        val connect = idle && !preparing
        return Gate(wake, wake, connect)
    }

    /**
     * [PC 접속] 버튼을 깜빡여 눈에 띄게 할 때인지. PC가 켜진 것이 확인됐을 때만 깜빡인다.
     * 응답이 없거나(꺼졌을 수도, 포트가 막혔을 수도) 아직 확인 전이면 깜빡이지 않는다.
     */
    fun shouldBlinkConnect(setting: Boolean, power: PcPowerState, connectEnabled: Boolean, busy: Boolean, exiting: Boolean): Boolean =
        setting && power == PcPowerState.On && connectEnabled && !busy && !exiting

    /**
     * [공유기 화면] 버튼을 주황색으로 깜빡여 로그인하라고 알릴 때인지.
     * 공유기가 "인증되지 않음"이라고 확정한 경우(로그아웃·세션 만료)만 깜빡인다. 아직 확인 전이면 깜빡이지 않는다.
     * 공유기 화면을 띄워 둔 동안에는 그 버튼이 보이지 않으므로 의미가 없고, [종료] 중에는 멈춘다.
     */
    fun shouldBlinkRouter(session: SessionState, routerVisible: Boolean, exiting: Boolean): Boolean =
        session == SessionState.LoggedOut && !routerVisible && !exiting
}

data class ModeOption(val mode: ConnectMode, val title: String, val detail: String, val enabled: Boolean)

/** 접속 방식 선택지. 방식마다 그 방식에 필요한 설정만 검사한다. */
object ModeOptions {
    fun forSettings(s: AppSettings): List<ModeOption> {
        val list = mutableListOf(build(s, ConnectMode.Direct))
        // 크롬 원격 데스크톱은 설정에서 켠 경우에만 선택지에 넣는다(쓰지 않는 사람에게 빈 항목을 보이지 않도록).
        if (s.useCrd) list.add(build(s, ConnectMode.Crd))
        return list
    }

    fun build(s: AppSettings, mode: ConnectMode): ModeOption {
        val title = if (mode == ConnectMode.Crd) "크롬 원격 데스크톱" else "일반 접속"
        if (s.validateConnect(mode).isNotEmpty()) return ModeOption(mode, title, "설정 필요 · ⚙ 설정에서 입력", false)
        val detail = if (mode == ConnectMode.Crd) crdDetail(s) else "원격 데스크톱 앱 · " + InputRules.hostPort(s.publicHost, s.publicRdpPort)
        return ModeOption(mode, title, detail, true)
    }

    private fun crdDetail(s: AppSettings): String {
        val where = when {
            InputRules.normalizeCrdHostId(s.crdHostId) == null -> "기기 목록에서 고르기"
            s.crdOpen == CrdOpenWith.App -> "앱의 기기 목록에서 고르기"
            else -> "브라우저로 저장된 기기 바로 연결"
        }
        val check = if (s.crdCheck == CrdBootCheck.Direct) " · 부팅 확인: 일반 접속 주소" else " · 부팅 확인 없음"
        return where + check
    }
}
