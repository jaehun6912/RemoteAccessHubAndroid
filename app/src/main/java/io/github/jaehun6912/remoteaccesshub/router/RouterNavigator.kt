package io.github.jaehun6912.remoteaccesshub.router

import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.CancelSignal
import io.github.jaehun6912.remoteaccesshub.core.Mono
import io.github.jaehun6912.remoteaccesshub.core.ProbeSnapshot
import io.github.jaehun6912.remoteaccesshub.core.RouterPageKind
import io.github.jaehun6912.remoteaccesshub.core.RouterPages

enum class NavStatus { Ok, NeedLogin, NeedUserSelect, Failed }

data class NavResult(val status: NavStatus, val message: String, val page: RouterPageKind, val method: String = "")

/** [PC 켜기] 전 WOL 목록 불러오기 확인 결과. */
enum class WolListLoad {
    /** 이미 정상 응답을 받음 */
    Loaded,

    /** [페이지 새로고침]으로 다시 불러와 정상 응답을 받음 */
    Reloaded,

    /** 확인하지 못함(새로고침 버튼 없음 등) — 그대로 진행 */
    NotConfirmed,
}

/**
 * 공유기 앱 안의 화면 이동(Windows 버전 Router/RouterNavigator.cs와 같은 순서·규칙).
 * - 로그인 직후 선택 화면에서 [관리도구] 선택
 * - 관리 화면에서 WOL 화면으로 이동: 앱 내부 경로 이동 → 메뉴 클릭(검증된 좌표) → 주소 직접 열기 순서
 * 모든 클릭은 대상 텍스트·위치를 확인한 뒤에만 수행하고, 이동 결과를 화면 판정으로 다시 확인한다.
 */
class RouterNavigator(
    private val browser: RouterDriver,
    private val log: AppLog,
    private val settings: () -> AppSettings,
) {
    var progress: ((String) -> Unit)? = null

    private fun report(m: String) {
        log.info("[화면 이동] $m")
        progress?.invoke(m)
    }

    suspend fun classify(signal: CancelSignal): Pair<RouterPageKind, ProbeSnapshot> {
        val snap = browser.probe(signal)
        return RouterPages.classify(snap, settings().uiText) to snap
    }

    /** 화면이 로딩/판독 불가 상태를 벗어날 때까지 기다린다(접근성 트리도 켠다). */
    suspend fun waitSettled(timeoutMs: Long, signal: CancelSignal): Pair<RouterPageKind, ProbeSnapshot> {
        val deadline = Mono.now() + timeoutMs
        var semanticsTried = false
        while (true) {
            signal.throwIfCancelled()
            val (kind, snap) = classify(signal)
            if (kind != RouterPageKind.Loading && kind != RouterPageKind.Unknown) return kind to snap
            if (!semanticsTried && snap.flutter && snap.semanticsCount == 0) {
                semanticsTried = true
                browser.ensureSemantics(3000, signal)
                continue
            }
            if (Mono.now() >= deadline) return kind to snap
            signal.delay(250)
        }
    }

    private suspend fun waitForKind(ok: (RouterPageKind) -> Boolean, timeoutMs: Long, signal: CancelSignal): Pair<Boolean, RouterPageKind> {
        val deadline = Mono.now() + timeoutMs
        var last: RouterPageKind
        var semanticsTried = false
        while (true) {
            signal.throwIfCancelled()
            val (kind, snap) = classify(signal)
            last = kind
            if (ok(kind)) return true to kind
            if (kind == RouterPageKind.Login) return false to kind
            if (!semanticsTried && snap.flutter && snap.semanticsCount == 0) {
                semanticsTried = true
                browser.ensureSemantics(3000, signal)
            }
            if (Mono.now() >= deadline) return false to last
            signal.delay(250)
        }
    }

    /**
     * 선택 화면이면 [관리도구]를 누른다. 이미 관리 화면이면 그대로 둔다.
     * allowAutoClick=false면 누르지 않고 NeedUserSelect를 돌려준다.
     */
    suspend fun ensureAdminTool(allowAutoClick: Boolean, signal: CancelSignal): NavResult {
        val s = settings()
        val (kind, snap) = waitSettled(15_000, signal)
        when (kind) {
            RouterPageKind.Login -> return NavResult(NavStatus.NeedLogin, "로그인 화면이 표시되어 있습니다.", kind)
            RouterPageKind.AdminMain, RouterPageKind.WolList -> return NavResult(NavStatus.Ok, "관리 화면이 이미 표시되어 있습니다.", kind)
            RouterPageKind.ModeSelect -> Unit
            else -> return NavResult(NavStatus.Failed, "공유기 화면 종류를 판단하지 못했습니다(로딩 중이거나 구조가 다름).", kind)
        }

        val label = s.adminToolLabel
        if (!allowAutoClick) return NavResult(NavStatus.NeedUserSelect, "공유기 화면에서 [$label]를 눌러 주세요.", kind)

        // 선택 화면이 막 그려진 직후에는 공유기 앱 내부 상태가 준비되지 않았을 수 있다
        // (공유기 앱의 [관리도구] 처리기는 내부 로그인 정보가 없으면 관리 화면으로 가지 않는다).
        // 잠깐 기다린 뒤 누르고, 반응이 없으면 몇 번 더 시도한다.
        signal.delay(700)
        logModeSelectStructure(snap, label)

        val rounds = 3
        var delivered = false
        val reasons = mutableListOf<String>()
        val starts = RouterPages.labelStarts(label)
        for (round in 1..rounds) {
            if (round > 1) {
                signal.delay(1500)
                browser.ensureSemantics(3000, signal)
            }

            val (kindNow, snapNow) = classify(signal)
            if (kindNow == RouterPageKind.AdminMain || kindNow == RouterPageKind.WolList) {
                return NavResult(NavStatus.Ok, "관리 화면이 표시되었습니다.", kindNow, if (round == 1) "이미 이동" else "지연 반영")
            }
            if (kindNow == RouterPageKind.Login) return NavResult(NavStatus.NeedLogin, "관리도구 선택 중 로그인 화면이 표시되었습니다.", kindNow)
            if (kindNow != RouterPageKind.ModeSelect) {
                reasons.add("${round}회차: 화면 $kindNow")
                continue
            }

            val semTargets = snapNow.nodes.filter { it.isButton && it.isUsable && starts.containsMatchIn(RouterPages.compact(it.label)) }
            val paraTargets = RouterPages.findParagraphs(snapNow, label)
            if (semTargets.size != 1 && paraTargets.size != 1) {
                val why = if (semTargets.isEmpty() && paraTargets.isEmpty()) "찾지 못함" else "접근성 ${semTargets.size}개·텍스트 ${paraTargets.size}개라 구분 불가"
                reasons.add("${round}회차: $why")
                if (round == 1 && semTargets.size > 1) {
                    return NavResult(NavStatus.NeedUserSelect, "[$label] 항목을 자동으로 누르지 못했습니다($why). 공유기 화면에서 직접 눌러 주세요.", kindNow)
                }
                continue
            }

            report(if (round == 1) "선택 화면 감지 → [$label] 선택" else "[$label] 다시 선택 ($round/${rounds}회차)")

            if (semTargets.size == 1) {
                val n = semTargets[0]
                val at = System.currentTimeMillis()
                val c = browser.clickSemanticsNode(n.id, RouterPages.compact(n.label), n.rect, signal)
                log.info("[$label] ${round}회차 접근성 클릭 결과: ${c.reason} · ${n.rect} '${RouterPages.compact(n.label)}'")
                if (c.clicked) {
                    delivered = true
                    val (ok, after) = waitAfterAdminClick(signal)
                    logAfterClick(label, "${round}회차 접근성", after, at)
                    if (ok) return NavResult(NavStatus.Ok, "[$label]를 선택해 관리 화면으로 이동했습니다.", after, "semantics")
                    if (after == RouterPageKind.Login) return NavResult(NavStatus.NeedLogin, "관리도구 선택 후 로그인 화면이 표시되었습니다.", after)
                    reasons.add("${round}회차 접근성 클릭 후 $after")
                } else {
                    reasons.add("${round}회차 접근성 안 누름: ${c.reason}")
                }
            }

            if (paraTargets.size == 1) {
                val (kindMid, snapMid) = classify(signal)
                if (kindMid == RouterPageKind.AdminMain || kindMid == RouterPageKind.WolList) {
                    return NavResult(NavStatus.Ok, "[$label]를 선택해 관리 화면으로 이동했습니다.", kindMid, "semantics")
                }
                val para = RouterPages.findParagraphs(snapMid, label)
                if (kindMid == RouterPageKind.ModeSelect && para.size == 1) {
                    val at = System.currentTimeMillis()
                    val c = browser.clickVerifiedParagraph(label, para[0].rect, signal)
                    log.info("[$label] ${round}회차 텍스트 위치 클릭 결과: ${c.reason} · ${para[0].rect}")
                    if (c.clicked) {
                        delivered = true
                        val (ok, after) = waitAfterAdminClick(signal)
                        logAfterClick(label, "${round}회차 텍스트 위치", after, at)
                        if (ok) return NavResult(NavStatus.Ok, "[$label]를 선택해 관리 화면으로 이동했습니다.", after, "paragraph")
                        if (after == RouterPageKind.Login) return NavResult(NavStatus.NeedLogin, "관리도구 선택 후 로그인 화면이 표시되었습니다.", after)
                        reasons.add("${round}회차 텍스트 클릭 후 $after")
                    } else {
                        reasons.add("${round}회차 텍스트 안 누름: ${c.reason}")
                    }
                }
            }
        }

        log.warn("[$label] 자동 선택 실패 요약: ${reasons.joinToString(" / ")}")
        val (k3, _) = classify(signal)
        if (k3 == RouterPageKind.AdminMain || k3 == RouterPageKind.WolList) return NavResult(NavStatus.Ok, "관리 화면이 표시되었습니다.", k3, "지연 반영")
        if (!delivered) {
            return NavResult(NavStatus.NeedUserSelect, "[$label]를 자동으로 누르지 못했습니다(${reasons.firstOrNull()}). 공유기 화면에서 직접 눌러 주세요.", k3)
        }
        return NavResult(NavStatus.NeedUserSelect, "[$label]를 ${rounds}번 눌렀지만 관리 화면으로 바뀌지 않았습니다. 공유기 화면에서 직접 눌러 주세요.", k3)
    }

    /** 선택 화면의 구조 요약(버튼 노드·같은 글자 텍스트)을 기록한다. 라벨과 좌표만 남기고 입력값은 없다. */
    private fun logModeSelectStructure(snap: ProbeSnapshot, label: String) {
        val buttons = snap.nodes.filter { it.isButton }.take(8).joinToString("; ") {
            "#${it.index}(부모 ${it.parent}) '${RouterPages.compact(it.label)}' ${it.rect}${if (it.disabled) " 비활성" else ""}${if (it.hidden) " 숨김" else ""}"
        }
        val paras = RouterPages.findParagraphs(snap, label).joinToString("; ") { it.rect.toString() }
        val labelled = snap.nodes.filter { RouterPages.compact(it.label).contains(label) }.take(5)
            .joinToString("; ") { "#${it.index} role=${it.role} '${RouterPages.compact(it.label)}' ${it.rect}" }
        log.info("[$label] 선택 화면 구조: 접근성 노드 ${snap.semanticsCount}개, 버튼 [$buttons]")
        log.info("[$label] 라벨 포함 노드 [$labelled], 같은 글자 텍스트 위치 [$paras]")
    }

    private suspend fun logAfterClick(label: String, which: String, after: RouterPageKind, clickAt: Long) {
        val calls = browser.network.since(clickAt).map { it.method }.filter { it.isNotEmpty() }.distinct().take(10)
        log.info("[$label] $which 클릭 후 화면: $after, 이후 공유기 API [${calls.joinToString(", ")}]")
    }

    /**
     * [관리도구]를 누른 뒤 관리 화면을 기다린다. 화면이 바뀌는 중(로딩)이면 최대 12초까지 기다리지만,
     * 4초가 지나도 선택 화면 그대로면 클릭이 반영되지 않은 것으로 보고 곧바로 돌아간다.
     */
    private suspend fun waitAfterAdminClick(signal: CancelSignal): Pair<Boolean, RouterPageKind> {
        val start = Mono.now()
        val hardDeadline = start + 12_000
        val unchangedDeadline = start + 4_000
        while (true) {
            signal.throwIfCancelled()
            val (kind, _) = classify(signal)
            if (kind == RouterPageKind.AdminMain || kind == RouterPageKind.WolList) return true to kind
            if (kind == RouterPageKind.Login) return false to kind
            val now = Mono.now()
            if (now >= hardDeadline) return false to kind
            if (kind == RouterPageKind.ModeSelect && now >= unchangedDeadline) return false to kind
            signal.delay(250)
        }
    }

    /** WOL 목록 화면으로 이동한다. 이미 WOL 화면이면 그대로 쓴다. */
    suspend fun navigateToWol(signal: CancelSignal): NavResult {
        val s = settings()
        val path = s.wolPagePath
        var lastKind = RouterPageKind.Unknown

        // 1회차: 현재 페이지에서 시도. 2회차: 주소를 직접 연(새로 불러온) 페이지에서 다시 시도.
        for (round in 0 until 2) {
            val reloaded = round == 1
            if (reloaded) {
                val url = (s.routerOrigin ?: "") + path
                report("주소 직접 열기(페이지 새로 불러오기): $path")
                browser.navigate(url, 30_000, signal)
            }

            val ensure = ensureAdminTool(s.autoSelectAdminTool, signal)
            lastKind = ensure.page
            if (ensure.status != NavStatus.Ok) return ensure
            if (ensure.page == RouterPageKind.WolList) {
                if (reloaded) return NavResult(NavStatus.Ok, "WOL 화면으로 이동했습니다(주소 직접 열기).", RouterPageKind.WolList, "주소")
                // 이미 열려 있던 WOL 화면은 목록이 오래됐을 수 있으므로 화면의 [페이지 새로고침]으로 다시 불러온다.
                val refreshed = refreshWolList(signal)
                return NavResult(
                    NavStatus.Ok,
                    if (refreshed) "열려 있던 WOL 화면의 목록을 새로 불러왔습니다." else "WOL 화면이 이미 표시되어 있습니다(새로고침 버튼 없음).",
                    RouterPageKind.WolList,
                    if (refreshed) "현재 화면+새로고침" else "현재 화면",
                )
            }

            // 앱 내부 경로 이동
            report("앱 내부 경로 이동 시도: $path")
            if (browser.pushRoute(path, signal)) {
                val (ok, kind) = waitForKind({ it == RouterPageKind.WolList }, 8_000, signal)
                lastKind = kind
                if (ok) return NavResult(NavStatus.Ok, "WOL 화면으로 이동했습니다(앱 내부 경로).", kind, if (reloaded) "주소+경로" else "경로")
                if (kind == RouterPageKind.Login) return NavResult(NavStatus.NeedLogin, "WOL 화면 대신 로그인 화면이 표시되었습니다.", kind)
            }

            // 메뉴 클릭(장면 텍스트 위치와 클릭 지점 검증)
            val menu = clickMenu(s, signal)
            if (menu.status == NavStatus.Ok) return menu.copy(method = if (reloaded) "주소+메뉴" else "메뉴")
            if (menu.status == NavStatus.NeedLogin) return menu
        }

        return NavResult(
            NavStatus.Failed,
            "WOL 화면으로 자동 이동하지 못했습니다. 공유기 화면에서 [${s.wolMenuGroupLabel} → ${s.wolMenuLabel}]을 직접 연 뒤 [현재 화면에서 PC 켜기]를 누르세요.",
            lastKind,
        )
    }

    /**
     * WOL 화면의 [페이지 새로고침] 버튼을 눌러 목록(wol/show)을 다시 불러온다.
     * 실기기 구조: 라벨 "페이지 새로고침" 노드 안에 라벨 없는 버튼 노드. 버튼 자체에 라벨이 붙은 경우도 허용.
     */
    suspend fun refreshWolList(signal: CancelSignal): Boolean {
        val label = settings().refreshLabel
        val (_, snap) = classify(signal)
        val byIndex = snap.nodes.associateBy { it.index }
        val candidates = snap.nodes.filter { n ->
            n.isButton && n.isUsable && (
                RouterPages.compact(n.label) == label ||
                    (RouterPages.compact(n.label).isEmpty() && n.parent >= 0 && byIndex[n.parent]?.let { RouterPages.compact(it.label) == label } == true)
                )
        }
        if (candidates.size != 1) {
            log.debug("[$label] 버튼 ${candidates.size}개 → 새로고침 생략")
            return false
        }
        val b = candidates[0]
        val since = System.currentTimeMillis()
        val c = browser.clickSemanticsNode(b.id, RouterPages.compact(b.label), b.rect, signal)
        if (!c.clicked) {
            log.debug("[$label] 클릭 안 함: ${c.reason}")
            return false
        }
        // 목록 요청이 "끝났다"가 아니라 "정상 응답을 받았다"를 기다린다(중간에 끊긴 요청은 성공으로 치지 않음).
        val deadline = Mono.now() + 6_000
        while (Mono.now() < deadline) {
            signal.throwIfCancelled()
            val shows = browser.network.since(since, "wol/show")
            if (shows.any(::isOkListLoad)) {
                signal.delay(150) // 목록 다시 그리기
                report("WOL 목록 새로고침")
                return true
            }
            if (shows.isNotEmpty() && shows.all { it.failed }) {
                log.info("[$label] 누른 뒤 목록 요청도 끊김: ${shows.last().errorText}")
                return false
            }
            signal.delay(150)
        }
        log.debug("새로고침 후 wol/show 정상 응답을 관찰하지 못함")
        return false
    }

    /**
     * [PC 켜기]를 누르기 전에 WOL 목록 요청(wol/show)이 정상으로 끝났는지 확인하고, 끊겼거나 확인되지 않으면
     * 화면의 [페이지 새로고침]으로 다시 불러온다(최대 2번).
     * 근거(2026-09-15 Windows 사용자 PC 기록 7회): 목록 요청이 정상(200)으로 끝난 뒤 누른 2번은 모두 wol/signal 정상,
     * 목록 요청이 중간에 끊긴(net::ERR_ABORTED) 채 누른 3번은 모두 wol/signal이 0.1초 안에 끊겼다.
     */
    suspend fun ensureWolListLoaded(since: Long, forceRefresh: Boolean, signal: CancelSignal): WolListLoad {
        // 진행 중인 목록 요청이 있으면 끝날 때까지 잠시 기다린다.
        val wait = Mono.now() + 4_000
        while (Mono.now() < wait && browser.network.since(since, "wol/show").any { !it.completed && !it.failed }) signal.delay(150)

        val last = browser.network.since(since, "wol/show").lastOrNull { it.completed || it.failed }
        if (!forceRefresh && last != null && isOkListLoad(last)) return WolListLoad.Loaded

        val why = when {
            forceRefresh -> "다시 보내기 전에 목록을 새로 불러옴"
            last == null -> "목록 요청이 보이지 않음"
            last.failed -> "목록 요청이 중간에 끊김(${last.errorText ?: "실패"})"
            else -> "목록 요청 응답 HTTP ${last.status}"
        }
        repeat(2) {
            report("WOL 목록 다시 불러오기: $why")
            if (refreshWolList(signal)) return WolListLoad.Reloaded
        }
        log.warn("WOL 목록을 정상으로 불러왔는지 확인하지 못했습니다. 그대로 진행합니다($why).")
        return WolListLoad.NotConfirmed
    }

    private suspend fun clickMenu(s: AppSettings, signal: CancelSignal): NavResult {
        for (attempt in 0 until 2) {
            val (kind, snap) = classify(signal)
            if (kind == RouterPageKind.WolList) return NavResult(NavStatus.Ok, "WOL 화면 표시됨", kind, "메뉴")
            if (kind == RouterPageKind.Login) return NavResult(NavStatus.NeedLogin, "로그인 화면이 표시되었습니다.", kind)

            val items = RouterPages.findParagraphs(snap, s.wolMenuLabel)
                .filter { RouterPages.isPointSafe(snap, s.wolMenuLabel, it.rect.centerX, it.rect.centerY) }
            if (items.isNotEmpty()) {
                // 같은 이름의 메뉴(즐겨찾기/특수 기능)는 모두 같은 화면으로 이동한다.
                val item = items[0]
                report("메뉴 [${s.wolMenuLabel}] 클릭 ${item.rect}")
                val c = browser.clickVerifiedParagraph(s.wolMenuLabel, item.rect, signal)
                if (!c.clicked) {
                    log.warn("메뉴 클릭 안 함: ${c.reason}")
                } else {
                    val (ok, k2) = waitForKind({ it == RouterPageKind.WolList }, 10_000, signal)
                    if (ok) return NavResult(NavStatus.Ok, "WOL 화면으로 이동했습니다(메뉴).", k2, "메뉴")
                    if (k2 == RouterPageKind.Login) return NavResult(NavStatus.NeedLogin, "로그인 화면이 표시되었습니다.", k2)
                }
            } else if (attempt == 0) {
                val groups = RouterPages.findParagraphs(snap, s.wolMenuGroupLabel)
                    .filter { RouterPages.isPointSafe(snap, s.wolMenuGroupLabel, it.rect.centerX, it.rect.centerY) }
                if (groups.size == 1) {
                    report("메뉴 [${s.wolMenuGroupLabel}] 펼치기")
                    browser.clickVerifiedParagraph(s.wolMenuGroupLabel, groups[0].rect, signal)
                    signal.delay(800)
                    continue
                }
                val visible = RouterPages.findParagraphs(snap, s.wolMenuLabel).size
                log.warn("메뉴 [${s.wolMenuLabel}] 안전하게 누를 위치 없음(보이는 텍스트 ${visible}개), 그룹 [${s.wolMenuGroupLabel}] ${groups.size}개")
            }
            break
        }
        return NavResult(NavStatus.Failed, "메뉴로 WOL 화면을 열지 못했습니다.", RouterPageKind.Unknown)
    }

    companion object {
        /** 목록 응답을 받았는지. 공유기 앱이 응답을 읽은 뒤 연결을 닫아 "끊김"으로 기록된 경우도 응답을 받았으면 성공으로 본다. */
        fun isOkListLoad(c: ApiCall): Boolean = (c.completed || c.failed) && (c.status ?: 0) in 200..299
    }
}
