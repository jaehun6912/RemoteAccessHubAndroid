package io.github.jaehun6912.remoteaccesshub.services

import io.github.jaehun6912.remoteaccesshub.core.InputRules

/** 다른 앱에 넘길 주소·파일 내용. 검증한 값으로만 만든다(안드로이드 의존 없이 단위 검사한다). */
object RemoteLinks {
    /** 크롬 원격 데스크톱 기기 목록 화면. */
    const val CRD_ACCESS_URL = "https://remotedesktop.google.com/access"

    /** 크롬 원격 데스크톱 안드로이드 앱. */
    const val CRD_PACKAGE = "com.google.chromeremotedesktop"

    /** Microsoft 원격 데스크톱(현재 이름 Windows App) 안드로이드 앱. */
    const val MS_RDP_PACKAGE = "com.microsoft.rdc.androidx"

    /** 열 주소를 만든다. 기기 ID는 16진수와 '-'만 허용하므로 주소에 그대로 넣어도 안전하다. */
    fun crdUrl(hostId: String?): String {
        val id = InputRules.normalizeCrdHostId(hostId)
        return if (id == null) CRD_ACCESS_URL else "$CRD_ACCESS_URL/session/$id"
    }

    private fun checked(host: String, port: Int): String {
        require(InputRules.isValidHost(host)) { "원격 데스크톱 주소가 올바르지 않습니다." }
        require(InputRules.isValidPort(port)) { "원격 데스크톱 포트는 1~65535 사이여야 합니다." }
        return InputRules.hostPort(host, port)
    }

    /**
     * Microsoft 원격 데스크톱 URI(rdp://full%20address=s:host:port).
     * 비밀번호·사용자 이름은 넣지 않는다(자격 증명은 원격 데스크톱 앱에서 직접 입력).
     */
    fun rdpUri(host: String, port: Int): String = "rdp://full%20address=s:" + checked(host, port)

    /** .rdp 연결 파일 내용(주소·포트만, 자격 증명은 앱에서 묻도록). */
    fun rdpFile(host: String, port: Int): String =
        "full address:s:${checked(host, port)}\r\nprompt for credentials:i:1\r\n"
}
