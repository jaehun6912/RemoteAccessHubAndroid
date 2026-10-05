package io.github.jaehun6912.remoteaccesshub

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jaehun6912.remoteaccesshub.core.ConnectMode
import io.github.jaehun6912.remoteaccesshub.core.SessionState
import io.github.jaehun6912.remoteaccesshub.router.NavStatus
import io.github.jaehun6912.remoteaccesshub.services.CrdLauncher
import io.github.jaehun6912.remoteaccesshub.services.CrdOpenTarget
import io.github.jaehun6912.remoteaccesshub.services.RdpLauncher
import io.github.jaehun6912.remoteaccesshub.services.RdpOpenTarget
import io.github.jaehun6912.remoteaccesshub.services.RemoteLinks
import io.github.jaehun6912.remoteaccesshub.ui.AppController
import io.github.jaehun6912.remoteaccesshub.ui.LaunchOptions
import io.github.jaehun6912.remoteaccesshub.ui.MainActivity
import io.github.jaehun6912.remoteaccesshub.ui.StepState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 모의 공유기 자체검사(Windows 버전의 --selftest에 해당).
 * 실제 WebView에 모의 ipTIME 화면(Windows 자체검사와 같은 index.html)을 띄우고, 로그인 → [관리도구] → WOL → 확인창 → 접속 → [종료]를 끝까지 돌린다.
 * 원격 데스크톱 앱·크롬 원격 데스크톱 실행만 기록용 가짜로 바꾸고, 포트 확인은 모의 공유기의 가짜 포트에 실제로 연결한다.
 */
@RunWith(AndroidJUnit4::class)
class MockRouterSelfTest {
    private class RecordingRdp : RdpLauncher {
        val launches = mutableListOf<Pair<String, Int>>()
        override fun launch(host: String, port: Int): RdpOpenTarget {
            RemoteLinks.rdpUri(host, port)
            launches.add(host to port)
            return RdpOpenTarget.UriScheme
        }
    }

    private class RecordingCrd : CrdLauncher {
        val opened = mutableListOf<String>()
        override fun open(hostId: String?): CrdOpenTarget {
            opened.add(RemoteLinks.crdUrl(hostId))
            return CrdOpenTarget.Browser
        }
    }

    private val rdp = RecordingRdp()
    private val crd = RecordingCrd()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        MainActivity.controllerFactory = null
    }

    private fun start(): AppController {
        MainActivity.controllerFactory = { a -> AppController(a, LaunchOptions(mock = true, selfTest = true), rdpLauncher = rdp, crdLauncher = crd) }
        val sc = ActivityScenario.launch(MainActivity::class.java)
        scenario = sc
        var c: AppController? = null
        sc.onActivity { c = it.controller }
        return c!!
    }

    private suspend fun <T> main(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun waitUntil(what: String, timeoutMs: Long = 30_000, cond: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (main { cond() }) return
            delay(200)
        }
        throw AssertionError("시간 초과: $what")
    }

    private suspend fun js(c: AppController, expression: String): String? = main { c.browser.evaluateAsync(expression) }

    private suspend fun mockState(c: AppController): JsonObject =
        Json.parseToJsonElement(js(c, "window.__mock.getState()") ?: "{}").jsonObject

    /** 로그인 화면이 뜬 뒤 모의 로그인을 하고, 관리 화면 준비가 끝날 때까지 기다린다. */
    private suspend fun loginAndSettle(c: AppController, options: String? = null) {
        assertTrue(main { c.initialized.await() })
        waitUntil("로그인 필요 판정") { c.browser.session.state == SessionState.LoggedOut }
        assertTrue("처음에는 공유기 화면을 보여 준다", main { c.routerVisible })
        waitUntil("모의 화면 준비") { js(c, "window.__mock ? window.__mock.getState() : null")?.contains("\"view\":\"login\"") == true }
        if (options != null) js(c, "window.__mock.setOptions($options)")
        js(c, "window.__mock.login()")
        waitUntil("로그인 판정") { c.browser.session.isLoggedIn }
        waitUntil("관리 화면 준비", 60_000) { c.lastSettle != null && !c.preparingAdmin }
        assertEquals(NavStatus.Ok, main { c.lastSettle!!.status })
        assertTrue("로그인 뒤에는 공유기 화면을 숨긴다", !main { c.routerVisible })
        assertTrue("숨긴 공유기 화면은 PC 화면 너비로 그린다", main { c.browser.frame.isWide })
        assertEquals(StepState.Done, main { c.flow.login.state })
    }

    @Test
    fun full_flow_with_semantics_clicks() = runBlocking {
        val c = start()
        loginAndSettle(c)

        // [PC 켜고 접속] → 일반 접속
        val (wol, connect) = main { c.runWakeAndConnect(ConnectMode.Direct) }
        assertTrue(wol.message, wol.success)
        assertTrue(connect!!.message, connect.success)
        val mock = c.mockRouter as MockRouterServer
        assertEquals(listOf("00:11:22:33:44:02"), mock.signals) // MY-PC 행만 켬
        assertEquals(listOf("127.0.0.1" to mock.fakeRdpPort), rdp.launches)
        assertTrue(main { c.flow.steps.all { it.state == StepState.Done } })

        // [PC 접속] → 크롬 원격 데스크톱(부팅 확인 없음)
        main { c.applySettings(c.settings.copy(useCrd = true, crdHostId = "7f3a1b9c2d4e5f60"), save = false) }
        val crdOutcome = main { c.runConnect(ConnectMode.Crd) }
        assertTrue(crdOutcome.message, crdOutcome.success)
        assertEquals(listOf("${RemoteLinks.CRD_ACCESS_URL}/session/7f3a1b9c2d4e5f60"), crd.opened)

        // [종료]: 공유기 로그아웃을 공유기 응답으로 확인
        val exit = main { c.prepareExit() }
        assertTrue(exit.message, exit.confirmed)
        assertEquals(1, mock.logoutCount)
    }

    @Test
    fun touch_path_for_admin_tool_and_confirm_dialog() = runBlocking {
        val c = start()
        // 선택 화면 카드와 확인창 버튼을 접근성 버튼으로 노출하지 않게 해, 화면 텍스트·노드 위치를 실제 터치로 누르게 한다.
        loginAndSettle(c, "{modeSelectButtons:false,dialogButtonRole:false}")
        val wol = main { c.runWake(skipNavigation = false) }
        assertTrue(wol.message, wol.success)
        val state = mockState(c)
        val taps = state["taps"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("관리도구를 터치로 누름: $taps", state["adminChosen"]!!.jsonPrimitive.content == "true" || taps.any { it == "ptr:admin" })
        assertTrue("확인창 [확인]을 터치로 누름: $taps", taps.contains("ptr:dok"))
        assertEquals(1, state["confirmCount"]!!.jsonPrimitive.int)
        assertEquals(0, state["cancelCount"]!!.jsonPrimitive.int)
        assertEquals(listOf("00:11:22:33:44:02"), (c.mockRouter as MockRouterServer).signals)
    }

    @Test
    fun aborted_wol_request_is_retried() = runBlocking {
        val c = start()
        // 실기기 기록 재현: 처음 두 번의 wol/signal 요청을 공유기 화면이 곧바로 취소한다.
        loginAndSettle(c, "{abortSignals:2}")
        val wol = main { c.runWake(skipNavigation = false) }
        assertTrue(wol.message, wol.success)
        val state = mockState(c)
        assertEquals(2, state["signalAborts"]!!.jsonPrimitive.int)
        assertEquals(1, state["signalOk"]!!.jsonPrimitive.int)
    }

    @Test
    fun missing_target_never_wakes_anything() = runBlocking {
        val c = start()
        loginAndSettle(c)
        main { c.applySettings(c.settings.copy(wolPcName = "GAME-PC"), save = false) }
        val wol = main { c.runWake(skipNavigation = false) }
        assertTrue(!wol.success)
        assertTrue((c.mockRouter as MockRouterServer).signals.isEmpty())
        assertTrue("실패하면 공유기 화면을 띄워 둔다", main { c.routerVisible })
    }
}
