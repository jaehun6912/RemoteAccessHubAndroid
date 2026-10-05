package io.github.jaehun6912.remoteaccesshub.core

enum class RouterPageKind {
    /** 판독 불가(오류, 빈 화면). */
    Unknown,

    /** 공유기 앱 로딩 중. */
    Loading,

    /** 로그인 화면(비밀번호 입력칸). */
    Login,

    /** 로그인 직후 [관리도구] / [설정마법사] 등을 고르는 화면. */
    ModeSelect,

    /** 관리도구 본 화면(왼쪽 메뉴, 로그아웃). */
    AdminMain,

    /** WOL 목록 화면. */
    WolList,
}

/** 화면 판정에 쓰는 공유기 UI 문구. 설정에서 바꿀 수 있다. */
data class RouterUiText(
    val adminToolLabel: String = "관리도구",
    val wolMenuLabel: String = "WOL 기능",
    val wolMenuGroupLabel: String = "특수 기능",
    val wakeButtonPattern: String = "^PC\\s*켜기$",
    val emptyListText: String = "등록된 WOL PC가 없습니다",
)

/**
 * 화면 종류 판정. 2026-09-14 실제 AX2004T(15.36.6) 진단 파일의 구조를 기준으로 만들었다(Windows 버전과 같은 규칙).
 * - 로그인 화면: 비밀번호 입력칸이 있는 접근성 노드
 * - 관리 화면: 왼쪽 메뉴의 "로그아웃" 라벨
 * - WOL 화면: role=button "PC 켜기", 또는 "PC 이름 (n/500)" 머리글 + "MAC 주소"
 * - 선택 화면: 버튼 위젯(라벨 = "관리도구" + 버전 문구)
 */
object RouterPages {
    private val whitespace = Regex("\\s+")
    private val headerRegex = Regex("^PC 이름\\s*\\(\\d+/\\d+\\)$")

    fun classify(s: ProbeSnapshot, t: RouterUiText): RouterPageKind {
        if (s.error != null) return RouterPageKind.Unknown
        if (s.markers.loadingOverlay) return RouterPageKind.Loading
        if (!s.isReadable) return RouterPageKind.Unknown
        if (s.markers.passwordInput) return RouterPageKind.Login
        if (isWolList(s, t)) return RouterPageKind.WolList
        if (!s.markers.logoutLabel && findAdminToolTargets(s, t).isNotEmpty()) return RouterPageKind.ModeSelect
        if (s.markers.logoutLabel) return RouterPageKind.AdminMain
        return RouterPageKind.Unknown
    }

    fun isWolList(s: ProbeSnapshot, t: RouterUiText): Boolean {
        if (s.markers.passwordInput) return false
        val wake = safeRegex(t.wakeButtonPattern)
        if (s.nodes.any { it.isButton && it.isUsable && wake.containsMatchIn(compact(it.label)) }) return true
        if (s.paragraphs.any { !it.rect.isEmpty && wake.containsMatchIn(compact(it.text)) }) return true
        if (s.containsText(t.emptyListText)) return true
        // 실기기 머리글: "PC 이름 (1/500)" + "MAC 주소"
        val header = s.allTexts().any { headerRegex.containsMatchIn(compact(it)) }
        return header && s.containsText("MAC 주소")
    }

    data class ClickTarget(val kind: String, val nodeId: String, val text: String, val rect: ProbeRect)

    /**
     * 선택 화면의 [관리도구] 후보. 접근성 버튼(라벨이 "관리도구"로 시작)이 우선이고,
     * 없으면 정확히 "관리도구"인 장면 텍스트를 쓴다.
     */
    fun findAdminToolTargets(s: ProbeSnapshot, t: RouterUiText): List<ClickTarget> {
        val label = compact(t.adminToolLabel)
        if (label.isEmpty()) return emptyList()
        val starts = labelStarts(label)
        val buttons = s.nodes
            .filter { it.isButton && it.isUsable && starts.containsMatchIn(compact(it.label)) }
            .map { ClickTarget("semantics", it.id, compact(it.label), it.rect) }
        if (buttons.isNotEmpty()) return buttons
        return s.paragraphs
            .filter { !it.rect.isEmpty && compact(it.text) == label }
            .map { ClickTarget("paragraph", "", label, it.rect) }
    }

    /** "라벨"로 시작하고 바로 뒤가 공백이거나 끝인 라벨. */
    fun labelStarts(label: String): Regex = Regex("^" + Regex.escape(compact(label)) + "(\\s|$)")

    /** 화면에 보이는 메뉴 텍스트(정확히 일치) 후보. */
    fun findParagraphs(s: ProbeSnapshot, text: String): List<Paragraph> {
        val want = compact(text)
        return s.paragraphs.filter { !it.rect.isEmpty && compact(it.text) == want }
    }

    /**
     * 좌표 클릭 안전 검사. 클릭 지점을 포함하는 가장 작은 접근성 노드가
     * 다른 라벨(예: "로그아웃", "홈으로 이동")을 가지고 있으면 클릭하지 않는다.
     * 실기기에서 스크롤 메뉴 항목이 가려진 위치에 걸쳐 있을 때 엉뚱한 항목을 누르는 것을 막는다.
     * @return 안전하면 null, 아니면 이유
     */
    fun unsafeReason(s: ProbeSnapshot, intendedText: String, x: Double, y: Double): String? {
        if (x < 0 || y < 0) return "화면 밖 좌표"
        val want = compact(intendedText)
        val containing = s.nodes
            .filter { !it.rect.isEmpty && !it.hidden && it.rect.contains(x, y) }
            .sortedBy { it.rect.w * it.rect.h }
        if (containing.isEmpty()) return null // 접근성 트리가 없으면 장면 텍스트 검증만으로 판단
        val lbl = compact(containing[0].label)
        if (lbl.isEmpty()) return null
        if (lbl.contains(want, ignoreCase = true)) return null
        return "클릭 지점이 다른 항목('$lbl') 위에 있음"
    }

    fun isPointSafe(s: ProbeSnapshot, intendedText: String, x: Double, y: Double): Boolean = unsafeReason(s, intendedText, x, y) == null

    fun compact(s: String?): String = whitespace.replace(s ?: "", " ").trim()

    fun safeRegex(pattern: String): Regex = try {
        Regex(pattern, RegexOption.IGNORE_CASE)
    } catch (_: Exception) {
        Regex("^PC\\s*켜기$", RegexOption.IGNORE_CASE)
    }
}
