package io.github.jaehun6912.remoteaccesshub.router

import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.AppSettings
import io.github.jaehun6912.remoteaccesshub.core.InputRules
import io.github.jaehun6912.remoteaccesshub.core.ProbeSnapshot
import io.github.jaehun6912.remoteaccesshub.core.RouterPages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * 민감정보를 제외한 진단 파일 생성(Windows 버전 Router/DiagnosticsExporter.cs와 같은 규칙).
 * 비밀번호·쿠키·세션 토큰·원본 HTML·입력값은 포함하지 않는다.
 * 호스트/IP/MAC은 가리고, 접근성 라벨과 장면 텍스트는 길이를 제한해 담는다.
 */
object DiagnosticsExporter {
    fun defaultFileName(): String = "RemoteAccessHub-진단-${OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json"

    private val pretty = Json { prettyPrint = true }

    suspend fun build(browser: RouterBrowser?, settings: AppSettings, log: AppLog, appVersion: String, device: String): String {
        var snap: ProbeSnapshot? = null
        var session: SessionProbeDetail? = null
        if (browser != null && browser.isReady) {
            try {
                snap = browser.probe()
            } catch (_: Exception) {
            }
            try {
                session = browser.probeSession()
            } catch (_: Exception) {
            }
            try {
                browser.network.sync()
            } catch (_: Exception) {
            }
        }

        fun rect(x: Double, y: Double, w: Double, h: Double) = buildJsonObject {
            put("x", x.roundToLong()); put("y", y.roundToLong()); put("w", w.roundToLong()); put("h", h.roundToLong())
        }
        fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)

        val doc = buildJsonObject {
            put("app", "RemoteAccessHub (Android)")
            put("version", appVersion)
            put("createdAt", OffsetDateTime.now().toString())
            put("os", device)
            put("settings", buildJsonObject {
                put("routerUrl", maskUrl(settings.routerUrl))
                put("wolPcName", settings.wolPcName)
                put("wolPcMac", InputRules.maskMac(settings.wolPcMac))
                put("publicHost", maskHost(settings.publicHost))
                put("publicRdpPort", settings.publicRdpPort)
                put("bootWaitSeconds", settings.bootWaitSeconds)
                put("autoCollapseAfterLogin", settings.autoCollapseAfterLogin)
                put("autoConfirmWakeDialog", settings.autoConfirmWakeDialog)
                put("allowRouterCertificateError", settings.allowRouterCertificateError)
                put("wolPageRoute", settings.wolPageRoute)
                put("wolPagePath", settings.wolPagePath)
                put("autoSelectAdminTool", settings.autoSelectAdminTool)
                put("adminToolLabel", settings.adminToolLabel)
                put("wolMenuGroupLabel", settings.wolMenuGroupLabel)
                put("wolMenuLabel", settings.wolMenuLabel)
                put("wakeButtonPattern", settings.wakeButtonPattern)
                put("sessionProbeIntervalSeconds", settings.sessionProbeIntervalSeconds)
                put("automationLayoutWidth", settings.automationLayoutWidth)
                put("useCrd", settings.useCrd)
                put("crdBootCheckMode", settings.crdBootCheckMode)
            })
            if (browser != null) {
                put("browser", buildJsonObject {
                    put("ready", browser.isReady)
                    put("initError", str(browser.lastInitError))
                    put("url", maskUrl(browser.currentUrl))
                    put("onRouterOrigin", browser.isOnRouterOrigin)
                    put("wideLayout", browser.frame.isWide)
                    put("session", buildJsonObject {
                        put("state", browser.session.state.toString())
                        put("describe", browser.session.describe())
                        put("lastReason", browser.session.lastReason)
                        val s = session
                        put("probeNow", if (s == null) JsonNull else buildJsonObject {
                            put("result", s.result.toString()); put("reason", s.reason); put("http", s.httpStatus)
                            put("code", s.errorCode?.let { JsonPrimitive(it) } ?: JsonNull)
                        })
                    })
                })
            }
            val sn = snap
            put("probe", if (sn == null) JsonNull else buildJsonObject {
                put("url", maskUrl(sn.url))
                put("title", sn.title)
                put("readyState", sn.readyState)
                put("flutter", sn.flutter)
                put("placeholder", sn.placeholder)
                put("roots", sn.roots)
                put("semanticsCount", sn.semanticsCount)
                put("paragraphCount", sn.paragraphCount)
                put("markers", buildJsonObject {
                    put("passwordInput", sn.markers.passwordInput); put("loginButton", sn.markers.loginButton)
                    put("logoutLabel", sn.markers.logoutLabel); put("loadingOverlay", sn.markers.loadingOverlay)
                    put("wakeButtons", sn.markers.wakeButtons); put("inputs", sn.markers.inputs)
                    put("dialogTexts", JsonArray(sn.markers.dialogTexts.map { JsonPrimitive(InputRules.maskMac(it)) }))
                })
                put("error", str(sn.error))
                put("elapsedMs", sn.elapsedMs)
                put("nodes", buildJsonArray {
                    for (n in sn.nodes) add(buildJsonObject {
                        put("index", n.index); put("id", n.id); put("parent", n.parent); put("depth", n.depth); put("role", n.role)
                        put("inputType", n.inputType); put("hidden", n.hidden); put("disabled", n.disabled)
                        put("label", InputRules.maskMac(n.label)); put("rect", rect(n.rect.x, n.rect.y, n.rect.w, n.rect.h))
                    })
                })
                put("paragraphs", buildJsonArray {
                    for (p in sn.paragraphs) add(buildJsonObject {
                        put("index", p.index); put("text", InputRules.maskMac(p.text)); put("rect", rect(p.rect.x, p.rect.y, p.rect.w, p.rect.h))
                    })
                })
            })
            put("network", buildJsonArray {
                for (c in browser?.network?.snapshot(60) ?: emptyList()) add(buildJsonObject {
                    put("time", Instant.ofEpochMilli(c.startedAt).toString())
                    put("method", c.method); put("path", maskUrl(c.path))
                    put("status", c.status?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("completed", c.completed); put("failed", c.failed)
                    put("errorText", str(c.errorText)); put("errorCode", c.errorCode?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("errorMessage", str(c.errorMessage)); put("resultOk", c.resultOk?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("paramsMasked", str(c.paramsMasked))
                })
            })
            put("pageKind", if (sn == null) JsonNull else JsonPrimitive(RouterPages.classify(sn, settings.uiText).toString()))
            put("log", JsonArray(log.snapshot(300).map {
                JsonPrimitive(maskHostsInText(it.time.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + " [" + it.levelText + "] " + it.message, settings))
            }))
        }
        return pretty.encodeToString(JsonObject.serializer(), doc)
    }

    fun maskHost(host: String?): String {
        if (host.isNullOrBlank()) return ""
        val h = host.trim()
        if (InputRules.isIpv4(h)) {
            val parts = h.split('.')
            return parts[0] + "." + parts[1] + ".*.*"
        }
        if (InputRules.isIpv6(h)) return h.take(4) + "…"
        val labels = h.split('.')
        if (labels.size >= 2) {
            val first = labels[0]
            val masked = if (first.length <= 2) first.take(1) + "*" else first.take(2) + "*".repeat(minOf(6, first.length - 2))
            val rest = labels.drop(1).mapIndexed { i, l -> if (i == labels.size - 2) l else "*" }
            return masked + "." + rest.joinToString(".")
        }
        return if (h.length <= 2) "*" else h.take(2) + "***"
    }

    private val urlHostRegex = Regex("(?i)\\b(https?://)([^/\\s:?#]+)")
    private val ipv4InText = Regex("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b")

    /**
     * 로그 문장 안의 주소를 가린다: 설정에 있는 호스트(공유기·일반 접속),
     * 그리고 문장에 나타나는 모든 URL 호스트와 IPv4 주소.
     */
    fun maskHostsInText(text: String, settings: AppSettings): String {
        if (text.isEmpty()) return text
        var s = text
        val hosts = mutableListOf<String>()
        settings.routerUri?.host?.trim('[', ']')?.takeIf { it.isNotEmpty() }?.let { hosts.add(it) }
        if (settings.publicHost.isNotBlank()) hosts.add(settings.publicHost.trim())
        for (h in hosts.distinctBy { it.lowercase() }.sortedByDescending { it.length }) {
            s = Regex(Regex.escape(h), RegexOption.IGNORE_CASE).replace(s, Regex.escapeReplacement(maskHost(h)))
        }
        s = urlHostRegex.replace(s) { m -> m.groupValues[1] + maskHost(m.groupValues[2]) }
        s = ipv4InText.replace(s) { m -> "${m.groupValues[1]}.${m.groupValues[2]}.*.*" }
        return s
    }

    fun maskUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val u = try {
            URI(url.trim()).takeIf { it.isAbsolute && it.host != null }
        } catch (_: Exception) {
            null
        } ?: return "<url>"
        val port = if (u.port == -1) "" else ":" + u.port
        val fragment = u.rawFragment?.substringBefore('?')?.let { "#$it" } ?: "" // 해시 경로 뒤의 쿼리도 제거
        return "${u.scheme}://${maskHost(u.host.trim('[', ']'))}$port${u.rawPath ?: ""}$fragment"
    }
}
