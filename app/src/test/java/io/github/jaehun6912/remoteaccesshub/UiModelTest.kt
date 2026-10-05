package io.github.jaehun6912.remoteaccesshub

import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.ConnectMode
import io.github.jaehun6912.remoteaccesshub.core.SessionProbeResult
import io.github.jaehun6912.remoteaccesshub.core.SessionState
import io.github.jaehun6912.remoteaccesshub.core.SessionTracker
import io.github.jaehun6912.remoteaccesshub.core.WolMatchResult
import io.github.jaehun6912.remoteaccesshub.core.WolMatchStatus
import io.github.jaehun6912.remoteaccesshub.router.StageStatus
import io.github.jaehun6912.remoteaccesshub.router.WolOutcome
import io.github.jaehun6912.remoteaccesshub.router.WolStep
import io.github.jaehun6912.remoteaccesshub.services.ConnectOutcome
import io.github.jaehun6912.remoteaccesshub.services.ConnectProgress
import io.github.jaehun6912.remoteaccesshub.services.ConnectStage
import io.github.jaehun6912.remoteaccesshub.services.PcPowerState
import io.github.jaehun6912.remoteaccesshub.ui.ActionGate
import io.github.jaehun6912.remoteaccesshub.ui.FlowTracker
import io.github.jaehun6912.remoteaccesshub.ui.ModeOptions
import io.github.jaehun6912.remoteaccesshub.ui.Palettes
import io.github.jaehun6912.remoteaccesshub.ui.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 화면 상태 모델(Windows 검사 UiModelTests·SessionTrackerTests·ConnectBlinkTests·ThemeTests를 옮김). */
class UiModelTest {
    private val t = OffsetDateTime.of(2026, 9, 14, 15, 37, 0, 0, ZoneOffset.ofHours(9))

    // ------------------------------------------------------------------ 세션 래치

    @Test
    fun session_starts_unknown_and_ok_latches_logged_in() {
        val s = SessionTracker()
        assertEquals(SessionState.Unknown, s.state)
        assertFalse(s.isLoggedIn)
        s.apply(SessionProbeResult.Ok, "session/info")
        assertTrue(s.isLoggedIn)
        assertNotNull(s.lastConfirmedAt)
    }

    @Test
    fun unavailable_never_changes_session_state() {
        val s = SessionTracker()
        s.apply(SessionProbeResult.Unavailable, "hidden")
        assertEquals(SessionState.Unknown, s.state)
        s.apply(SessionProbeResult.Ok, "ok")
        repeat(10) { s.apply(SessionProbeResult.Unavailable, "판독 불가") }
        assertTrue(s.isLoggedIn)
        assertEquals(10, s.consecutiveUnavailable)
        assertTrue(s.describe().contains("상태 유지"))
        s.apply(SessionProbeResult.Unauthenticated, "-31998")
        assertEquals(SessionState.LoggedOut, s.state)
        s.apply(SessionProbeResult.Unavailable, "x")
        assertEquals(SessionState.LoggedOut, s.state)
    }

    @Test
    fun session_change_events_and_reset() {
        val s = SessionTracker()
        val events = mutableListOf<Pair<SessionState, SessionState>>()
        s.onStateChanged { o, n, _ -> events.add(o to n) }
        s.apply(SessionProbeResult.Ok, "ok")
        s.apply(SessionProbeResult.Ok, "ok again") // 알림 없음
        s.apply(SessionProbeResult.Unauthenticated, "expired")
        assertEquals(listOf(SessionState.Unknown to SessionState.LoggedIn, SessionState.LoggedIn to SessionState.LoggedOut), events)
        s.apply(SessionProbeResult.Ok, "ok")
        s.reset("url changed")
        assertEquals(SessionState.Unknown, s.state)
        assertNull(s.lastConfirmedAt)
    }

    // ------------------------------------------------------------------ 단계 표시

    @Test
    fun initial_state_is_pending_and_login_prompt_when_logged_out() {
        val f = FlowTracker()
        assertTrue(f.steps.all { it.state == StepState.Pending })
        f.onSession(SessionState.LoggedOut, null)
        assertEquals(StepState.Active, f.login.state)
        assertTrue(f.login.detail.contains("로그인"))
    }

    @Test
    fun admin_select_needed_is_warning_then_done() {
        val f = FlowTracker()
        f.onSession(SessionState.LoggedIn, t)
        assertEquals(StepState.Done, f.login.state)
        assertTrue(f.login.detail.contains("15:37"))
        f.onAdminSelectNeeded(true)
        assertEquals(StepState.Warning, f.login.state)
        f.onAdminSelectNeeded(false)
        assertEquals(StepState.Done, f.login.state)
    }

    @Test
    fun full_success_path_marks_all_done_and_keeps_legacy_texts() {
        val f = FlowTracker()
        f.onSession(SessionState.LoggedIn, t)
        f.onWolStarted()
        assertEquals(StepState.Active, f.wake.state)
        f.onWolStage(WolStep.NavigateToWol, StageStatus.Running, "이동")
        assertTrue(f.wake.detail.contains("WOL 화면"))
        f.onWolStage(WolStep.Click, StageStatus.Done, "clicked")
        f.onWolStage(WolStep.RouterResponse, StageStatus.Done, "ok")
        f.onWolOutcome(WolOutcome(true, WolStep.RouterResponse, "ok", true, StageStatus.Done, null), t)
        assertEquals(StepState.Done, f.wake.state)
        assertEquals("WOL 버튼 클릭: 완료", f.wolClickText)
        assertEquals("공유기 처리: 완료", f.wolRouterText)

        f.onConnectStarted(ConnectMode.Direct, 180)
        f.onConnectProgress(ConnectProgress(ConnectStage.WaitingPort, "대기"), t)
        assertTrue(f.tick(t.plusSeconds(45)))
        assertEquals(StepState.Active, f.boot.state)
        assertEquals(0.25, f.boot.progress!!, 0.01)
        assertTrue(f.boot.detail.contains("45/180"))
        f.onConnectProgress(ConnectProgress(ConnectStage.PortOpen, "열림"), t.plusSeconds(60))
        assertEquals(StepState.Done, f.boot.state)
        assertTrue(f.wolBootText.startsWith("PC 부팅: 응답 확인"))
        assertFalse(f.tick(t.plusSeconds(61)))
        f.onConnectProgress(ConnectProgress(ConnectStage.LaunchingRdp, "실행"), t.plusSeconds(61))
        f.onConnectOutcome(ConnectOutcome(ConnectStage.Done, true, "ok", Duration.ofSeconds(61), true, ConnectMode.Direct), t.plusSeconds(61))
        assertTrue(f.steps.all { it.state == StepState.Done })
    }

    @Test
    fun manual_confirm_needed_is_warning() {
        val f = FlowTracker()
        f.onWolStarted()
        f.onWolStage(WolStep.Confirm, StageStatus.Running, "공유기가 확인창을 표시했습니다. 공유기 화면에서 [확인]을 누르세요.")
        assertEquals(StepState.Warning, f.wake.state)
    }

    @Test
    fun wake_failures_are_explained() {
        for ((status, expected) in listOf(WolMatchStatus.TargetNotFound to "대상 PC 없음", WolMatchStatus.Ambiguous to "대상 구분 불가", WolMatchStatus.MacMismatch to "MAC 불일치")) {
            val f = FlowTracker()
            f.onWolStarted()
            f.onWolOutcome(WolOutcome.fail(WolStep.Match, "msg", WolMatchResult(status, "msg", null, emptyList())), t)
            assertEquals(StepState.Failed, f.wake.state)
            assertEquals(expected, f.wake.detail)
        }
    }

    @Test
    fun cancel_and_timeout_are_distinguished() {
        val f = FlowTracker()
        f.onWolStarted()
        f.onWolOutcome(WolOutcome.fail(WolStep.Click, "PC 켜기 작업이 취소되었습니다."), t)
        assertEquals(StepState.Warning, f.wake.state)

        f.onConnectStarted(ConnectMode.Direct, 10)
        f.onConnectProgress(ConnectProgress(ConnectStage.WaitingPort, "대기"), t)
        f.onConnectOutcome(ConnectOutcome(ConnectStage.Cancelled, false, "취소", Duration.ofSeconds(3), false, ConnectMode.Direct), t)
        assertEquals(StepState.Warning, f.boot.state)

        f.onConnectStarted(ConnectMode.Direct, 10)
        f.onConnectOutcome(ConnectOutcome(ConnectStage.TimedOut, false, "시간 초과", Duration.ofSeconds(10), false, ConnectMode.Direct), t)
        assertEquals(StepState.Failed, f.boot.state)
        assertTrue(f.wolBootText.contains("응답 없음"))
    }

    @Test
    fun launch_failure_after_boot_marks_only_the_remote_step() {
        val f = FlowTracker()
        f.onConnectStarted(ConnectMode.Direct, 60)
        f.onConnectProgress(ConnectProgress(ConnectStage.PortOpen, "열림"), t)
        f.onConnectOutcome(ConnectOutcome(ConnectStage.Failed, false, "원격 데스크톱 앱이 없습니다", Duration.ofSeconds(5), true, ConnectMode.Direct), t)
        assertEquals(StepState.Done, f.boot.state)
        assertEquals(StepState.Failed, f.remote.state)

        val g = FlowTracker()
        g.onConnectStarted(ConnectMode.Direct, 60)
        g.onConnectOutcome(ConnectOutcome(ConnectStage.Failed, false, "설정", Duration.ZERO, false, ConnectMode.Direct), t)
        assertEquals("설정 확인 필요", g.boot.detail)
    }

    @Test
    fun new_wake_resets_later_steps_and_new_session_clears_results() {
        val f = FlowTracker()
        f.onConnectStarted(ConnectMode.Direct, 10)
        f.onConnectProgress(ConnectProgress(ConnectStage.PortOpen, "열림"), t)
        f.onConnectOutcome(ConnectOutcome(ConnectStage.Done, true, "ok", Duration.ofSeconds(1), true, ConnectMode.Direct), t)
        assertEquals(StepState.Done, f.remote.state)
        f.resetForNewSession()
        assertEquals(StepState.Pending, f.boot.state)
        assertEquals(StepState.Pending, f.remote.state)
        assertEquals("PC 부팅: -", f.wolBootText)

        f.onWolStarted()
        f.resetForNewSession()
        assertEquals(StepState.Active, f.wake.state) // 진행 중인 단계는 그대로
    }

    @Test
    fun crd_without_boot_check_does_not_claim_the_pc_was_checked() {
        val f = FlowTracker()
        f.onConnectStarted(ConnectMode.Crd, 180)
        assertEquals(StepState.Active, f.boot.state)
        f.onConnectProgress(ConnectProgress(ConnectStage.BootCheckSkipped, "확인 없이 엽니다"), t)
        assertEquals(StepState.Pending, f.boot.state)
        assertEquals("확인 안 함", f.boot.detail)
        assertEquals("PC 부팅: 확인 안 함", f.wolBootText)
        assertFalse(f.tick(t.plusSeconds(30)))
        f.onConnectProgress(ConnectProgress(ConnectStage.LaunchingCrd, "여는 중"), t)
        assertEquals(StepState.Active, f.remote.state)
        f.onConnectOutcome(ConnectOutcome(ConnectStage.Done, true, "열었습니다", Duration.ofSeconds(1), false, ConnectMode.Crd), t)
        assertEquals(StepState.Done, f.remote.state)
        assertTrue(f.remote.detail.contains("크롬 원격 데스크톱 열림"))
        assertEquals(StepState.Pending, f.boot.state) // 확인하지 않은 단계는 완료로 바꾸지 않는다
    }

    @Test
    fun login_step_shows_preparing_then_done() {
        val f = FlowTracker()
        f.onSession(SessionState.LoggedIn, t)
        f.onAdminPreparing(true)
        assertEquals(StepState.Active, f.login.state)
        assertEquals("관리 화면 준비 중", f.login.detail)
        f.onAdminSelectNeeded(true)
        assertEquals(StepState.Warning, f.login.state)
        f.onAdminSelectNeeded(false)
        f.onAdminPreparing(false)
        assertEquals(StepState.Done, f.login.state)
        f.onAdminPreparing(true)
        f.onSession(SessionState.LoggedOut, null)
        f.onSession(SessionState.LoggedIn, t)
        assertEquals(StepState.Done, f.login.state) // 로그아웃하면 준비 표시가 지워짐
    }

    // ------------------------------------------------------------------ 버튼·선택지

    @Test
    fun buttons_follow_login_preparation_and_work_state() {
        //               loggedIn busy  exiting preparing → wake  connect
        val cases = listOf(
            listOf(false, false, false, false, false, true), // 로그인 전: [PC 접속]만(이미 켜진 PC에 바로 접속)
            listOf(true, false, false, true, false, false), // 로그인 뒤 관리 화면 준비 중: 셋 다 막음
            listOf(true, false, false, false, true, true), // 준비 완료
            listOf(true, true, false, false, false, false), // 작업 중
            listOf(true, false, true, false, false, false), // 종료 중
            listOf(false, false, false, true, false, true), // 준비 표시가 남아도 로그아웃이면 [PC 접속]은 막지 않음
        )
        for (c in cases) {
            val g = ActionGate.compute(c[0], c[1], c[2], c[3])
            assertEquals(c.toString(), c[4], g.wake)
            assertEquals(c.toString(), c[4], g.wakeConnect)
            assertEquals(c.toString(), c[5], g.connect)
        }
    }

    @Test
    fun blinks_only_when_the_pc_is_known_to_be_on_and_the_button_works() {
        assertTrue(ActionGate.shouldBlinkConnect(true, PcPowerState.On, connectEnabled = true, busy = false, exiting = false))
        for (state in listOf(PcPowerState.NoAnswer, PcPowerState.Unknown, PcPowerState.Checking, PcPowerState.Disabled)) {
            assertFalse(ActionGate.shouldBlinkConnect(true, state, connectEnabled = true, busy = false, exiting = false))
        }
        assertFalse(ActionGate.shouldBlinkConnect(true, PcPowerState.On, connectEnabled = false, busy = false, exiting = false))
        assertFalse(ActionGate.shouldBlinkConnect(true, PcPowerState.On, connectEnabled = true, busy = true, exiting = false))
        assertFalse(ActionGate.shouldBlinkConnect(true, PcPowerState.On, connectEnabled = true, busy = false, exiting = true))
        assertFalse(ActionGate.shouldBlinkConnect(false, PcPowerState.On, connectEnabled = true, busy = false, exiting = false))
        assertTrue(AppSettings().blinkConnectWhenPcOn)
    }

    @Test
    fun mode_options_check_only_their_own_settings() {
        val s = AppSettings(publicHost = "myhome.iptime.org", publicRdpPort = 41000)
        val o = ModeOptions.forSettings(s)
        assertEquals(1, o.size) // 크롬 원격 데스크톱은 켠 경우에만 보인다(VPN 접속은 안드로이드 버전에 없음)
        assertEquals(ConnectMode.Direct, o[0].mode)
        assertTrue(o[0].enabled)
        assertTrue(o[0].detail.endsWith("myhome.iptime.org:41000"))

        val crd = ModeOptions.forSettings(s.copy(useCrd = true, crdHostId = "7f3a1b9c2d4e5f60"))
        assertEquals(2, crd.size)
        assertEquals(ConnectMode.Crd, crd[1].mode)
        assertEquals("브라우저로 저장된 기기 바로 연결 · 부팅 확인 없음", crd[1].detail)
        assertEquals("앱의 기기 목록에서 고르기 · 부팅 확인 없음", ModeOptions.build(s.copy(useCrd = true, crdHostId = "7f3a1b9c2d4e5f60", crdOpenWith = "app"), ConnectMode.Crd).detail)
        assertTrue(ModeOptions.build(s.copy(useCrd = true, crdHostId = "", crdBootCheckMode = "direct"), ConnectMode.Crd).detail.contains("부팅 확인: 일반 접속 주소"))

        val empty = ModeOptions.build(AppSettings(), ConnectMode.Direct)
        assertFalse(empty.enabled)
        assertTrue(empty.detail.startsWith("설정 필요"))
    }

    // ------------------------------------------------------------------ 테마(Windows와 같은 명암비 기준)

    @Test
    fun text_contrast_meets_wcag() {
        for (p in listOf(Palettes.Dark, Palettes.Light)) {
            assertTrue(p.name, Palettes.contrast(p.text, p.surface) >= 7)
            assertTrue(p.name, Palettes.contrast(p.text, p.background) >= 7)
            assertTrue(p.name, Palettes.contrast(p.subText, p.surface) >= 4.5)
            assertTrue(p.name, Palettes.contrast(p.subText, p.background) >= 4.5)
            assertTrue(p.name, Palettes.contrast(p.onAccent, p.accent) >= 4.5)
            assertTrue(p.name, Palettes.contrast(p.logText, p.logBackground) >= 7)
            for (c in listOf(p.success, p.warning, p.danger, p.info, p.accent)) assertTrue(p.name, Palettes.contrast(c, p.surface) >= 4.5)
        }
        assertEquals(Palettes.Dark, Palettes.resolve("dark", false))
        assertEquals(Palettes.Light, Palettes.resolve("system", false))
        assertEquals(Palettes.Dark, Palettes.resolve("모름", true))
    }
}
