package io.github.jaehun6912.remoteaccesshub

import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.CancelSignal
import io.github.jaehun6912.remoteaccesshub.core.ConnectMode
import io.github.jaehun6912.remoteaccesshub.services.ConnectStage
import io.github.jaehun6912.remoteaccesshub.services.ConnectWorkflow
import io.github.jaehun6912.remoteaccesshub.services.CrdOpenTarget
import io.github.jaehun6912.remoteaccesshub.services.RdpLauncher
import io.github.jaehun6912.remoteaccesshub.services.RdpOpenTarget
import io.github.jaehun6912.remoteaccesshub.services.RdpClientMissingException
import io.github.jaehun6912.remoteaccesshub.services.RemoteLinks
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PC 접속] 흐름(Windows 검사 ConnectWorkflowTests·CrdConnectWorkflowTests를 옮기고 VPN 경우를 뺌). */
class ConnectWorkflowTest {
    private val hostId = "7f3a1b9c2d4e5f60"

    private class Made(val wf: ConnectWorkflow, val port: FakePortProbe, val rdp: FakeRdpLauncher, val crd: FakeCrdLauncher)

    private fun make(rdp: RdpLauncher? = null): Made {
        val port = FakePortProbe()
        val fakeRdp = FakeRdpLauncher()
        val crd = FakeCrdLauncher()
        val wf = ConnectWorkflow(port, rdp ?: fakeRdp, crd, AppLog(null)).apply {
            portRetryIntervalMs = 5
            portAttemptTimeoutMs = 5
        }
        return Made(wf, port, fakeRdp, crd)
    }

    private fun settings() = AppSettings(publicHost = "myhome.iptime.org", publicRdpPort = 41000, bootWaitSeconds = 10)
    private fun crdSettings(check: String = "none") = settings().copy(useCrd = true, crdHostId = hostId, crdBootCheckMode = check)

    @Test
    fun direct_mode_waits_port_then_launches() = runBlocking {
        val m = make()
        m.port.openAfterAttempts = 3
        val r = m.wf.run(settings(), ConnectMode.Direct, null, CancelSignal())
        assertTrue(r.message, r.success)
        assertEquals(ConnectStage.Done, r.stage)
        assertEquals(3, m.port.attempts)
        assertEquals(listOf("myhome.iptime.org" to 41000), m.rdp.launches)
        assertTrue(r.message.contains("원격 데스크톱 앱에서 입력"))
    }

    @Test
    fun boot_timeout_is_reported_without_launching() = runBlocking {
        val m = make()
        m.port.neverOpen = true
        val r = m.wf.run(settings(), ConnectMode.Direct, null, CancelSignal().withTimeout(30_000))
        assertEquals(ConnectStage.TimedOut, r.stage)
        assertFalse(r.success)
        assertTrue(m.rdp.launches.isEmpty())
    }

    @Test
    fun cancel_is_reported_as_cancelled() = runBlocking {
        val m = make()
        m.port.neverOpen = true
        val sig = CancelSignal()
        val job = async { m.wf.run(settings().copy(bootWaitSeconds = 3600), ConnectMode.Direct, null, sig) }
        delay(100)
        sig.cancel()
        val r = job.await()
        assertEquals(ConnectStage.Cancelled, r.stage)
        assertTrue(m.rdp.launches.isEmpty())
    }

    @Test
    fun invalid_settings_fail_before_any_action() = runBlocking {
        val m = make()
        val r = m.wf.run(settings().copy(publicHost = "bad host;calc"), ConnectMode.Direct, null, CancelSignal())
        assertEquals(ConnectStage.Failed, r.stage)
        assertEquals(0, m.port.attempts)
        assertTrue(m.rdp.launches.isEmpty())
    }

    @Test
    fun missing_remote_desktop_app_is_a_clear_failure() = runBlocking {
        val missing = object : RdpLauncher {
            override fun launch(host: String, port: Int): RdpOpenTarget = throw RdpClientMissingException("원격 데스크톱 앱이 없습니다. Play 스토어에서 설치하세요.")
        }
        val m = make(missing)
        val r = m.wf.run(settings(), ConnectMode.Direct, null, CancelSignal())
        assertEquals(ConnectStage.Failed, r.stage)
        assertTrue(r.pcRespondedOnPort) // 부팅은 확인됐고 앱만 없다
        assertTrue(r.message.contains("Play 스토어"))
    }

    @Test
    fun remote_links_reject_invalid_input() {
        assertEquals("rdp://full%20address=s:myhome.iptime.org:41000", RemoteLinks.rdpUri("myhome.iptime.org", 41000))
        assertEquals("rdp://full%20address=s:[fe80::1]:3389", RemoteLinks.rdpUri("fe80::1", 3389))
        assertTrue(RemoteLinks.rdpFile("myhome.iptime.org", 41000).startsWith("full address:s:myhome.iptime.org:41000\r\n"))
        for ((h, p) in listOf("host name with space" to 3389, "host" to 0, "a&b" to 3389)) {
            try {
                RemoteLinks.rdpUri(h, p)
                throw AssertionError("검증 실패: $h:$p")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // ------------------------------------------------------------------ 크롬 원격 데스크톱

    @Test
    fun crd_without_boot_check_opens_without_probing() = runBlocking {
        val m = make()
        val stages = mutableListOf<ConnectStage>()
        val r = m.wf.run(crdSettings(), ConnectMode.Crd, { stages.add(it.stage) }, CancelSignal())
        assertTrue(r.message, r.success)
        assertEquals(ConnectMode.Crd, r.mode)
        assertFalse(r.pcRespondedOnPort) // 포트를 확인하지 않았으므로 "부팅 확인됨"이라고 주장하지 않는다.
        assertEquals(0, m.port.attempts)
        assertTrue(m.rdp.launches.isEmpty())
        assertEquals(listOf("${RemoteLinks.CRD_ACCESS_URL}/session/$hostId"), m.crd.opened)
        assertTrue(stages.contains(ConnectStage.BootCheckSkipped))
    }

    @Test
    fun crd_with_direct_boot_check_waits_for_public_port() = runBlocking {
        val m = make()
        m.port.openAfterAttempts = 2
        val r = m.wf.run(crdSettings("direct"), ConnectMode.Crd, null, CancelSignal())
        assertTrue(r.message, r.success)
        assertTrue(r.pcRespondedOnPort)
        assertEquals(2, m.port.attempts)
        assertEquals("myhome.iptime.org:41000", m.port.targets[0])
        assertEquals(1, m.crd.opened.size)
    }

    @Test
    fun crd_boot_check_timeout_does_not_open() = runBlocking {
        val m = make()
        m.port.neverOpen = true
        val r = m.wf.run(crdSettings("direct"), ConnectMode.Crd, null, CancelSignal().withTimeout(30_000))
        assertEquals(ConnectStage.TimedOut, r.stage)
        assertTrue(m.crd.opened.isEmpty())
    }

    @Test
    fun crd_without_host_id_opens_device_list() = runBlocking {
        val m = make()
        val r = m.wf.run(crdSettings().copy(crdHostId = ""), ConnectMode.Crd, null, CancelSignal())
        assertTrue(r.message, r.success)
        assertEquals(RemoteLinks.CRD_ACCESS_URL, m.crd.opened[0])
        assertTrue(r.message.contains("기기 목록"))
    }

    @Test
    fun crd_turned_off_fails_before_opening() = runBlocking {
        val m = make()
        val r = m.wf.run(crdSettings().copy(useCrd = false), ConnectMode.Crd, null, CancelSignal())
        assertEquals(ConnectStage.Failed, r.stage)
        assertTrue(m.crd.opened.isEmpty())
    }

    @Test
    fun result_says_whether_the_app_or_the_browser_opened() = runBlocking {
        for ((target, expected) in listOf(
            CrdOpenTarget.App to "앱을 열었습니다",
            CrdOpenTarget.AppHome to "앱을 열었습니다(기기 목록)",
            CrdOpenTarget.Browser to "브라우저로 크롬 원격 데스크톱을 열었습니다",
        )) {
            val m = make()
            m.crd.target = target
            val r = m.wf.run(crdSettings(), ConnectMode.Crd, null, CancelSignal())
            assertTrue(r.message, r.success)
            assertTrue(r.message, r.message.contains(expected))
            assertTrue(r.message.contains("직접 하세요")) // 구글 로그인·PIN은 사용자가 직접 입력한다고 알린다.
            if (target == CrdOpenTarget.AppHome) {
                assertFalse(r.message.contains("저장된 기기")) // 앱 첫 화면만 열렸으면 "저장된 기기"라고 하지 않는다.
                assertTrue(r.message.contains("목록에서 기기를 고르세요"))
            }
        }
    }

    @Test
    fun crd_url_is_built_only_from_a_valid_host_id() {
        assertEquals("${RemoteLinks.CRD_ACCESS_URL}/session/$hostId", RemoteLinks.crdUrl(hostId))
        assertEquals("${RemoteLinks.CRD_ACCESS_URL}/session/$hostId", RemoteLinks.crdUrl("https://remotedesktop.google.com/access/session/$hostId?hl=ko"))
        assertEquals(RemoteLinks.CRD_ACCESS_URL, RemoteLinks.crdUrl(""))
        assertEquals(RemoteLinks.CRD_ACCESS_URL, RemoteLinks.crdUrl(null))
        assertEquals(RemoteLinks.CRD_ACCESS_URL, RemoteLinks.crdUrl("7f3a1b9c2d4e5f60/../evil?x=1"))
        assertEquals(RemoteLinks.CRD_ACCESS_URL, RemoteLinks.crdUrl("javascript:alert(1)"))
    }
}
