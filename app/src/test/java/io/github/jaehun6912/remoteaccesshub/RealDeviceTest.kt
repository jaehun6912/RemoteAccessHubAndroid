package io.github.jaehun6912.remoteaccesshub

import io.github.jaehun6912.remoteaccesshub.TestPages.FAKE_MAC
import io.github.jaehun6912.remoteaccesshub.TestPages.realConfirmDialog
import io.github.jaehun6912.remoteaccesshub.TestPages.realShell
import io.github.jaehun6912.remoteaccesshub.TestPages.realWolPage
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.ProbeRect
import io.github.jaehun6912.remoteaccesshub.core.RouterPageKind
import io.github.jaehun6912.remoteaccesshub.core.RouterPages
import io.github.jaehun6912.remoteaccesshub.core.RouterUiText
import io.github.jaehun6912.remoteaccesshub.core.SemanticNode
import io.github.jaehun6912.remoteaccesshub.core.WolMatchStatus
import io.github.jaehun6912.remoteaccesshub.core.WolMatcher
import io.github.jaehun6912.remoteaccesshub.core.WolTarget
import io.github.jaehun6912.remoteaccesshub.router.DiagnosticsExporter
import io.github.jaehun6912.remoteaccesshub.router.WolAutomation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 실제 공유기 화면 구조로 만든 검사(Windows 검사 RealDeviceTests를 옮김). */
class RealDeviceTest {
    private val wake = Regex("^PC\\s*켜기$")
    private val ui = RouterUiText()

    @Test
    fun real_wol_page_is_classified_as_wol_list() {
        assertEquals(RouterPageKind.WolList, RouterPages.classify(realWolPage("MY-PC" to FAKE_MAC), ui))
    }

    @Test
    fun real_wol_page_matches_structurally() {
        val r = WolMatcher.match(realWolPage("MY-PC" to FAKE_MAC), WolTarget("MY-PC", null), wake)
        assertTrue(r.message + " | " + r.details.joinToString(" / "), r.isFound)
        assertEquals("구조", r.target!!.strategy)
        assertEquals("wake0", r.target!!.nodeId)
        assertEquals(ProbeRect(1094.0, 115.0, 56.0, 32.0), r.target!!.rect)
    }

    @Test
    fun real_structure_with_configured_mac() {
        val snap = realWolPage("MY-PC" to FAKE_MAC)
        assertTrue(WolMatcher.match(snap, WolTarget("MY-PC", "02-00-aa-bb-cc-bf"), wake).isFound)
        assertEquals(WolMatchStatus.MacMismatch, WolMatcher.match(snap, WolTarget("MY-PC", "02:00:AA:BB:CC:00"), wake).status)
    }

    @Test
    fun real_structure_multiple_rows_picks_own_row_button() {
        val snap = realWolPage("OFFICE-PC" to "02:00:AA:BB:CC:01", "MY-PC" to FAKE_MAC, "NAS" to "02:00:AA:BB:CC:03")
        val r = WolMatcher.match(snap, WolTarget("MY-PC", null), wake)
        assertTrue(r.message, r.isFound)
        assertEquals("wake1", r.target!!.nodeId)

        val miss = WolMatcher.match(snap, WolTarget("GAME-PC", null), wake)
        assertEquals(WolMatchStatus.TargetNotFound, miss.status)
        assertTrue(miss.message.contains("OFFICE-PC"))
        assertTrue(miss.message.contains("NAS"))
    }

    @Test
    fun real_structure_duplicate_names_need_mac() {
        val snap = realWolPage("MY-PC" to "02:00:AA:BB:CC:01", "MY-PC" to FAKE_MAC)
        assertEquals(WolMatchStatus.Ambiguous, WolMatcher.match(snap, WolTarget("MY-PC", null), wake).status)
        val withMac = WolMatcher.match(snap, WolTarget("MY-PC", FAKE_MAC), wake)
        assertTrue(withMac.isFound)
        assertEquals("wake1", withMac.target!!.nodeId)
    }

    @Test
    fun geometric_fallback_on_real_layout_ignores_big_menu_container() {
        // 부모 관계를 끊어 좌표 매칭을 강제한다. "메뉴 접기"(0,0 310x720) 같은 큰 컨테이너가 모호함을 만들면 안 된다.
        val snap = realWolPage("OFFICE-PC" to "02:00:AA:BB:CC:01", "MY-PC" to FAKE_MAC)
        snap.nodes.filter { it.isButton }.forEach { it.parent = -1 }
        val r = WolMatcher.match(snap, WolTarget("MY-PC", null), wake)
        assertTrue(r.message + " | " + r.details.joinToString(" / "), r.isFound)
        assertEquals("좌표", r.target!!.strategy)
        assertEquals("wake1", r.target!!.nodeId)
    }

    @Test
    fun real_admin_page_without_wol_content_is_admin_main() {
        assertEquals(RouterPageKind.AdminMain, RouterPages.classify(realShell(false).first.snap, ui))
    }

    @Test
    fun menu_click_guard_uses_real_menu_geometry() {
        val snap = realShell(false).first.snap
        // 실제로 보이는 'WOL 기능' 텍스트 위치는 안전
        assertNull(RouterPages.unsafeReason(snap, "WOL 기능", 62.0 + 30, 383.0 + 10))
        assertNull(RouterPages.unsafeReason(snap, "특수 기능", 62.0 + 28, 257.0 + 10))
        // 스크롤로 가려져 로그아웃 줄에 걸친 위치는 거부
        assertTrue(RouterPages.unsafeReason(snap, "WOL 기능", 62.0 + 30, 680.0 + 10)!!.contains("로그아웃"))
        assertTrue(RouterPages.unsafeReason(snap, "WOL 기능", 62.0 + 30, 626.0 + 10)!!.contains("홈으로 이동"))
        // 메뉴 위쪽(메뉴 접기 영역만 포함)도 거부
        assertFalse(RouterPages.isPointSafe(snap, "WOL 기능", 30.0, 40.0))
    }

    @Test
    fun real_login_page_is_login() {
        val b = SnapBuilder()
        val root = b.node(-1, "", "", 0.0, 0.0, 1184.0, 753.0)
        val form = b.node(root, "", "", 414.0, 132.0, 355.0, 455.0)
        b.node(form, "", "AX2004T", 688.0, 148.0, 82.0, 29.0)
        b.node(form, "", "로그인 이름", 414.0, 206.0, 70.0, 20.0)
        val pw = b.node(form, "", "", 414.0, 301.0, 355.0, 38.0)
        b.snap.nodes[pw].inputType = "password"
        b.node(form, "button", "로그인", 414.0, 543.0, 355.0, 44.0)
        b.snap.markers.passwordInput = true
        b.snap.markers.loginButton = true
        assertEquals(RouterPageKind.Login, RouterPages.classify(b.snap, ui))
    }

    @Test
    fun mode_select_with_button_widgets() {
        val b = SnapBuilder()
        val root = b.node(-1, "", "", 0.0, 0.0, 1184.0, 753.0)
        b.node(root, "", "AX2004T", 688.0, 148.0, 82.0, 29.0)
        b.node(root, "button", "관리도구\n버전 15.36.6", 414.0, 250.0, 355.0, 70.0, "admin")
        b.node(root, "button", "설정마법사\n간편설정", 414.0, 340.0, 355.0, 70.0, "wizard")
        b.para("관리도구", 500.0, 264.0, 60.0, 20.0)
        b.para("버전 15.36.6", 500.0, 288.0, 90.0, 20.0)
        b.para("설정마법사", 500.0, 354.0, 75.0, 20.0)
        b.para("간편설정", 500.0, 378.0, 60.0, 20.0)
        assertEquals(RouterPageKind.ModeSelect, RouterPages.classify(b.snap, ui))
        val t = RouterPages.findAdminToolTargets(b.snap, ui)
        assertEquals(1, t.size)
        assertEquals("semantics", t[0].kind)
        assertEquals("admin", t[0].nodeId)
        assertEquals("관리도구 버전 15.36.6", t[0].text)
    }

    @Test
    fun mode_select_with_text_only() {
        val b = SnapBuilder()
        b.para("AX2004T", 688.0, 148.0, 82.0, 29.0)
        b.para("관리도구", 500.0, 264.0, 60.0, 20.0)
        b.para("버전 15.36.6", 500.0, 288.0, 90.0, 20.0)
        b.para("설정마법사", 500.0, 354.0, 75.0, 20.0)
        val t = RouterPages.findAdminToolTargets(b.snap, ui)
        assertEquals(RouterPageKind.ModeSelect, RouterPages.classify(b.snap, ui))
        assertEquals(1, t.size)
        assertEquals("paragraph", t[0].kind)
    }

    @Test
    fun admin_label_inside_other_text_is_not_mode_select() {
        val (b, _) = realShell(false)
        b.para("관리도구", 400.0, 300.0, 60.0, 20.0) // 관리 화면(로그아웃 표시) 안의 같은 글자는 선택 화면이 아님
        assertEquals(RouterPageKind.AdminMain, RouterPages.classify(b.snap, ui))
    }

    @Test
    fun settings_migrate_old_hash_route_to_real_path() {
        val f = File.createTempFile("rah-mig", ".json")
        try {
            f.writeText("{\"RouterUrl\":\"http://r.example:8080/\",\"WolPageRoute\":\"#/wol\"}")
            val s = AppSettings.load(f)
            assertEquals("/ui/wol", s.wolPageRoute)
            assertEquals("/ui/wol", s.wolPagePath)
            assertTrue(s.autoSelectAdminTool)
            assertEquals("관리도구", s.adminToolLabel)
        } finally {
            f.delete()
        }
        assertEquals("/ui/wol", AppSettings(wolPageRoute = "#/wol").wolPagePath)
        assertEquals("/ui/wol", AppSettings(wolPageRoute = "wol").wolPagePath)
        assertEquals("/x/wol", AppSettings(wolPageRoute = "/x/wol").wolPagePath)
        assertEquals("/ui/wol", AppSettings(wolPageRoute = "http://r/ui/wol").wolPagePath)
    }

    @Test
    fun confirm_on_real_dialog_picks_ok_never_cancel() {
        val t = WolAutomation.findConfirmTargets(realConfirmDialog(buttonRole = true), "PC를 켜시겠습니까")
        assertTrue(t.isNotEmpty())
        assertEquals("semantics", t[0].kind)
        assertEquals("ok", t[0].nodeId)
        assertFalse(t.any { it.text == "취소" || it.nodeId == "cancel" })
        assertTrue(t.any { it.kind == "paragraph" && it.rect.x == 652.0 })
    }

    @Test
    fun confirm_on_real_dialog_without_button_role_uses_node_then_text() {
        val snap = realConfirmDialog(buttonRole = false)
        val t = WolAutomation.findConfirmTargets(snap, "PC를 켜시겠습니까")
        assertNull(WolAutomation.findConfirmButton(snap, "PC를 켜시겠습니까"))
        assertEquals("node", t[0].kind)
        assertEquals("ok", t[0].nodeId)
        assertNull(RouterPages.unsafeReason(snap, "확인", t[0].rect.centerX, t[0].rect.centerY))
        // [취소] 위치는 안전 검사에서도 거부된다
        assertFalse(RouterPages.isPointSafe(snap, "확인", 505.0, 409.0))
    }

    @Test
    fun confirm_on_real_dialog_text_only() {
        val t = WolAutomation.findConfirmTargets(realConfirmDialog(buttonRole = true, withSemantics = false), "PC를 켜시겠습니까")
        assertEquals(1, t.size)
        assertEquals("paragraph", t[0].kind)
        assertEquals(652.0, t[0].rect.x, 0.0)
    }

    @Test
    fun confirm_ignores_ok_buttons_outside_the_dialog() {
        val snap = realConfirmDialog(buttonRole = true)
        // 창 밖(위쪽, 먼 오른쪽 아래)의 '확인'은 후보가 아니다
        snap.nodes.add(SemanticNode(index = snap.nodes.size, id = "far1", parent = 0, role = "button", label = "확인", rect = ProbeRect(1000.0, 20.0, 80.0, 36.0)))
        snap.nodes.add(SemanticNode(index = snap.nodes.size, id = "far2", parent = 0, role = "button", label = "확인", rect = ProbeRect(1100.0, 690.0, 60.0, 30.0)))
        val t = WolAutomation.findConfirmTargets(snap, "PC를 켜시겠습니까")
        assertFalse(t.any { it.nodeId == "far1" || it.nodeId == "far2" })
        assertEquals("ok", t[0].nodeId)
    }

    @Test
    fun settings_turn_on_auto_confirm_once_for_pre_1_1_1_files() {
        val f = File.createTempFile("rah-ac", ".json")
        try {
            // Windows 1.1.0 이전 파일: 버전 없음 + 옛 기본값 false
            f.writeText("{\"RouterUrl\":\"http://r.example/\",\"AutoConfirmWakeDialog\":false}")
            val s = AppSettings.load(f)
            assertTrue(s.autoConfirmWakeDialog)

            // 새 버전으로 저장된 뒤 사용자가 끈 값은 유지
            s.autoConfirmWakeDialog = false
            s.save(f)
            val again = AppSettings.load(f)
            assertFalse(again.autoConfirmWakeDialog)
            assertEquals(AppSettings.CURRENT_SETTINGS_VERSION, again.settingsVersion)
        } finally {
            f.delete()
        }
        assertTrue(AppSettings().autoConfirmWakeDialog)
    }

    @Test
    fun diagnostics_mask_hosts_in_log_lines_like_the_real_leak() {
        val s = AppSettings(routerUrl = "http://myhome.iptime.org:8080/", publicHost = "myhome.iptime.org")
        val lines = listOf(
            "14:32:10.496 [정보] 페이지 이동: http://myhome.iptime.org:8080/",
            "14:36:16.292 [정보] 원격 데스크톱 포트 응답 확인: 192.168.0.2:3390",
            "other http://another-host.example.net/x",
        )
        for (l in lines) {
            val m = DiagnosticsExporter.maskHostsInText(l, s)
            assertFalse(m, m.contains("myhome"))
            assertFalse(m, m.contains("0.2:3390"))
            assertFalse(m, m.contains("another-host"))
        }
        assertFalse(DiagnosticsExporter.maskUrl("http://myhome.iptime.org:8080/cgi/service.cgi").contains("myhome"))
        assertNotNull(DiagnosticsExporter.maskUrl("not a url"))
    }
}
