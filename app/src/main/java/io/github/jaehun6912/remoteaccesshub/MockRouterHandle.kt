package io.github.jaehun6912.remoteaccesshub

/** 디버그 빌드의 모의 공유기(DebugTools). 배포(release) 빌드에는 없다. */
interface MockRouterHandle {
    /** 예: http://127.0.0.1:41234/ */
    val baseUrl: String

    /** 원격 데스크톱 포트 대신 연결만 받아 주는 가짜 포트. */
    val fakeRdpPort: Int

    fun close()

    companion object {
        /** 모의 공유기 WOL 목록의 기본 대상 PC 이름(Windows 자체검사와 같음). */
        const val DEFAULT_TARGET_NAME = "MY-PC"
    }
}
