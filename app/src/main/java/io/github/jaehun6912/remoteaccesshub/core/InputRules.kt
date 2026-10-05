package io.github.jaehun6912.remoteaccesshub.core

import java.net.URI
import java.util.Locale

/** 공유기 관리자 주소를 풀어 둔 값. [origin]은 공유기 API를 부를 때 쓰는 scheme://host[:port](기본 포트는 생략). */
data class RouterUri(val scheme: String, val host: String, val port: Int, val text: String) {
    val isDefaultPort: Boolean get() = port == -1 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
    val effectivePort: Int get() = if (port != -1) port else if (scheme == "https") 443 else 80
    val origin: String get() = if (isDefaultPort) "$scheme://$host" else "$scheme://$host:$port"
    override fun toString(): String = text
}

/**
 * 설정값 검증 규칙. 검증을 통과한 값만 다른 앱(원격 데스크톱 앱, 브라우저)에 넘기는 주소에 쓴다.
 * Windows 버전 Core/InputRules.cs와 같은 규칙이다.
 */
object InputRules {
    private val hostnameRegex =
        Regex("^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?$")

    val macRegex = Regex(
        "\\b([0-9a-fA-F]{2})[:-]([0-9a-fA-F]{2})[:-]([0-9a-fA-F]{2})[:-]([0-9a-fA-F]{2})[:-]([0-9a-fA-F]{2})[:-]([0-9a-fA-F]{2})\\b",
    )
    private val bareMacRegex = Regex("^[0-9A-Fa-f]{12}$")
    private val ipv4Regex = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun isIpv4(host: String): Boolean {
        val m = ipv4Regex.matchEntire(host.trim()) ?: return false
        return m.groupValues.drop(1).all { it.toInt() <= 255 }
    }

    /** IPv6 문자 표기인지(대괄호 없이). DNS 조회를 하지 않도록 문자만 보고 판단한다. */
    fun isIpv6(host: String): Boolean {
        val h = host.trim()
        if (!h.contains(':')) return false
        val core = h.substringBefore('%')
        if (core.isEmpty() || core.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return false
        val doubleColon = core.indexOf("::")
        if (doubleColon >= 0 && core.indexOf("::", doubleColon + 1) >= 0) return false
        val groups = core.split(':')
        val hasV4Tail = groups.last().contains('.')
        if (hasV4Tail && !isIpv4(groups.last())) return false
        val hexGroups = if (hasV4Tail) groups.dropLast(1) else groups
        if (hexGroups.any { it.length > 4 }) return false
        val count = hexGroups.count { it.isNotEmpty() } + if (hasV4Tail) 2 else 0
        return if (doubleColon >= 0) count < 8 else count == 8 && hexGroups.none { it.isEmpty() }
    }

    fun isValidHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim()
        if (h.length > 253) return false
        if (isIpv4(h) || isIpv6(h)) return true
        return hostnameRegex.matches(h)
    }

    fun isValidPort(port: Int): Boolean = port in 1..65535

    /** MAC 주소를 "AA:BB:CC:DD:EE:FF" 형식으로 정규화한다. 형식이 아니면 null. */
    fun normalizeMac(mac: String?): String? {
        if (mac.isNullOrBlank()) return null
        val s = mac.trim()
        val m = macRegex.find(s)
        if (m != null && m.value.length == s.length) {
            return (1..6).joinToString(":") { m.groupValues[it].uppercase(Locale.ROOT) }
        }
        if (bareMacRegex.matches(s)) {
            val u = s.uppercase(Locale.ROOT)
            return (0 until 6).joinToString(":") { u.substring(it * 2, it * 2 + 2) }
        }
        return null
    }

    /** 텍스트 안의 모든 MAC 주소를 정규화하여 반환. */
    fun extractMacs(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val list = mutableListOf<String>()
        for (m in macRegex.findAll(text)) {
            val n = normalizeMac(m.value)
            if (n != null && n !in list) list.add(n)
        }
        return list
    }

    /** 진단·로그용 MAC 마스킹: AA:BB:**:**:**:FF */
    fun maskMac(text: String?): String {
        if (text.isNullOrEmpty()) return text ?: ""
        return macRegex.replace(text) { m -> "${m.groupValues[1]}:${m.groupValues[2]}:**:**:**:${m.groupValues[6]}" }
    }

    fun parseRouterUrl(url: String?): RouterUri? {
        if (url.isNullOrBlank()) return null
        val text = url.trim()
        val u = try {
            URI(text)
        } catch (_: Exception) {
            return null
        }
        if (!u.isAbsolute) return null
        val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val rawHost = u.host ?: return null
        val bare = rawHost.trim('[', ']')
        if (!isValidHost(bare)) return null
        if (u.port != -1 && !isValidPort(u.port)) return null
        val host = rawHost.lowercase(Locale.ROOT)
        return RouterUri(scheme, host, u.port, text)
    }

    fun tryParseRouterUrl(url: String?): Boolean = parseRouterUrl(url) != null

    /**
     * 크롬 원격 데스크톱 기기 ID를 정리한다. 기기 목록에서 연결했을 때 주소창에 보이는
     * https://remotedesktop.google.com/access/session/<ID> 를 통째로 붙여 넣어도 받는다.
     * 16진수와 '-'로만 이루어진 16~64자만 허용한다(주소를 만들 때 그대로 쓰기 때문).
     */
    fun normalizeCrdHostId(value: String?): String? {
        var s = (value ?: "").trim().trim('"')
        if (s.isEmpty()) return null
        if (s.contains("://") || s.contains('/')) {
            val parts = s.split('/').map { it.trim() }.filter { it.isNotEmpty() }
            s = if (parts.isEmpty()) "" else parts.last()
            val q = s.indexOfAny(charArrayOf('?', '#'))
            if (q >= 0) s = s.substring(0, q)
        }
        if (s.length < 16 || s.length > 64) return null
        var hex = 0
        for (c in s) {
            if (c.isDigit() || c in 'a'..'f' || c in 'A'..'F') hex++
            else if (c != '-') return null
        }
        return if (hex >= 16) s else null
    }

    /** host:port 문자열 (IPv6는 대괄호). */
    fun hostPort(host: String, port: Int): String {
        val h = host.trim()
        return if (isIpv6(h)) "[$h]:$port" else "$h:$port"
    }
}
