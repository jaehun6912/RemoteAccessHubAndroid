package io.github.jaehun6912.remoteaccesshub.core

import kotlin.math.max

data class WolTarget(val pcName: String, val mac: String?)

enum class WolMatchStatus {
    Found,
    NothingReadable,
    ListEmpty,
    TargetNotFound,
    Ambiguous,
    MacMismatch,
    NoWakeButton,
}

/** 클릭할 대상. kind = "semantics"(flt-semantics 버튼 id로 click) 또는 "paragraph"(검증된 좌표 클릭). */
data class WolClickTarget(
    val kind: String,
    val nodeId: String,
    val paragraphIndex: Int,
    val label: String,
    val rect: ProbeRect,
    val rowBand: ProbeRect,
    val rowText: String,
    val strategy: String,
)

data class WolMatchResult(val status: WolMatchStatus, val message: String, val target: WolClickTarget?, val details: List<String>) {
    val isFound: Boolean get() = status == WolMatchStatus.Found && target != null
}

/**
 * WOL 목록에서 설정된 PC 이름(및 선택적 MAC)에 대응하는 [PC 켜기] 버튼을 찾는다. Windows 버전 Core/WolMatcher.cs와 같은 규칙.
 *
 * 1순위 — 구조 매칭: 실제 AX2004T(15.36.6)에서는 행이 role=group 노드이고 라벨이 "이름 MAC",
 *   [PC 켜기] 버튼이 그 행의 자식 노드다. 버튼의 가장 가까운 "행 크기" 라벨 조상으로 소속을 판단한다.
 * 2순위 — 좌표 매칭: 구조 정보가 없으면(평탄한 트리, 접근성 미사용) 세로 띠 겹침으로 판단한다.
 *
 * 대상이 없거나 구분할 수 없으면 절대 임의의 버튼을 고르지 않는다.
 */
object WolMatcher {
    private const val ROW_OVERLAP = 0.5
    private const val MAX_ROW_HEIGHT = 150.0
    private val nonNameRegex = Regex("^(PC\\s*켜기|삭제|수정|추가|검색|확인|취소|PC 이름.*|MAC 주소|WOL 기능|WOL PC 추가)$")

    fun match(snap: ProbeSnapshot, target: WolTarget, wakePattern: Regex, emptyListText: String = "등록된 WOL PC가 없습니다"): WolMatchResult {
        val details = mutableListOf<String>()
        val name = target.pcName.trim()
        val mac = InputRules.normalizeMac(target.mac)
        if (name.isEmpty()) return WolMatchResult(WolMatchStatus.TargetNotFound, "WOL 대상 PC 이름이 설정되지 않았습니다.", null, details)

        val nodes = snap.nodes
        val paras = snap.paragraphs
        if (nodes.isEmpty() && paras.isEmpty()) {
            return WolMatchResult(
                WolMatchStatus.NothingReadable,
                "화면에서 읽을 수 있는 요소가 없습니다. 접근성 트리가 비어 있거나 페이지가 아직 로딩 중입니다.",
                null,
                details,
            )
        }

        val semButtons = nodes.filter { it.isButton && it.isUsable && wakePattern.containsMatchIn(compact(it.label)) }
        details.add("접근성 [PC 켜기] 버튼 ${semButtons.size}개")

        // ---------- 1순위: 구조 매칭 ----------
        if (semButtons.isNotEmpty()) {
            val byIndex = HashMap<Int, SemanticNode>()
            for (n in nodes) byIndex.putIfAbsent(n.index, n)
            val rows = semButtons.map { it to rowAncestor(it, byIndex) }
            if (rows.all { it.second != null }) {
                details.add("구조 매칭 사용(버튼이 행 노드 안에 있음)")
                @Suppress("UNCHECKED_CAST")
                val structural = matchStructural(rows as List<Pair<SemanticNode, SemanticNode>>, name, mac, paras, details)
                if (structural != null) return structural
            } else {
                details.add("구조 정보 부족(행 노드 없는 버튼 ${rows.count { it.second == null }}개) → 좌표 매칭")
            }
        }

        // ---------- 2순위: 좌표 매칭 ----------
        return matchGeometric(nodes, paras, semButtons, name, mac, wakePattern, emptyListText, snap, details)
    }

    /** 버튼의 가장 가까운 라벨 있는 조상 중 "행 크기"이고 버튼을 세로로 포함하는 노드. */
    private fun rowAncestor(button: SemanticNode, byIndex: Map<Int, SemanticNode>): SemanticNode? {
        var cur = button
        repeat(4) {
            if (cur.parent < 0) return null
            val parent = byIndex[cur.parent] ?: return null
            if (compact(parent.label).isNotEmpty()) {
                val rowSized = !parent.rect.isEmpty &&
                    parent.rect.h <= max(MAX_ROW_HEIGHT, button.rect.h * 3) &&
                    ProbeRect.verticalOverlapRatio(parent.rect, button.rect) >= ROW_OVERLAP
                return if (rowSized) parent else null
            }
            cur = parent
        }
        return null
    }

    private fun matchStructural(
        rows: List<Pair<SemanticNode, SemanticNode>>,
        name: String,
        mac: String?,
        paras: List<Paragraph>,
        details: MutableList<String>,
    ): WolMatchResult? {
        val nameHits = rows.filter { matchesName(it.second.label, name) }
        details.add("이름 '$name' 일치 행 ${nameHits.size}개 / 전체 행 ${rows.size}개")

        // 같은 행 노드에 [PC 켜기]가 둘 이상이면 구분 불가
        for ((_, g) in nameHits.groupBy { it.second.index }) {
            if (g.size > 1) {
                return WolMatchResult(WolMatchStatus.Ambiguous, "'$name' 행 안에 [PC 켜기] 버튼이 ${g.size}개 있어 구분할 수 없습니다.", null, details)
            }
        }

        if (nameHits.isEmpty()) {
            val visible = rows.map { firstToken(it.second.label) }.filter { it.isNotEmpty() }.distinct().take(10)
            val hint = if (visible.isNotEmpty()) " 화면에 보이는 이름: ${visible.joinToString(", ")}" else ""
            return WolMatchResult(WolMatchStatus.TargetNotFound, "WOL 목록에서 '$name' 이름의 PC를 찾지 못했습니다.$hint", null, details)
        }

        var kept = nameHits
        if (mac != null) {
            val k = mutableListOf<Pair<SemanticNode, SemanticNode>>()
            for (r in nameHits) {
                val macs = sortedSetOf(String.CASE_INSENSITIVE_ORDER).apply { addAll(InputRules.extractMacs(r.second.label)) }
                for (p in paras.filter { ProbeRect.verticalOverlapRatio(it.rect, r.second.rect) >= ROW_OVERLAP }) {
                    macs.addAll(InputRules.extractMacs(p.text))
                }
                if (macs.isEmpty() || macs.contains(mac)) k.add(r)
                else details.add("행 ${r.second.rect}: MAC 불일치 (${macs.joinToString(",") { InputRules.maskMac(it) }})")
            }
            if (k.isEmpty()) {
                return WolMatchResult(WolMatchStatus.MacMismatch, "'$name' 행의 MAC 주소가 설정값과 다릅니다. 설정의 MAC 주소를 확인하세요.", null, details)
            }
            kept = k
        }

        if (kept.size > 1) {
            val msg = if (mac == null) {
                "같은 이름 '$name'의 PC가 ${kept.size}개 있어 구분할 수 없습니다. 설정에 MAC 주소를 입력하세요."
            } else {
                "'$name' 이름과 MAC이 모두 일치하는 행이 ${kept.size}개라 구분할 수 없습니다."
            }
            return WolMatchResult(WolMatchStatus.Ambiguous, msg, null, details)
        }

        val (button, row) = kept[0]
        val t = WolClickTarget("semantics", button.id, -1, button.label, button.rect, row.rect, row.label, "구조")
        return WolMatchResult(WolMatchStatus.Found, "'$name' 행의 [PC 켜기] 버튼을 찾았습니다 ${button.rect} (구조 매칭).", t, details)
    }

    private data class ButtonCandidate(val kind: String, val nodeId: String, val paragraphIndex: Int, val label: String, val rect: ProbeRect)

    private class Row(var band: ProbeRect, var text: String)

    private fun matchGeometric(
        nodes: List<SemanticNode>,
        paras: List<Paragraph>,
        semButtons: List<SemanticNode>,
        name: String,
        mac: String?,
        wakePattern: Regex,
        emptyListText: String,
        snap: ProbeSnapshot,
        details: MutableList<String>,
    ): WolMatchResult {
        val buttons = semButtons.map { ButtonCandidate("semantics", it.id, -1, it.label, it.rect) }.toMutableList()
        if (buttons.isEmpty()) {
            for (p in paras) {
                if (p.rect.isEmpty) continue
                if (wakePattern.containsMatchIn(compact(p.text))) buttons.add(ButtonCandidate("paragraph", "", p.index, p.text, p.rect))
            }
        }
        details.add("PC 켜기 버튼 후보 ${buttons.size}개 (접근성 ${buttons.count { it.kind == "semantics" }}개)")

        val hits = mutableListOf<Pair<ProbeRect, String>>()
        for (p in paras) {
            if (p.rect.isEmpty || p.rect.h > MAX_ROW_HEIGHT) continue
            if (matchesName(p.text, name)) hits.add(p.rect to p.text)
        }
        for (n in nodes) {
            if (n.rect.isEmpty || n.isButton || n.hidden || n.rect.h > MAX_ROW_HEIGHT) continue
            if (matchesName(n.label, name)) hits.add(n.rect to n.label)
        }
        details.add("이름 '$name' 일치 요소 ${hits.size}개")

        var rows = clusterRows(hits)
        details.add("이름 일치 행 ${rows.size}개")
        if (rows.isEmpty()) {
            if (snap.containsText(emptyListText)) {
                return WolMatchResult(WolMatchStatus.ListEmpty, "공유기에 등록된 WOL PC가 없습니다. 공유기 WOL 화면에서 PC를 먼저 등록하세요.", null, details)
            }
            val visible = visibleNames(paras, buttons)
            val hint = if (visible.isNotEmpty()) " 화면에 보이는 이름: ${visible.joinToString(", ")}" else ""
            return WolMatchResult(WolMatchStatus.TargetNotFound, "WOL 목록에서 '$name' 이름의 PC를 찾지 못했습니다.$hint", null, details)
        }

        if (mac != null) {
            val kept = mutableListOf<Row>()
            for (row in rows) {
                val macs = macsInBand(row.band, nodes, paras)
                if (macs.isEmpty()) {
                    kept.add(row)
                    continue
                }
                if (macs.contains(mac)) kept.add(row)
                else details.add("행 ${row.band}: MAC 불일치 (${macs.joinToString(",") { InputRules.maskMac(it) }})")
            }
            if (kept.isEmpty()) {
                return WolMatchResult(WolMatchStatus.MacMismatch, "'$name' 행의 MAC 주소가 설정값과 다릅니다. 설정의 MAC 주소를 확인하세요.", null, details)
            }
            rows = kept
        }

        if (rows.size > 1) {
            val msg = if (mac == null) {
                "같은 이름 '$name'의 PC가 ${rows.size}개 있어 구분할 수 없습니다. 설정에 MAC 주소를 입력하세요."
            } else {
                "'$name' 이름과 MAC이 모두 일치하는 행이 ${rows.size}개라 구분할 수 없습니다."
            }
            return WolMatchResult(WolMatchStatus.Ambiguous, msg, null, details)
        }

        val targetRow = rows[0]
        val inBand = buttons.filter { ProbeRect.verticalOverlapRatio(it.rect, targetRow.band) >= ROW_OVERLAP }
        details.add("행 ${targetRow.band} 안 버튼 ${inBand.size}개")
        if (inBand.isEmpty()) {
            val msg = if (buttons.isEmpty()) {
                "화면에서 [PC 켜기] 버튼을 찾지 못했습니다. 접근성 트리가 활성화되지 않았거나 화면 구성이 다릅니다."
            } else {
                "'$name' 행에 대응하는 [PC 켜기] 버튼이 없습니다. 목록을 스크롤해야 하거나 화면 구성이 다를 수 있습니다."
            }
            return WolMatchResult(WolMatchStatus.NoWakeButton, msg, null, details)
        }
        if (inBand.size > 1) {
            return WolMatchResult(WolMatchStatus.Ambiguous, "'$name' 행 안에 [PC 켜기] 버튼이 ${inBand.size}개 있어 구분할 수 없습니다.", null, details)
        }

        val chosen = inBand[0]
        for (other in otherNameRows(nodes, paras, name, chosen)) {
            if (ProbeRect.verticalOverlapRatio(chosen.rect, other) > ProbeRect.verticalOverlapRatio(chosen.rect, targetRow.band)) {
                return WolMatchResult(WolMatchStatus.Ambiguous, "선택된 버튼이 다른 PC 행과 더 가깝게 겹칩니다. 안전을 위해 클릭하지 않습니다.", null, details)
            }
        }

        val t = WolClickTarget(chosen.kind, chosen.nodeId, chosen.paragraphIndex, chosen.label, chosen.rect, targetRow.band, targetRow.text, "좌표")
        return WolMatchResult(WolMatchStatus.Found, "'$name' 행의 [PC 켜기] 버튼을 찾았습니다 ${chosen.rect} (좌표 매칭).", t, details)
    }

    private fun clusterRows(hits: List<Pair<ProbeRect, String>>): List<Row> {
        val rows = mutableListOf<Row>()
        for ((rect, text) in hits.sortedBy { it.first.y }) {
            val row = rows.firstOrNull { ProbeRect.verticalOverlapRatio(it.band, rect) >= ROW_OVERLAP }
            if (row == null) {
                rows.add(Row(rect, text))
            } else {
                row.band = ProbeRect.union(row.band, rect)
                if (!row.text.contains(text)) row.text += " | $text"
            }
        }
        return rows
    }

    private fun macsInBand(band: ProbeRect, nodes: List<SemanticNode>, paras: List<Paragraph>): Set<String> {
        val set = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        for (p in paras) {
            if (ProbeRect.verticalOverlapRatio(p.rect, band) >= ROW_OVERLAP) set.addAll(InputRules.extractMacs(p.text))
        }
        for (n in nodes) {
            if (!n.isButton && n.rect.h <= MAX_ROW_HEIGHT && ProbeRect.verticalOverlapRatio(n.rect, band) >= ROW_OVERLAP) {
                set.addAll(InputRules.extractMacs(n.label))
            }
        }
        return set
    }

    /** 선택한 버튼과 같은 세로 띠에 있는, 대상이 아닌 이름 후보(행 크기 요소만). */
    private fun otherNameRows(nodes: List<SemanticNode>, paras: List<Paragraph>, name: String, b: ButtonCandidate): List<ProbeRect> {
        val maxH = max(60.0, b.rect.h * 3)
        val texts = paras.filter { it.rect.h <= maxH && ProbeRect.verticalOverlapRatio(it.rect, b.rect) >= ROW_OVERLAP }.map { it.rect to it.text } +
            nodes.filter { !it.isButton && !it.rect.isEmpty && it.rect.h <= maxH && ProbeRect.verticalOverlapRatio(it.rect, b.rect) >= ROW_OVERLAP }
                .map { it.rect to it.label }
        return texts.filter { (_, text) -> !matchesName(text, name) && looksLikeName(text) }.map { it.first }
    }

    private fun visibleNames(paras: List<Paragraph>, buttons: List<ButtonCandidate>): List<String> {
        val names = mutableListOf<String>()
        for (b in buttons) {
            for (p in paras) {
                if (ProbeRect.verticalOverlapRatio(p.rect, b.rect) < ROW_OVERLAP) continue
                if (!looksLikeName(p.text)) continue
                val first = firstToken(p.text)
                if (first.isNotEmpty() && first !in names) names.add(first)
            }
        }
        return names.take(10)
    }

    private fun firstToken(text: String): String = compact(text).split(' ')[0]

    private fun looksLikeName(text: String): Boolean {
        val t = compact(text)
        if (t.isEmpty() || t.length > 64) return false
        if (InputRules.macRegex.containsMatchIn(t) && InputRules.macRegex.replace(t, "").trim().isEmpty()) return false
        if (nonNameRegex.containsMatchIn(t)) return false
        if (t.length == 1 && Character.getType(t[0]) == Character.PRIVATE_USE.toInt()) return false // 아이콘 글꼴
        return true
    }

    fun matchesName(text: String?, name: String): Boolean {
        if (text.isNullOrEmpty()) return false
        val t = compact(text)
        val n = compact(name)
        if (n.isEmpty()) return false
        if (t.equals(n, ignoreCase = true)) return true
        var idx = 0
        while (true) {
            idx = t.indexOf(n, idx, ignoreCase = true)
            if (idx < 0) break
            val before = if (idx == 0) ' ' else t[idx - 1]
            val afterIdx = idx + n.length
            val after = if (afterIdx >= t.length) ' ' else t[afterIdx]
            if (!isNameChar(before) && !isNameChar(after)) return true
            idx += n.length
        }
        return false
    }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

    fun compact(s: String?): String = RouterPages.compact(s)
}
