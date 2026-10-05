package io.github.jaehun6912.remoteaccesshub

import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.Paragraph
import io.github.jaehun6912.remoteaccesshub.core.ProbeRect
import io.github.jaehun6912.remoteaccesshub.core.ProbeSnapshot
import io.github.jaehun6912.remoteaccesshub.core.SemanticNode
import io.github.jaehun6912.remoteaccesshub.router.DiagnosticsExporter
import io.github.jaehun6912.remoteaccesshub.router.WolAutomation
import io.github.jaehun6912.remoteaccesshub.services.PcPowerState
import io.github.jaehun6912.remoteaccesshub.services.PowerRules
import io.github.jaehun6912.remoteaccesshub.services.PowerTarget
import io.github.jaehun6912.remoteaccesshub.services.PowerWatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 기록 가림·진단·판독 결과 해석·전원 배지(Windows 검사 MiscTests·PowerRulesTests를 옮김). */
class MiscTest {
    @Test
    fun log_redaction_hides_secrets_queries_and_macs() {
        val s = AppLog.redact("login password=hunter2 cookie: efm_session_id=abc token=xyz url http://r/x?sid=123 mac 00:11:22:33:44:55")
        assertFalse(s.contains("hunter2"))
        assertFalse(s.contains("abc"))
        assertFalse(s.contains("xyz"))
        assertFalse(s.contains("sid=123"))
        assertFalse(s.contains("22:33:44"))
    }

    @Test
    fun log_file_is_written_with_redaction() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rah-log-" + System.nanoTime())
        val log = AppLog(dir)
        log.info("password=secret 페이지")
        log.close()
        val text = dir.listFiles()!!.single().readText()
        assertTrue(text.contains("페이지"))
        assertFalse(text.contains("secret"))
        dir.deleteRecursively()
    }

    @Test
    fun diagnostics_masks_hosts_and_urls() {
        assertEquals("192.168.*.*", DiagnosticsExporter.maskHost("192.168.0.1"))
        val m = DiagnosticsExporter.maskHost("myhome.iptime.org")
        assertFalse(m.contains("myhome"))
        assertTrue(m.endsWith("org"))
        val u = DiagnosticsExporter.maskUrl("http://myhome.iptime.org:8080/ui/#/wol?x=1")
        assertFalse(u.contains("myhome"))
        assertTrue(u.contains(":8080"))
        assertFalse(u.contains("x=1"))
    }

    @Test
    fun probe_snapshot_parses_script_result_wrapped_as_json_string() {
        val inner = "{\"url\":\"http://r/ui/#/wol\",\"flutter\":true,\"semanticsCount\":2,\"nodes\":[{\"index\":0,\"id\":\"a\",\"role\":\"button\",\"label\":\"PC 켜기\",\"rect\":{\"x\":1,\"y\":2,\"w\":3,\"h\":4}}],\"paragraphs\":[],\"markers\":{\"passwordInput\":false},\"error\":null,\"futureField\":1}"
        val wrapped = "\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val snap = ProbeSnapshot.parse(wrapped)
        assertNull(snap.error)
        assertTrue(snap.flutter)
        assertEquals(1, snap.nodes.size)
        assertEquals(3.0, snap.nodes[0].rect.w, 0.0)
        assertTrue(snap.nodes[0].isButton)
        assertNotNull(ProbeSnapshot.parse("null").error)
        assertNotNull(ProbeSnapshot.parse("\"not json\"").error)
    }

    @Test
    fun confirm_button_is_chosen_below_dialog_text() {
        val snap = ProbeSnapshot()
        snap.paragraphs.add(Paragraph(0, "PC를 켜시겠습니까?", ProbeRect(330.0, 240.0, 300.0, 20.0)))
        snap.nodes.add(SemanticNode(index = 0, id = "top-ok", role = "button", label = "확인", rect = ProbeRect(900.0, 20.0, 90.0, 36.0)))
        snap.nodes.add(SemanticNode(index = 1, id = "cancel", role = "button", label = "취소", rect = ProbeRect(480.0, 320.0, 90.0, 36.0)))
        snap.nodes.add(SemanticNode(index = 2, id = "ok", role = "button", label = "확인", rect = ProbeRect(590.0, 320.0, 90.0, 36.0)))
        assertEquals("ok", WolAutomation.findConfirmButton(snap, "PC를 켜시겠습니까")!!.id)
    }

    @Test
    fun rect_overlap_and_union() {
        val a = ProbeRect(0.0, 100.0, 10.0, 20.0)
        val b = ProbeRect(50.0, 110.0, 10.0, 20.0)
        assertEquals(0.5, ProbeRect.verticalOverlapRatio(a, b), 0.001)
        val u = ProbeRect.union(a, b)
        assertEquals(100.0, u.y, 0.0)
        assertEquals(30.0, u.h, 0.0)
        assertEquals(0.0, ProbeRect.verticalOverlapRatio(a, ProbeRect(0.0, 200.0, 5.0, 5.0)), 0.0)
    }

    // ------------------------------------------------------------------ PC 전원 배지

    private val t = OffsetDateTime.of(2026, 10, 3, 2, 31, 0, 0, ZoneOffset.ofHours(9))
    private val target = PowerTarget("myhome.iptime.org", 41000, "일반 접속 주소")

    @Test
    fun power_target_uses_the_direct_address_or_nothing() {
        val s = AppSettings(publicHost = "myhome.iptime.org", publicRdpPort = 41000)
        assertEquals(target, PowerRules.target(s))
        assertNull(PowerRules.target(s.copy(powerCheckMode = "off")))
        assertNull(PowerRules.target(AppSettings()))
        assertEquals(PcPowerState.Disabled, PowerRules.decide(null, true, true, t).state)
    }

    @Test
    fun an_answering_port_means_the_pc_is_on() {
        val s = PowerRules.decide(target, portOpen = true, routerLoggedIn = false, now = t)
        assertEquals(PcPowerState.On, s.state)
        assertEquals(t, s.checkedAt)
        assertEquals("PC 켜짐 · 02:31", s.pillText)
    }

    @Test
    fun no_answer_is_never_reported_as_powered_off() {
        val offline = PowerRules.decide(target, portOpen = false, routerLoggedIn = false, now = t)
        assertEquals(PcPowerState.NoAnswer, offline.state)
        assertEquals("PC 응답 없음", offline.pillText)
        assertFalse(offline.pillText.contains("꺼짐"))
        assertTrue(offline.detail.contains("포트가 막힘"))
        assertTrue(PowerRules.decide(target, false, routerLoggedIn = true, now = t).detail.contains("공유기는 연결됨"))
        val unknown = PowerRules.decide(target, null, true, t)
        assertEquals(PcPowerState.Unknown, unknown.state)
        assertNull(unknown.checkedAt)
    }

    @Test
    fun out_of_range_interval_is_reported() {
        assertTrue(AppSettings(routerUrl = "http://10.0.0.1:8080/", wolPcName = "PC-1", powerCheckSeconds = 1).validateRouter().isEmpty()) // 최소 1초
        for (sec in listOf(0, 5000)) {
            val s = AppSettings(routerUrl = "http://10.0.0.1:8080/", wolPcName = "PC-1", powerCheckSeconds = sec)
            assertTrue(s.validateRouter().joinToString().contains("전원 확인 주기"))
        }
    }

    @Test
    fun watcher_reports_on_and_stays_out_of_the_way_while_busy() = runBlocking {
        val s = AppSettings(publicHost = "myhome.iptime.org", publicRdpPort = 41000, powerCheckSeconds = 15)
        val port = FakePortProbe()
        var paused = true
        val seen = mutableListOf<PcPowerState>()
        val w = PowerWatcher(port, AppLog(null), this, { s }, { true }, { paused }).apply { probeTimeoutMs = 20 }
        w.changed = { seen.add(it.state) }
        w.checkSoon()
        w.tick()
        delay(100)
        assertEquals(0, port.attempts) // 작업 중에는 주기 확인을 쉰다
        w.checkNow() // 배지를 누르면 작업 중이어도 확인한다
        assertEquals(1, port.attempts)
        assertEquals(PcPowerState.On, w.status.state)
        assertTrue(seen.contains(PcPowerState.On))
        assertEquals("myhome.iptime.org:41000", port.targets[0])

        port.neverOpen = true
        paused = false
        w.checkNow()
        assertEquals(PcPowerState.NoAnswer, w.status.state)
    }

    @Test
    fun watcher_with_one_second_interval_checks_again_after_a_second() = runBlocking {
        val s = AppSettings(publicHost = "myhome.iptime.org", publicRdpPort = 41000, powerCheckSeconds = 1)
        val port = FakePortProbe()
        val w = PowerWatcher(port, AppLog(null), this, { s }, { false }, { false }).apply { probeTimeoutMs = 20 }
        w.checkNow()
        assertEquals(1, port.attempts)
        w.tick()
        delay(50)
        assertEquals(1, port.attempts) // 아직 1초가 지나지 않음
        delay(1100)
        w.tick()
        delay(100)
        assertEquals(2, port.attempts)
    }

    @Test
    fun watcher_turned_off_does_not_touch_the_network() = runBlocking {
        val port = FakePortProbe()
        val w = PowerWatcher(port, AppLog(null), this, { AppSettings(publicHost = "h.example", powerCheckMode = "off") }, { false }, { false })
        w.checkNow()
        assertEquals(PcPowerState.Disabled, w.status.state)
        assertEquals(0, port.attempts)
    }
}
