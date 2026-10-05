package io.github.jaehun6912.remoteaccesshub.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min

/** 화면 좌표(CSS px). probe.js의 getBoundingClientRect 결과. */
@Serializable
data class ProbeRect(val x: Double = 0.0, val y: Double = 0.0, val w: Double = 0.0, val h: Double = 0.0) {
    val isEmpty: Boolean get() = w <= 0 || h <= 0
    val right: Double get() = x + w
    val bottom: Double get() = y + h
    val centerX: Double get() = x + w / 2
    val centerY: Double get() = y + h / 2

    fun contains(px: Double, py: Double): Boolean = px >= x && px <= right && py >= y && py <= bottom

    override fun toString(): String = "(%.0f,%.0f %.0fx%.0f)".format(x, y, w, h)

    companion object {
        val Empty = ProbeRect(0.0, 0.0, 0.0, 0.0)

        /** 세로 겹침 길이 / 둘 중 작은 높이. 0~1. */
        fun verticalOverlapRatio(a: ProbeRect, b: ProbeRect): Double {
            if (a.isEmpty || b.isEmpty) return 0.0
            val overlap = min(a.bottom, b.bottom) - max(a.y, b.y)
            if (overlap <= 0) return 0.0
            return overlap / min(a.h, b.h)
        }

        fun union(a: ProbeRect, b: ProbeRect): ProbeRect {
            if (a.isEmpty) return b
            if (b.isEmpty) return a
            val x = min(a.x, b.x)
            val y = min(a.y, b.y)
            return ProbeRect(x, y, max(a.right, b.right) - x, max(a.bottom, b.bottom) - y)
        }
    }
}

/** Flutter 접근성 노드(flt-semantics) 또는 일반 DOM의 역할 있는 요소. */
@Serializable
class SemanticNode(
    var index: Int = 0,
    var id: String = "",
    var parent: Int = -1,
    var role: String = "",
    var label: String = "",
    var inputType: String = "",
    var rect: ProbeRect = ProbeRect.Empty,
    var hidden: Boolean = false,
    var disabled: Boolean = false,
    var depth: Int = 0,
) {
    val isButton: Boolean get() = role.equals("button", ignoreCase = true)
    val isUsable: Boolean get() = !hidden && !disabled && !rect.isEmpty

    override fun toString(): String = "#$index $role '$label' $rect"
}

/** HTML 렌더러가 장면(scene)에 그린 텍스트 조각(flt-paragraph). */
@Serializable
class Paragraph(
    var index: Int = 0,
    var text: String = "",
    var rect: ProbeRect = ProbeRect.Empty,
) {
    override fun toString(): String = "¶$index '$text' $rect"
}

@Serializable
class ProbeMarkers(
    var passwordInput: Boolean = false,
    var loginButton: Boolean = false,
    var logoutLabel: Boolean = false,
    var loadingOverlay: Boolean = false,
    var wakeButtons: Int = 0,
    var inputs: Int = 0,
    var dialogTexts: List<String> = emptyList(),
)

@Serializable
class ProbeSnapshot(
    var url: String = "",
    var title: String = "",
    var readyState: String = "",
    var flutter: Boolean = false,
    var placeholder: Boolean = false,
    var semanticsCount: Int = 0,
    var paragraphCount: Int = 0,
    var roots: Int = 0,
    var nodes: MutableList<SemanticNode> = mutableListOf(),
    var paragraphs: MutableList<Paragraph> = mutableListOf(),
    var markers: ProbeMarkers = ProbeMarkers(),
    var error: String? = null,
    var elapsedMs: Long = 0,
) {
    val isReadable: Boolean get() = error == null && (nodes.isNotEmpty() || paragraphs.isNotEmpty())

    /** 화면이 실제 내용을 표시하는 상태인지. 로딩 오버레이만 있는 상태(공유기 앱 부팅 중)는 false. */
    val hasMeaningfulContent: Boolean
        get() = error == null &&
            !markers.loadingOverlay &&
            (markers.passwordInput || markers.loginButton || markers.logoutLabel || markers.wakeButtons > 0 ||
                nodes.count { it.label.isNotEmpty() } >= 3 ||
                paragraphs.count { it.text.isNotEmpty() } >= 3)

    /** 텍스트(단락+접근성 라벨)에 패턴이 포함되는지. */
    fun containsText(needle: String?): Boolean {
        if (needle.isNullOrEmpty()) return false
        return paragraphs.any { it.text.contains(needle, ignoreCase = true) } ||
            nodes.any { it.label.contains(needle, ignoreCase = true) }
    }

    fun allTexts(): Sequence<String> = sequence {
        for (p in paragraphs) yield(p.text)
        for (n in nodes) if (n.label.isNotEmpty()) yield(n.label)
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        /** evaluateJavascript 결과(JSON 문자열을 다시 JSON으로 감싼 형태 포함)를 파싱한다. */
        fun parse(scriptResult: String?): ProbeSnapshot {
            if (scriptResult.isNullOrBlank() || scriptResult == "null") return ProbeSnapshot(error = "스크립트 결과 없음")
            return try {
                var text: String = scriptResult
                // evaluateJavascript는 문자열 결과를 JSON 문자열 리터럴로 돌려준다: "\"{...}\""
                if (text.startsWith("\"")) text = json.decodeFromString<String>(text)
                json.decodeFromString<ProbeSnapshot>(text)
            } catch (e: Exception) {
                ProbeSnapshot(error = "결과 파싱 실패: " + (e.message ?: e.javaClass.simpleName).lineSequence().first())
            }
        }
    }
}
