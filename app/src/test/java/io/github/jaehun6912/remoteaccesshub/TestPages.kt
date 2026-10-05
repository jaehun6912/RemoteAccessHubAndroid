package io.github.jaehun6912.remoteaccesshub

import io.github.jaehun6912.remoteaccesshub.core.Paragraph
import io.github.jaehun6912.remoteaccesshub.core.ProbeRect
import io.github.jaehun6912.remoteaccesshub.core.ProbeSnapshot
import io.github.jaehun6912.remoteaccesshub.core.SemanticNode

/**
 * 2026-09-14 실제 AX2004T(15.36.6)에서 사용자가 로그인 후 WOL 화면에서 내보낸 진단 파일의 구조(Windows 검사 RealDeviceTests와 같음).
 * MAC 주소와 주소는 가짜 값이다. 좌표·부모 관계·라벨 형식은 실제 값 그대로다.
 */
class SnapBuilder {
    val snap = ProbeSnapshot(flutter = true, title = "AX2004T", readyState = "complete")

    fun node(parent: Int, role: String, label: String, x: Double, y: Double, w: Double, h: Double, id: String? = null): Int {
        val i = snap.nodes.size
        snap.nodes.add(SemanticNode(index = i, id = id ?: "flt-semantic-node-r$i", parent = parent, role = role, label = label, rect = ProbeRect(x, y, w, h)))
        return i
    }

    fun para(text: String, x: Double, y: Double, w: Double, h: Double) {
        snap.paragraphs.add(Paragraph(snap.paragraphs.size, text, ProbeRect(x, y, w, h)))
    }
}

object TestPages {
    const val FAKE_MAC = "02:00:AA:BB:CC:BF"

    /** 실제 왼쪽 메뉴(특수 기능 펼침, 스크롤된 상태). 메뉴 항목의 접근성 위치는 실기기처럼 모두 y=68. */
    fun realShell(withWolContent: Boolean): Pair<SnapBuilder, Int> {
        val b = SnapBuilder()
        val n0 = b.node(-1, "", "", 0.0, 0.0, 1180.0, 720.0)
        val n1 = b.node(n0, "", "", 0.0, 0.0, 1180.0, 720.0)
        val n2 = b.node(n1, "dialog", "", 0.0, 0.0, 1180.0, 720.0)
        val menu = b.node(n2, "", "메뉴 접기", 0.0, 0.0, 310.0, 720.0)
        b.node(menu, "button", "", 256.0, 20.0, 40.0, 40.0)
        val scroll = b.node(menu, "", "", 0.0, 68.0, 310.0, 517.0)
        val group = b.node(scroll, "group", "", 0.0, 68.0, 310.0, 517.0)
        for ((label, w, h) in listOf(Triple("기본 메뉴", 310.0, 42.0), Triple("시스템 요약 정보", 112.0, 42.0), Triple("무선랜 관리", 78.0, 42.0), Triple("NAT/라우터 관리", 114.0, 42.0), Triple("보안 기능", 63.0, 42.0))) {
            b.node(group, "", label, 0.0, 68.0, w, h)
        }
        val expert = b.node(group, "", "", 0.0, 68.0, 108.0, 42.0)
        b.node(expert, "", "특수 기능", 0.0, 68.0, 63.0, 42.0)
        b.node(expert, "", "DDNS 설정", 0.0, 68.0, 77.0, 24.0)
        b.node(expert, "", "IPTV 설정", 0.0, 68.0, 67.0, 24.0)
        val fav = b.node(expert, "group", "즐겨찾기에 추가 WOL 기능", 0.0, 68.0, 0.0, 0.0)
        b.node(fav, "button", "", 0.0, 68.0, 0.0, 0.0)
        b.node(expert, "", "호스트 검색", 0.0, 68.0, 78.0, 24.0)
        b.node(group, "", "VPN 설정", 0.0, 68.0, 65.0, 42.0)
        b.node(menu, "", "홈으로 이동", 0.0, 615.0, 310.0, 42.0)
        b.node(menu, "", "로그아웃", 0.0, 668.0, 310.0, 42.0)

        for ((t, r) in listOf(
            "무선랜 관리" to doubleArrayOf(62.0, 89.0, 70.0, 20.0), "NAT/라우터 관리" to doubleArrayOf(62.0, 173.0, 102.0, 20.0),
            "보안 기능" to doubleArrayOf(62.0, 215.0, 57.0, 20.0), "특수 기능" to doubleArrayOf(62.0, 257.0, 57.0, 20.0),
            "DDNS 설정" to doubleArrayOf(62.0, 299.0, 69.0, 20.0), "IPTV 설정" to doubleArrayOf(62.0, 341.0, 60.0, 20.0),
            "WOL 기능" to doubleArrayOf(62.0, 383.0, 61.0, 20.0), "호스트 검색" to doubleArrayOf(62.0, 425.0, 70.0, 20.0),
            "VPN 설정" to doubleArrayOf(62.0, 551.0, 58.0, 20.0), "홈으로 이동" to doubleArrayOf(64.0, 626.0, 70.0, 20.0),
            "로그아웃" to doubleArrayOf(64.0, 679.0, 52.0, 20.0), "" to doubleArrayOf(267.0, 382.0, 22.0, 22.0), "AX2004T" to doubleArrayOf(335.0, 680.0, 59.0, 20.0),
        )) b.para(t, r[0], r[1], r[2], r[3])

        val content = b.node(n2, "", "", 310.0, 0.0, 870.0, 660.0)
        val contentDialog = b.node(content, "dialog", "", 310.0, 0.0, 870.0, 660.0)
        b.node(n2, "", "라이트 모드로 전환", 421.0, 670.0, 40.0, 40.0)
        b.node(b.snap.nodes.size - 1, "button", "", 421.0, 670.0, 40.0, 40.0)
        b.node(n2, "", "AX2004T", 335.0, 680.0, 59.0, 20.0)
        b.snap.markers.logoutLabel = true

        if (!withWolContent) {
            b.node(contentDialog, "", "시스템 요약 정보", 350.0, 13.0, 760.0, 31.0)
            b.para("시스템 요약 정보", 350.0, 13.0, 160.0, 31.0)
        }
        return b to contentDialog
    }

    /** 실제 WOL 화면. 실기기 행 간격은 첫 행만 관찰(105)했으므로 이후 행은 62px 간격으로 가정. */
    fun realWolPage(vararg rows: Pair<String, String>): ProbeSnapshot {
        val (b, cd) = realShell(withWolContent = true)
        b.node(cd, "", "WOL 기능", 350.0, 13.0, 760.0, 31.0)
        val refresh = b.node(cd, "", "페이지 새로고침", 1110.0, 9.0, 40.0, 40.0)
        b.node(refresh, "button", "", 1110.0, 9.0, 40.0, 40.0, "refresh")
        val list1 = b.node(cd, "", "", 310.0, 58.0, 870.0, 602.0)
        val list2 = b.node(list1, "", "", 310.0, 58.0, 870.0, 602.0)
        b.node(list2, "", "PC 이름 (${rows.size}/500)", 340.0, 69.0, 530.0, 24.0)
        b.node(list2, "", "MAC 주소", 870.0, 69.0, 170.0, 24.0)
        b.node(list2, "", "검색", 1040.0, 66.0, 30.0, 30.0)
        b.node(list2, "", "삭제", 1080.0, 66.0, 30.0, 30.0)
        b.node(list2, "", "추가", 1120.0, 66.0, 30.0, 30.0)
        b.para("WOL 기능", 350.0, 13.0, 96.0, 31.0)
        b.para("PC 이름", 360.0, 71.0, 49.0, 20.0)
        b.para("(${rows.size}/500)", 409.0, 71.0, 52.0, 20.0)
        b.para("MAC 주소", 890.0, 71.0, 60.0, 20.0)
        rows.forEachIndexed { i, (name, mac) ->
            val y = 105.0 + i * 62
            val row = b.node(list2, "group", "$name $mac", 330.0, y, 830.0, 52.0, "row$i")
            b.node(row, "button", "PC 켜기", 1094.0, y + 10, 56.0, 32.0, "wake$i")
            b.para(name, 360.0, y + 16, 79.0, 20.0)
            b.para(mac, 890.0, y + 16, 127.0, 20.0)
            b.para("PC 켜기", 1098.0, y + 19, 48.0, 14.0)
        }
        b.snap.markers.wakeButtons = rows.size
        b.snap.semanticsCount = b.snap.nodes.size
        b.snap.paragraphCount = b.snap.paragraphs.size
        return b.snap
    }

    /** 실기기 확인창(2026-09-14 화면): 제목 "알림", 문구, 아래 한 줄에 [취소] [확인]이 반씩. */
    fun realConfirmDialog(buttonRole: Boolean, withSemantics: Boolean = true): ProbeSnapshot {
        val (b, _) = realShell(withWolContent = true)
        if (withSemantics) {
            val dlg = b.node(0, "dialog", "", 426.0, 288.0, 320.0, 143.0)
            b.node(dlg, "", "알림", 446.0, 302.0, 30.0, 20.0)
            b.node(dlg, "", "PC를 켜시겠습니까?", 480.0, 344.0, 140.0, 20.0)
            b.node(dlg, if (buttonRole) "button" else "", "취소", 426.0, 388.0, 159.0, 43.0, "cancel")
            b.node(dlg, if (buttonRole) "button" else "", "확인", 586.0, 388.0, 160.0, 43.0, "ok")
        } else {
            b.snap.nodes.clear()
        }
        b.para("알림", 446.0, 302.0, 30.0, 20.0)
        b.para("PC를 켜시겠습니까?", 480.0, 344.0, 140.0, 20.0)
        b.para("취소", 492.0, 400.0, 28.0, 20.0)
        b.para("확인", 652.0, 400.0, 28.0, 20.0)
        return b.snap
    }
}
