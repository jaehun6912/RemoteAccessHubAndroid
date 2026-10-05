package io.github.jaehun6912.remoteaccesshub

import io.github.jaehun6912.remoteaccesshub.TestPages.FAKE_MAC
import io.github.jaehun6912.remoteaccesshub.TestPages.realConfirmDialog
import io.github.jaehun6912.remoteaccesshub.TestPages.realWolPage
import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.CancelSignal
import io.github.jaehun6912.remoteaccesshub.core.SessionProbeResult
import io.github.jaehun6912.remoteaccesshub.core.SessionState
import io.github.jaehun6912.remoteaccesshub.core.WolMatchStatus
import io.github.jaehun6912.remoteaccesshub.router.StageStatus
import io.github.jaehun6912.remoteaccesshub.router.WolAutomation
import io.github.jaehun6912.remoteaccesshub.router.WolStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가짜 공유기 화면으로 PC 켜기 흐름 전체를 검사한다.
 * 안드로이드 버전은 공유기 API 관찰을 페이지 안 스크립트로 새로 만들었으므로, 그 기록으로 결과를 판정하는 부분을 특히 확인한다.
 */
class WolAutomationTest {
    private val log = AppLog(null)
    private val settings = AppSettings(routerUrl = "http://r.example/", wolPcName = "MY-PC")
    private val rows = arrayOf("OFFICE-PC" to "02:00:AA:BB:CC:01", "MY-PC" to FAKE_MAC)

    private fun make(): Pair<FakeRouterDriver, WolAutomation> {
        val d = FakeRouterDriver(log)
        d.page = realWolPage(*rows)
        return d to WolAutomation(d, log) { settings }
    }

    @Test
    fun wake_clicks_only_the_target_row_and_reports_router_processed() = runBlocking {
        val (d, w) = make()
        d.onClick = { id ->
            when (id) {
                "refresh" -> d.net.add("wol/show")
                "wake1" -> d.net.add("wol/signal", params = "[\"$FAKE_MAC\"]")
            }
        }
        val o = w.wake(skipNavigation = false, signal = CancelSignal())
        assertTrue(o.message, o.success)
        assertEquals(StageStatus.Done, o.routerStatus)
        assertEquals(listOf("refresh", "wake1"), d.clicks)
    }

    @Test
    fun aborted_request_is_sent_again_after_reloading_the_list() = runBlocking {
        val (d, w) = make()
        var wakes = 0
        d.onClick = { id ->
            when (id) {
                "refresh" -> d.net.add("wol/show")
                "wake1" -> {
                    wakes++
                    if (wakes == 1) {
                        // 응답 없이 페이지가 취소한 요청(net::ERR_ABORTED): 공유기 처리 여부를 알 수 없다.
                        d.net.add("wol/signal", status = null, done = false, failed = true, err = "net::ERR_ABORTED", checked = false, ok = null)
                    } else {
                        d.net.add("wol/signal")
                    }
                }
            }
        }
        val o = w.wake(skipNavigation = false, signal = CancelSignal())
        assertTrue(o.message, o.success)
        assertEquals(2, wakes)
        assertEquals(listOf("refresh", "wake1", "refresh", "wake1"), d.clicks)
    }

    @Test
    fun response_received_before_the_page_closed_counts_as_processed() = runBlocking {
        val (d, w) = make()
        d.onClick = { id ->
            when (id) {
                "refresh" -> d.net.add("wol/show")
                // 공유기가 200으로 응답한 뒤 페이지가 연결을 닫음(본문 판독 불가) → 다시 보내지 않는다.
                "wake1" -> d.net.add("wol/signal", status = 200, done = false, failed = true, err = "net::ERR_ABORTED", checked = true, ok = null)
            }
        }
        val o = w.wake(skipNavigation = false, signal = CancelSignal())
        assertTrue(o.message, o.success)
        assertEquals(StageStatus.Done, o.routerStatus)
        assertEquals(1, d.clicks.count { it == "wake1" })
    }

    @Test
    fun router_unauthenticated_answer_logs_the_session_out() = runBlocking {
        val (d, w) = make()
        d.session.apply(SessionProbeResult.Ok, "test")
        d.onClick = { id ->
            when (id) {
                "refresh" -> d.net.add("wol/show")
                "wake1" -> d.net.add("wol/signal", ok = false, code = -31998, msg = "Unauthenticated")
            }
        }
        val o = w.wake(skipNavigation = false, signal = CancelSignal())
        assertFalse(o.success)
        assertEquals(StageStatus.Failed, o.routerStatus)
        assertTrue(o.message.contains("다시 로그인"))
        assertEquals(SessionState.LoggedOut, d.session.state)
    }

    @Test
    fun missing_target_never_clicks_a_wake_button() = runBlocking {
        val d = FakeRouterDriver(log)
        d.page = realWolPage("OFFICE-PC" to "02:00:AA:BB:CC:01", "NAS" to "02:00:AA:BB:CC:03")
        d.onClick = { id -> if (id == "refresh") d.net.add("wol/show") }
        val o = WolAutomation(d, log) { settings }.wake(skipNavigation = false, signal = CancelSignal())
        assertFalse(o.success)
        assertEquals(WolStep.Match, o.lastStep)
        assertEquals(WolMatchStatus.TargetNotFound, o.match?.status)
        assertFalse(d.clicks.any { it.startsWith("wake") })
    }

    @Test
    fun expired_session_stops_before_touching_the_page() = runBlocking {
        val (d, w) = make()
        d.sessionResult = SessionProbeResult.Unauthenticated
        val o = w.wake(skipNavigation = false, signal = CancelSignal())
        assertFalse(o.success)
        assertEquals(WolStep.SessionCheck, o.lastStep)
        assertTrue(d.clicks.isEmpty())
    }

    @Test
    fun confirm_dialog_is_answered_with_ok_automatically() = runBlocking {
        val (d, w) = make()
        d.onClick = { id ->
            when (id) {
                "refresh" -> d.net.add("wol/show")
                "wake1" -> d.page = realConfirmDialog(buttonRole = true)
                "ok" -> {
                    d.page = realWolPage(*rows)
                    d.net.add("wol/signal")
                }
            }
        }
        val o = w.wake(skipNavigation = false, signal = CancelSignal())
        assertTrue(o.message, o.success)
        assertEquals(listOf("refresh", "wake1", "ok"), d.clicks)
        assertFalse(d.clicks.contains("cancel"))
    }

    @Test
    fun cancel_signal_stops_the_job() = runBlocking {
        val (d, w) = make()
        val sig = CancelSignal()
        d.onClick = { id ->
            if (id == "refresh") d.net.add("wol/show")
            if (id == "wake1") sig.cancel() // 누른 직후 사용자가 취소
        }
        try {
            w.wake(skipNavigation = false, signal = sig)
            throw AssertionError("취소되지 않음")
        } catch (_: io.github.jaehun6912.remoteaccesshub.core.OperationCanceledException) {
            // 기대한 결과
        }
    }
}
