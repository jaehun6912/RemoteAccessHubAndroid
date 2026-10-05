package io.github.jaehun6912.remoteaccesshub

import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.ConnectMode
import io.github.jaehun6912.remoteaccesshub.core.CrdBootCheck
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import io.github.jaehun6912.remoteaccesshub.core.PowerSource
import io.github.jaehun6912.remoteaccesshub.ui.SettingsDraft
import io.github.jaehun6912.remoteaccesshub.ui.SetupDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 입력 검증·설정 파일(Windows 검사 InputRulesTests·CrdSettingsTests를 옮기고 Windows 설정 가져오기를 더함). */
class SettingsTest {
    @Test
    fun host_validation() {
        for ((host, expected) in listOf(
            "myhome.iptime.org" to true, "192.168.0.10" to true, "fe80::1" to true, "host with space" to false,
            "host;calc.exe" to false, "host&&whoami" to false, "" to false, "-bad.example" to false, "a.b.c.d.example.com" to true,
            "::" to true, "1::2::3" to false,
        )) assertEquals(host, expected, InputRules.isValidHost(host))
    }

    @Test
    fun port_validation() {
        assertFalse(InputRules.isValidPort(0))
        assertTrue(InputRules.isValidPort(1))
        assertTrue(InputRules.isValidPort(3389))
        assertTrue(InputRules.isValidPort(65535))
        assertFalse(InputRules.isValidPort(65536))
    }

    @Test
    fun mac_normalization() {
        assertEquals("00:11:22:33:44:55", InputRules.normalizeMac("00:11:22:33:44:55"))
        assertEquals("00:11:22:33:44:55", InputRules.normalizeMac("00-11-22-33-44-55"))
        assertEquals("AA:BB:CC:DD:EE:FF", InputRules.normalizeMac("aabbccddeeff"))
        assertNull(InputRules.normalizeMac("00:11:22:33:44"))
        assertNull(InputRules.normalizeMac("not a mac"))
        assertNull(InputRules.normalizeMac(""))
    }

    @Test
    fun mac_masking_hides_middle() {
        assertEquals("PC 00:11:**:**:**:55 켜기", InputRules.maskMac("PC 00:11:22:33:44:55 켜기"))
    }

    @Test
    fun router_url_validation_and_origin() {
        for ((url, expected) in listOf(
            "http://192.168.0.1/" to true, "https://myhome.iptime.org:8443/" to true, "http://myhome.iptime.org:8080" to true,
            "ftp://x/" to false, "192.168.0.1" to false, "" to false, "http://bad host/" to false,
        )) assertEquals(url, expected, InputRules.tryParseRouterUrl(url))
        assertEquals("http://myhome.iptime.org:8080", InputRules.parseRouterUrl("http://MyHome.iptime.org:8080/ui/")!!.origin)
        assertEquals("http://192.168.0.1", InputRules.parseRouterUrl("http://192.168.0.1:80/")!!.origin)
        assertEquals(443, InputRules.parseRouterUrl("https://r.example/")!!.effectivePort)
    }

    @Test
    fun host_port_formats_ipv6_with_brackets() {
        assertEquals("[fe80::1]:3389", InputRules.hostPort("fe80::1", 3389))
        assertEquals("host:3390", InputRules.hostPort("host", 3390))
    }

    @Test
    fun settings_validation_is_mode_specific() {
        val crdOnly = AppSettings(publicHost = "", useCrd = true)
        assertTrue(crdOnly.validateConnect(ConnectMode.Crd).isEmpty())
        assertTrue(crdOnly.validateConnect(ConnectMode.Direct).isNotEmpty())
        val d = AppSettings(publicHost = "myhome.iptime.org", publicRdpPort = 41000)
        assertTrue(d.validateConnect(ConnectMode.Direct).isEmpty())
        assertTrue(d.validateConnect(ConnectMode.Crd).isNotEmpty()) // 크롬 원격 데스크톱이 꺼져 있음
    }

    @Test
    fun settings_roundtrip_and_no_secret_fields() {
        val f = File.createTempFile("rah-test", ".json")
        try {
            AppSettings(routerUrl = "http://10.0.0.1:8080/", wolPcName = "PC-1", wolPcMac = "00:11:22:33:44:55").save(f)
            val text = f.readText()
            assertFalse(text.contains("password", ignoreCase = true))
            assertFalse(text.contains("cookie", ignoreCase = true))
            val back = AppSettings.load(f)
            assertEquals("PC-1", back.wolPcName)
            assertEquals("http://10.0.0.1:8080", back.routerOrigin)
        } finally {
            f.delete()
        }
    }

    @Test
    fun broken_settings_file_falls_back_to_defaults() {
        val f = File.createTempFile("rah-bad", ".json")
        try {
            f.writeText("{ not json")
            val s = AppSettings.load(f)
            assertEquals("", s.routerUrl)
            assertFalse(s.setupCompleted)
        } finally {
            f.delete()
        }
    }

    @Test
    fun export_and_import_round_trip() {
        val s = AppSettings(
            routerUrl = "http://myhome.iptime.org:8080/", wolPcName = "MY-PC", wolPcMac = "02:00:AA:BB:CC:01",
            publicHost = "myhome.iptime.org", publicRdpPort = 41000, theme = "dark", setupCompleted = true, automationLayoutWidth = 1280,
        )
        val json = s.toExportJson()
        assertFalse(json.contains("password", ignoreCase = true))
        val (back, error) = AppSettings.fromExportJson(json)
        assertNull(error)
        assertEquals("MY-PC", back!!.wolPcName)
        assertEquals(41000, back.publicRdpPort)
        assertEquals("dark", back.theme)
        assertTrue(back.setupCompleted)
        assertEquals(1280, back.automationLayoutWidth)
        // Windows 버전과 같은 속성 이름(PascalCase)으로 내보낸다.
        assertTrue(json.contains("\"RouterUrl\""))
        assertTrue(json.contains("\"WolPcName\""))
    }

    @Test
    fun import_rejects_files_that_are_not_settings() {
        for (json in listOf("{\"foo\":1}", "[1,2,3]", "not json")) {
            val (s, error) = AppSettings.fromExportJson(json)
            assertNull(json, s)
            assertFalse(json, error.isNullOrEmpty())
        }
    }

    /** Windows 1.7.1이 내보낸 설정 파일 모양(VPN 항목·창 위치 포함, 대소문자 섞임 허용). */
    @Test
    fun windows_export_imports_without_vpn() {
        val windows = """
            {
              "SettingsVersion": 3,
              "SetupCompleted": true,
              "RouterUrl": "http://myhome.iptime.org:8080/",
              "WolPcName": "MY-PC",
              "WolPcMac": "02:00:AA:BB:CC:01",
              "AllowRouterCertificateError": false,
              "PublicHost": "myhome.iptime.org",
              "PublicRdpPort": 41000,
              "VpnName": "HomeVPN",
              "VpnDesktopIp": "192.168.0.10",
              "VpnRdpPort": 3389,
              "VpnWaitSeconds": 120,
              "UseCrd": true,
              "CrdHostId": "7f3a1b9c2d4e5f60",
              "CrdBootCheckMode": "vpn",
              "PowerCheckMode": "vpn",
              "PowerCheckSeconds": 60,
              "BlinkConnectWhenPcOn": true,
              "BootWaitSeconds": 180,
              "RdpFullScreen": true,
              "AutoCollapseAfterLogin": true,
              "AutoConfirmWakeDialog": true,
              "AutoSelectAdminTool": true,
              "LastConnectMode": "vpn",
              "Theme": "system",
              "ShowLog": false,
              "WindowLeft": null,
              "WindowTop": null,
              "wolPageRoute": "/ui/wol",
              "AdminToolLabel": "관리도구",
              "RefreshLabel": "페이지 새로고침",
              "WolMenuGroupLabel": "특수 기능",
              "WolMenuLabel": "WOL 기능",
              "WakeButtonPattern": "^PC\\s*켜기$",
              "ConfirmDialogPattern": "PC를 켜시겠습니까",
              "WakeProgressPattern": "PC를 켜는 중",
              "SessionProbeIntervalSeconds": 15,
            }
        """.trimIndent()
        val (s, error) = AppSettings.fromExportJson("﻿" + windows)
        assertNull(error)
        s!!
        assertEquals("http://myhome.iptime.org:8080/", s.routerUrl)
        assertEquals("MY-PC", s.wolPcName)
        assertEquals(41000, s.publicRdpPort)
        assertEquals("7f3a1b9c2d4e5f60", s.crdHostId)
        assertTrue(s.setupCompleted)
        // VPN이 없는 안드로이드에서는 VPN 값을 쓰지 않는 값으로 바꾼다.
        assertEquals(ConnectMode.Direct, s.lastMode)
        assertEquals(CrdBootCheck.None, s.crdCheck)
        assertEquals(PowerSource.Off, s.powerCheck)
        assertEquals("/ui/wol", s.wolPageRoute)
        assertEquals(1180, s.automationLayoutWidth)
        assertTrue(s.validateRouter().isEmpty())
    }

    @Test
    fun old_settings_with_valid_router_skip_first_run_setup() {
        val (configured, _) = AppSettings.fromExportJson("{\"SettingsVersion\":2,\"RouterUrl\":\"http://10.0.0.1:8080/\",\"WolPcName\":\"PC-1\"}")
        assertTrue(configured!!.setupCompleted)
        val (empty, _) = AppSettings.fromExportJson("{\"SettingsVersion\":2,\"RouterUrl\":\"\"}")
        assertFalse(empty!!.setupCompleted)
        assertFalse(AppSettings().setupCompleted)
    }

    @Test
    fun crd_host_id_accepts_id_or_pasted_session_url() {
        for ((input, expected) in listOf(
            "7f3a1b9c2d4e5f60" to "7f3a1b9c2d4e5f60",
            "  7F3A1B9C2D4E5F60  " to "7F3A1B9C2D4E5F60",
            "\"7f3a1b9c2d4e5f60\"" to "7f3a1b9c2d4e5f60",
            "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d" to "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
            "https://remotedesktop.google.com/access/session/7f3a1b9c2d4e5f60" to "7f3a1b9c2d4e5f60",
            "https://remotedesktop.google.com/access/session/7f3a1b9c2d4e5f60?hl=ko" to "7f3a1b9c2d4e5f60",
            "remotedesktop.google.com/access/session/7f3a1b9c2d4e5f60#top" to "7f3a1b9c2d4e5f60",
        )) assertEquals(input, expected, InputRules.normalizeCrdHostId(input))
    }

    @Test
    fun crd_host_id_rejects_anything_else() {
        for (input in listOf("", "   ", null, "7f3a1b9c", "7f3a1b9c2d4e5f60zz", "7f3a1b9c2d4e5f60 && calc", "--------------------",
            "javascript:alert(1)", "https://evil.example.com/access/session/x")) {
            assertNull(input, InputRules.normalizeCrdHostId(input))
        }
    }

    private fun crd(check: String) = AppSettings(useCrd = true, crdHostId = "7f3a1b9c2d4e5f60", crdBootCheckMode = check, publicHost = "myhome.iptime.org", publicRdpPort = 41000, bootWaitSeconds = 180)

    @Test
    fun crd_mode_checks_only_what_it_uses() {
        val bare = AppSettings(useCrd = true)
        assertTrue(bare.validateConnect(ConnectMode.Crd).isEmpty())
        assertTrue(bare.validateConnect(ConnectMode.Direct).isNotEmpty())
        assertTrue(crd("none").validateConnect(ConnectMode.Crd).isEmpty())
        assertTrue(crd("direct").validateConnect(ConnectMode.Crd).isEmpty())
    }

    @Test
    fun crd_mode_reports_missing_pieces() {
        assertTrue(crd("none").copy(useCrd = false).validateConnect(ConnectMode.Crd).isNotEmpty())
        assertTrue(crd("none").copy(crdHostId = "not a device id").validateConnect(ConnectMode.Crd).joinToString().contains("기기 ID"))
        assertTrue(crd("direct").copy(publicHost = "").validateConnect(ConnectMode.Crd).isNotEmpty())
        assertTrue(crd("direct").copy(bootWaitSeconds = 1).validateConnect(ConnectMode.Crd).isNotEmpty())
        // 부팅 확인을 안 하면 부팅 대기 시간은 쓰이지 않으므로 검사하지 않는다.
        assertTrue(crd("none").copy(bootWaitSeconds = 1).validateConnect(ConnectMode.Crd).isEmpty())
    }

    @Test
    fun modes_are_parsed() {
        assertEquals(CrdBootCheck.Direct, AppSettings(crdBootCheckMode = "DIRECT").crdCheck)
        assertEquals(CrdBootCheck.None, AppSettings(crdBootCheckMode = "무엇이든").crdCheck)
        assertEquals(ConnectMode.Crd, AppSettings(lastConnectMode = "CRD").lastMode)
        assertEquals(ConnectMode.Direct, AppSettings(lastConnectMode = "").lastMode)
        assertEquals(PowerSource.Direct, AppSettings(powerCheckMode = "auto").powerCheck)
        assertEquals(PowerSource.Off, AppSettings(powerCheckMode = "OFF").powerCheck)
    }

    @Test
    fun automation_layout_width_is_checked() {
        val ok = AppSettings(routerUrl = "http://10.0.0.1:8080/", wolPcName = "PC-1")
        assertTrue(ok.validateRouter().isEmpty())
        assertTrue(ok.copy(automationLayoutWidth = 0).validateRouter().isEmpty())
        assertTrue(ok.copy(automationLayoutWidth = 300).validateRouter().joinToString().contains("화면 너비"))
    }

    @Test
    fun settings_draft_reports_bad_numbers_and_keeps_valid_values() {
        val base = AppSettings(routerUrl = "http://10.0.0.1:8080/", wolPcName = "PC-1")
        val d = SettingsDraft(base)
        d.publicHost = "myhome.iptime.org"
        d.publicPort = "abc"
        val (s, errors) = d.build()
        assertNull(s)
        assertTrue(errors.joinToString().contains("일반 접속 포트"))
        d.publicPort = "41000"
        d.wolPcMac = "aabbccddeeff"
        val (s2, errors2) = d.build()
        assertTrue(errors2.joinToString(), errors2.isEmpty())
        assertEquals(41000, s2!!.publicRdpPort)
        assertEquals("AA:BB:CC:DD:EE:FF", s2.wolPcMac)
    }

    @Test
    fun setup_draft_walks_pages_and_validates_each_step() {
        val d = SetupDraft(AppSettings())
        assertNull(d.next().first) // 시작 → 공유기 주소
        d.routerUrl = "myhome"
        assertNotNull(d.next().first)
        d.routerUrl = "http://myhome.iptime.org:8080/"
        assertNull(d.next().first)
        assertNotNull(d.next().first) // PC 이름 없음
        d.pcName = "MY-PC"
        d.pcMac = "zz"
        assertNotNull(d.next().first)
        d.pcMac = ""
        assertNull(d.next().first)
        // 접속 방법 단계: 일반 접속 주소를 공유기 주소로 미리 채운다
        assertEquals("myhome.iptime.org", d.publicHost)
        d.useDirect = true
        d.publicPort = "41000"
        d.useCrd = true
        d.crdHostId = "https://remotedesktop.google.com/access/session/7f3a1b9c2d4e5f60"
        assertNull(d.next().first)
        val (error, result) = d.next()
        assertNull(error)
        result!!
        assertTrue(result.setupCompleted)
        assertEquals("MY-PC", result.wolPcName)
        assertEquals(41000, result.publicRdpPort)
        assertEquals("7f3a1b9c2d4e5f60", result.crdHostId)
        assertEquals("direct", result.lastConnectMode)
    }
}
