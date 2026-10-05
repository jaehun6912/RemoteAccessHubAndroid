package io.github.jaehun6912.remoteaccesshub

import io.github.jaehun6912.remoteaccesshub.core.Paragraph
import io.github.jaehun6912.remoteaccesshub.core.ProbeRect
import io.github.jaehun6912.remoteaccesshub.core.ProbeSnapshot
import io.github.jaehun6912.remoteaccesshub.core.SemanticNode
import io.github.jaehun6912.remoteaccesshub.core.WolMatchStatus
import io.github.jaehun6912.remoteaccesshub.core.WolMatcher
import io.github.jaehun6912.remoteaccesshub.core.WolTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Windows 검사 WolMatcherTests를 옮김. */
class WolMatcherTest {
    private val wake = Regex("^PC\\s*켜기$")
    private val target = "MY-PC"

    /**
     * 실제 Flutter 표에서 관찰되는 평탄한 구조를 흉내 낸 스냅샷:
     * 행 노드(이름+MAC 결합 라벨)들이 먼저 나오고 버튼 노드들은 뒤에 역순으로 온다. 장면 텍스트는 이름/MAC이 별도 단락.
     */
    private fun build(vararg rows: Pair<String, String>): ProbeSnapshot {
        val snap = ProbeSnapshot(flutter = true, semanticsCount = 1)
        var idx = 0
        val buttons = mutableListOf<SemanticNode>()
        rows.forEachIndexed { i, (name, mac) ->
            val y = 150.0 + i * 56
            snap.paragraphs.add(Paragraph(snap.paragraphs.size, name, ProbeRect(60.0, y, 300.0, 20.0)))
            snap.paragraphs.add(Paragraph(snap.paragraphs.size, mac, ProbeRect(400.0, y, 200.0, 20.0)))
            snap.paragraphs.add(Paragraph(snap.paragraphs.size, "PC 켜기", ProbeRect(712.0, y, 76.0, 20.0)))
            snap.nodes.add(SemanticNode(index = idx++, id = "row$i", label = "$name $mac", rect = ProbeRect(50.0, y - 10, 560.0, 44.0)))
            buttons.add(SemanticNode(id = "wake$i", role = "button", label = "PC 켜기", rect = ProbeRect(700.0, y - 8, 100.0, 36.0)))
            buttons.add(SemanticNode(id = "del$i", role = "button", label = "삭제", rect = ProbeRect(820.0, y - 8, 80.0, 36.0)))
        }
        buttons.reverse()
        for (b in buttons) {
            b.index = idx++
            snap.nodes.add(b)
        }
        snap.nodes.add(SemanticNode(index = idx, id = "add", role = "button", label = "WOL PC 추가", rect = ProbeRect(40.0, 60.0, 130.0, 34.0)))
        snap.paragraphs.add(Paragraph(snap.paragraphs.size, "WOL 기능", ProbeRect(40.0, 20.0, 100.0, 24.0)))
        return snap
    }

    @Test
    fun finds_button_on_target_row_only() {
        val snap = build("OTHER-1" to "00:11:22:33:44:01", target to "00:11:22:33:44:02", "OTHER-2" to "00:11:22:33:44:03")
        val r = WolMatcher.match(snap, WolTarget(target, null), wake)
        assertTrue(r.message, r.isFound)
        assertEquals("wake1", r.target!!.nodeId)
        assertEquals("semantics", r.target!!.kind)
    }

    @Test
    fun target_missing_refuses() {
        val r = WolMatcher.match(build("OTHER-1" to "00:11:22:33:44:01", "OTHER-2" to "00:11:22:33:44:03"), WolTarget(target, null), wake)
        assertEquals(WolMatchStatus.TargetNotFound, r.status)
        assertNull(r.target)
        assertTrue(r.message.contains("OTHER-1"))
    }

    @Test
    fun duplicate_names_without_mac_is_ambiguous() {
        val r = WolMatcher.match(build(target to "00:11:22:33:44:01", "X" to "00:11:22:33:44:09", target to "00:11:22:33:44:02"), WolTarget(target, null), wake)
        assertEquals(WolMatchStatus.Ambiguous, r.status)
        assertNull(r.target)
    }

    @Test
    fun duplicate_names_resolved_by_mac() {
        val r = WolMatcher.match(build(target to "00:11:22:33:44:01", "X" to "00:11:22:33:44:09", target to "00:11:22:33:44:02"), WolTarget(target, "00-11-22-33-44-02"), wake)
        assertTrue(r.message, r.isFound)
        assertEquals("wake2", r.target!!.nodeId)
    }

    @Test
    fun wrong_mac_refuses() {
        val r = WolMatcher.match(build(target to "00:11:22:33:44:02"), WolTarget(target, "AA:BB:CC:DD:EE:FF"), wake)
        assertEquals(WolMatchStatus.MacMismatch, r.status)
        assertNull(r.target)
    }

    @Test
    fun partial_name_does_not_match() {
        val r = WolMatcher.match(build("MY-PC2" to "00:11:22:33:44:01", "MYMY-PC" to "00:11:22:33:44:02"), WolTarget(target, null), wake)
        assertEquals(WolMatchStatus.TargetNotFound, r.status)
    }

    @Test
    fun name_match_is_case_insensitive_and_token_based() {
        assertTrue(WolMatcher.matchesName("my-pc\n00:11:22:33:44:55", target))
        assertTrue(WolMatcher.matchesName("MY-PC", "my-pc"))
        assertFalse(WolMatcher.matchesName("MY-PCX", target))
        assertFalse(WolMatcher.matchesName("", target))
    }

    @Test
    fun falls_back_to_paragraph_click_when_no_semantics() {
        val snap = build("OTHER-1" to "00:11:22:33:44:01", target to "00:11:22:33:44:02")
        snap.nodes.clear()
        snap.semanticsCount = 0
        val r = WolMatcher.match(snap, WolTarget(target, null), wake)
        assertTrue(r.message, r.isFound)
        assertEquals("paragraph", r.target!!.kind)
        assertTrue(r.target!!.rect.centerY in (150.0 + 56 - 5)..(150.0 + 56 + 25))
    }

    @Test
    fun two_wake_buttons_in_same_row_is_ambiguous() {
        val snap = build(target to "00:11:22:33:44:02")
        snap.nodes.add(SemanticNode(index = 99, id = "extra", role = "button", label = "PC 켜기", rect = ProbeRect(900.0, 142.0, 100.0, 36.0)))
        assertEquals(WolMatchStatus.Ambiguous, WolMatcher.match(snap, WolTarget(target, null), wake).status)
    }

    @Test
    fun empty_snapshot_is_not_readable() {
        assertEquals(WolMatchStatus.NothingReadable, WolMatcher.match(ProbeSnapshot(), WolTarget(target, null), wake).status)
    }

    @Test
    fun empty_list_message() {
        val snap = ProbeSnapshot(flutter = true)
        snap.paragraphs.add(Paragraph(0, "등록된 WOL PC가 없습니다.", ProbeRect(60.0, 160.0, 250.0, 20.0)))
        assertEquals(WolMatchStatus.ListEmpty, WolMatcher.match(snap, WolTarget(target, null), wake).status)
    }

    @Test
    fun disabled_or_hidden_buttons_are_ignored() {
        val snap = build(target to "00:11:22:33:44:02")
        snap.nodes.filter { it.id.startsWith("wake") }.forEach { it.disabled = true }
        snap.paragraphs.removeAll { it.text == "PC 켜기" }
        assertEquals(WolMatchStatus.NoWakeButton, WolMatcher.match(snap, WolTarget(target, null), wake).status)
    }

    @Test
    fun row_band_prevents_neighbor_button_selection() {
        // 대상 행의 버튼이 없고(스크롤 밖) 이웃 행의 버튼만 있는 경우: 이웃 버튼을 고르면 안 된다.
        val snap = build("OTHER-1" to "00:11:22:33:44:01", target to "00:11:22:33:44:02")
        snap.nodes.removeAll { it.id == "wake1" }
        snap.paragraphs.removeAll { it.text == "PC 켜기" && abs(it.rect.y - 206) < 1 }
        val r = WolMatcher.match(snap, WolTarget(target, null), wake)
        assertEquals(WolMatchStatus.NoWakeButton, r.status)
        assertNull(r.target)
    }
}
