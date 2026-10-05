package io.github.jaehun6912.remoteaccesshub.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.Locale

enum class ConnectMode {
    /** 일반 접속: 공유기 포트포워딩으로 원격 데스크톱 앱이 바로 연결. */
    Direct,

    /** 크롬 원격 데스크톱(구글 중계). 포트포워딩 없이 앱이나 브라우저로 연결한다. */
    Crd,
}

/** 윗줄 배지에 PC 전원 상태를 보여 줄 때 어디로 확인할지. 안드로이드 버전은 VPN이 없어 일반 접속 주소만 쓴다. */
enum class PowerSource {
    /** 일반 접속 주소·포트로 확인. */
    Direct,

    /** 확인하지 않음. */
    Off,
}

/** 크롬 원격 데스크톱으로 접속할 때 PC가 켜졌는지 확인하는 방법. */
enum class CrdBootCheck {
    /** 확인하지 않고 바로 연다(크롬 원격 데스크톱은 열어 둘 포트가 없다). */
    None,

    /** 일반 접속 주소·포트가 응답하는지로 확인. */
    Direct,
}

/**
 * 사용자 설정. 비밀번호·캡차·쿠키·세션 토큰은 어떤 필드에도 저장하지 않는다.
 *
 * JSON 이름은 Windows 버전(AppSettings.cs)과 같다. 그래서 Windows에서 내보낸 설정 파일을 그대로 가져올 수 있고,
 * 여기서 내보낸 파일도 Windows에서 가져올 수 있다. VPN 항목은 안드로이드 버전에 없으므로 가져올 때 무시한다.
 */
@Serializable
data class AppSettings(
    /** 설정 파일 형식 버전. 저장할 때 현재 버전으로 기록한다. */
    @SerialName("SettingsVersion") var settingsVersion: Int = 0,
    /** 첫 실행 시작 설정을 마쳤는지. 마치기 전에는 실행할 때마다 시작 설정을 연다. */
    @SerialName("SetupCompleted") var setupCompleted: Boolean = false,

    // --- 공유기 ---
    @SerialName("RouterUrl") var routerUrl: String = "",
    @SerialName("WolPcName") var wolPcName: String = "",
    @SerialName("WolPcMac") var wolPcMac: String = "",
    @SerialName("AllowRouterCertificateError") var allowRouterCertificateError: Boolean = false,

    // --- 일반(직접) 원격 데스크톱 접속 ---
    @SerialName("PublicHost") var publicHost: String = "",
    @SerialName("PublicRdpPort") var publicRdpPort: Int = 3389,

    // --- 크롬 원격 데스크톱 ---
    /** 접속 방식에 "크롬 원격 데스크톱"을 넣을지. */
    @SerialName("UseCrd") var useCrd: Boolean = false,
    /** 크롬 원격 데스크톱 기기 ID. 비어 있으면 기기 목록 화면을 연다. */
    @SerialName("CrdHostId") var crdHostId: String = "",
    /** "none" | "direct". 부팅 확인 방법. */
    @SerialName("CrdBootCheckMode") var crdBootCheckMode: String = "none",

    // --- PC 전원 상태 배지 ---
    /** "auto" | "direct" | "off". 안드로이드 버전에서 "auto"는 "direct"와 같다. */
    @SerialName("PowerCheckMode") var powerCheckMode: String = "auto",
    /** 전원 상태를 다시 확인하는 주기(초). */
    @SerialName("PowerCheckSeconds") var powerCheckSeconds: Int = 60,
    /** PC가 켜진 것이 확인되면 [PC 접속] 버튼을 천천히 깜빡인다. */
    @SerialName("BlinkConnectWhenPcOn") var blinkConnectWhenPcOn: Boolean = true,

    // --- 공통 ---
    @SerialName("BootWaitSeconds") var bootWaitSeconds: Int = 180,
    @SerialName("AutoCollapseAfterLogin") var autoCollapseAfterLogin: Boolean = true,
    /** WOL 확인창(PC를 켜시겠습니까?)의 [확인]을 자동으로 누른다. */
    @SerialName("AutoConfirmWakeDialog") var autoConfirmWakeDialog: Boolean = true,
    /** 로그인 직후 [관리도구]/[설정마법사] 선택 화면에서 [관리도구]를 자동으로 누른다. */
    @SerialName("AutoSelectAdminTool") var autoSelectAdminTool: Boolean = true,
    @SerialName("LastConnectMode") var lastConnectMode: String = "direct",

    // --- 화면 ---
    /** "system"(휴대폰 설정을 따름) | "dark" | "light" */
    @SerialName("Theme") var theme: String = "system",
    @SerialName("ShowLog") var showLog: Boolean = false,

    // --- 고급(공유기 화면 구조가 바뀌면 조정) ---
    /** WOL 화면 경로. 실제 AX2004T(15.36.6)는 경로 방식 주소(/ui/wol)를 쓴다. */
    @SerialName("WolPageRoute") var wolPageRoute: String = "/ui/wol",
    @SerialName("AdminToolLabel") var adminToolLabel: String = "관리도구",
    @SerialName("RefreshLabel") var refreshLabel: String = "페이지 새로고침",
    @SerialName("WolMenuGroupLabel") var wolMenuGroupLabel: String = "특수 기능",
    @SerialName("WolMenuLabel") var wolMenuLabel: String = "WOL 기능",
    @SerialName("WakeButtonPattern") var wakeButtonPattern: String = "^PC\\s*켜기$",
    @SerialName("ConfirmDialogPattern") var confirmDialogPattern: String = "PC를 켜시겠습니까",
    @SerialName("WakeProgressPattern") var wakeProgressPattern: String = "PC를 켜는 중",
    @SerialName("SessionProbeIntervalSeconds") var sessionProbeIntervalSeconds: Int = 15,

    // --- 안드로이드 전용 ---
    /**
     * 공유기 화면을 숨긴 채 자동으로 조작할 때 공유기 화면을 그릴 너비(CSS px).
     * 공유기 앱이 휴대폰 너비에서 다른 배치(메뉴 숨김 등)로 바뀌지 않도록, 화면 판정을 맞춘 PC 화면 너비로 그린다.
     * 0이면 휴대폰 화면 너비 그대로 그린다.
     */
    @SerialName("AutomationLayoutWidth") var automationLayoutWidth: Int = 1180,
) {
    val powerCheck: PowerSource
        get() = if (powerCheckMode.trim().lowercase(Locale.ROOT) == "off") PowerSource.Off else PowerSource.Direct

    val crdCheck: CrdBootCheck
        get() = if (crdBootCheckMode.trim().lowercase(Locale.ROOT) == "direct") CrdBootCheck.Direct else CrdBootCheck.None

    val lastMode: ConnectMode
        get() = if (lastConnectMode.trim().lowercase(Locale.ROOT) == "crd") ConnectMode.Crd else ConnectMode.Direct

    val routerUri: RouterUri? get() = InputRules.parseRouterUrl(routerUrl)

    /** API 호출 origin (scheme://host:port). 공유기 URL이 잘못되면 null. */
    val routerOrigin: String? get() = routerUri?.origin

    val uiText: RouterUiText get() = RouterUiText(adminToolLabel, wolMenuLabel, wolMenuGroupLabel, wakeButtonPattern)

    /** WOL 화면의 origin 기준 경로(항상 "/"로 시작). 예: "/ui/wol". */
    val wolPagePath: String
        get() {
            val r = wolPageRoute.trim()
            if (r.isEmpty()) return "/ui/wol"
            if (r.startsWith("#/")) return "/ui/" + r.substring(2)
            if (r.startsWith("/")) return r
            val abs = try {
                java.net.URI(r).takeIf { it.isAbsolute }
            } catch (_: Exception) {
                null
            }
            if (abs != null) return abs.rawPath.ifEmpty { "/" }
            return "/ui/" + r.trimStart('#')
        }

    /** 불러온 설정의 알려진 옛 기본값을 현재 기본값으로 바꾼다(Windows 버전 규칙 + VPN 값 정리). */
    fun migrate() {
        if (settingsVersion < 2) {
            // 1.1.0 이전 Windows 파일의 false는 사용자가 고른 값이 아니라 옛 기본값이므로 한 번만 켠다.
            autoConfirmWakeDialog = true
        }
        if (settingsVersion < 3 && validateRouter().isEmpty()) {
            // 시작 설정이 생기기 전부터 쓰던 Windows 설정을 가져오면 시작 설정을 다시 띄우지 않는다.
            setupCompleted = true
        }
        if (settingsVersion < CURRENT_SETTINGS_VERSION) settingsVersion = CURRENT_SETTINGS_VERSION
        if (wolPageRoute.trim() == "#/wol") wolPageRoute = "/ui/wol"
        if (adminToolLabel.isBlank()) adminToolLabel = "관리도구"
        if (wolMenuGroupLabel.isBlank()) wolMenuGroupLabel = "특수 기능"
        if (refreshLabel.isBlank()) refreshLabel = "페이지 새로고침"
        // 안드로이드 버전에는 VPN 접속이 없다. Windows에서 가져온 VPN 값은 쓰지 않는 값으로 바꾼다.
        when (lastConnectMode.trim().lowercase(Locale.ROOT)) {
            "crd" -> lastConnectMode = "crd"
            else -> lastConnectMode = "direct"
        }
        if (crdBootCheckMode.trim().lowercase(Locale.ROOT) !in setOf("none", "direct")) crdBootCheckMode = "none"
        if (powerCheckMode.trim().lowercase(Locale.ROOT) !in setOf("auto", "direct", "off")) powerCheckMode = "off"
        if (automationLayoutWidth != 0) automationLayoutWidth = automationLayoutWidth.coerceIn(MIN_LAYOUT_WIDTH, MAX_LAYOUT_WIDTH)
    }

    fun save(file: File) {
        settingsVersion = CURRENT_SETTINGS_VERSION
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer(), this), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IllegalStateException("설정 파일을 저장하지 못했습니다.")
        }
    }

    fun clone(): AppSettings = copy()

    /** 공유기 로그인/WOL에 필요한 설정 검증. */
    fun validateRouter(): List<String> {
        val errors = mutableListOf<String>()
        if (!InputRules.tryParseRouterUrl(routerUrl)) {
            errors.add("공유기 관리자 페이지 URL이 올바르지 않습니다. 예: http://ddns.example.com:8080/ 또는 https://192.168.0.1/")
        }
        if (wolPcName.isBlank()) errors.add("WOL 대상 PC 이름을 입력하세요.")
        if (wolPcMac.isNotBlank() && InputRules.normalizeMac(wolPcMac) == null) {
            errors.add("WOL 대상 MAC 주소 형식이 올바르지 않습니다. 예: 00:11:22:33:44:55")
        }
        try {
            Regex(wakeButtonPattern)
        } catch (_: Exception) {
            errors.add("PC 켜기 버튼 패턴(정규식)이 올바르지 않습니다.")
        }
        if (powerCheckSeconds !in 15..3600) errors.add("PC 전원 확인 주기는 15~3600초 사이여야 합니다.")
        if (sessionProbeIntervalSeconds !in 5..600) errors.add("세션 확인 주기는 5~600초 사이여야 합니다.")
        if (automationLayoutWidth != 0 && automationLayoutWidth !in MIN_LAYOUT_WIDTH..MAX_LAYOUT_WIDTH) {
            errors.add("자동 조작 화면 너비는 0(휴대폰 너비) 또는 $MIN_LAYOUT_WIDTH~${MAX_LAYOUT_WIDTH} 사이여야 합니다.")
        }
        return errors
    }

    /** 선택한 접속 모드에 필요한 설정만 검증한다. 다른 모드의 설정이 비어 있어도 막지 않는다. */
    fun validateConnect(mode: ConnectMode): List<String> {
        val errors = mutableListOf<String>()
        if (mode == ConnectMode.Crd) {
            if (!useCrd) errors.add("크롬 원격 데스크톱 접속이 꺼져 있습니다.")
            if (crdHostId.isNotEmpty() && InputRules.normalizeCrdHostId(crdHostId) == null) {
                errors.add("크롬 원격 데스크톱 기기 ID 형식이 올바르지 않습니다.")
            }
            if (crdCheck == CrdBootCheck.Direct) {
                if (!InputRules.isValidHost(publicHost)) errors.add("부팅 확인에 쓸 일반 접속 주소가 올바르지 않습니다.")
                if (!InputRules.isValidPort(publicRdpPort)) errors.add("부팅 확인에 쓸 일반 접속 포트는 1~65535 사이여야 합니다.")
                if (bootWaitSeconds !in 10..3600) errors.add("부팅 대기 시간은 10~3600초 사이여야 합니다.")
            }
            return errors
        }
        if (!InputRules.isValidHost(publicHost)) errors.add("일반 접속 주소(DDNS 또는 공인 IP)가 올바르지 않습니다.")
        if (!InputRules.isValidPort(publicRdpPort)) errors.add("일반 접속 원격 데스크톱 외부 포트는 1~65535 사이여야 합니다.")
        if (bootWaitSeconds !in 10..3600) errors.add("부팅 대기 시간은 10~3600초 사이여야 합니다.")
        return errors
    }

    /** 설정 내보내기용 JSON. 비밀번호·보안문자·쿠키는 원래 설정에 없으므로 들어가지 않는다. */
    fun toExportJson(): String {
        val copy = clone()
        copy.settingsVersion = CURRENT_SETTINGS_VERSION
        return json.encodeToString(serializer(), copy)
    }

    companion object {
        /** 설정 파일 형식 버전(Windows 버전과 같은 번호를 쓴다). */
        const val CURRENT_SETTINGS_VERSION = 3
        const val MIN_LAYOUT_WIDTH = 600
        const val MAX_LAYOUT_WIDTH = 2400

        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            allowTrailingComma = true
            allowComments = true
        }

        private val knownNames: Map<String, String> by lazy {
            val d = serializer().descriptor
            (0 until d.elementsCount).associate { d.getElementName(it).lowercase(Locale.ROOT) to d.getElementName(it) }
        }

        /** 속성 이름을 대소문자 구분 없이 맞춘다(Windows 버전은 대소문자를 가리지 않고 읽는다). */
        private fun normalizeKeys(obj: JsonObject): JsonObject =
            JsonObject(obj.entries.associate { (k, v) -> (knownNames[k.lowercase(Locale.ROOT)] ?: k) to v })

        private fun decode(text: String): AppSettings {
            val obj = json.parseToJsonElement(text).jsonObject
            val s = json.decodeFromJsonElement(serializer(), normalizeKeys(obj))
            s.migrate()
            return s
        }

        fun load(file: File, log: AppLog? = null): AppSettings {
            return try {
                if (!file.exists()) AppSettings() else decode(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                log?.warn("설정 파일을 읽지 못해 기본값을 사용합니다: ${e.message}")
                AppSettings()
            }
        }

        /** 내보낸 설정 파일을 읽는다. 이 프로그램의 설정 파일이 아니면 (null, 이유)를 돌려준다. */
        fun fromExportJson(text: String): Pair<AppSettings?, String?> {
            return try {
                val root = json.parseToJsonElement(text.trimStart('﻿'))
                val obj = root as? JsonObject
                val known = obj != null && obj.keys.any {
                    it.equals("RouterUrl", ignoreCase = true) || it.equals("SettingsVersion", ignoreCase = true)
                }
                if (!known) return null to "RemoteAccessHub 설정 파일이 아닙니다."
                val s = json.decodeFromJsonElement(serializer(), normalizeKeys(obj!!))
                s.migrate()
                s to null
            } catch (e: Exception) {
                null to ("JSON 형식이 올바르지 않습니다: " + (e.message ?: "").lineSequence().first())
            }
        }

        /** 진단용: 문자열 값 하나를 JSON 문자열로. */
        internal fun quote(s: String): String = JsonPrimitive(s).toString()
    }
}
