package io.github.jaehun6912.remoteaccesshub.services

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.jaehun6912.remoteaccesshub.core.AppLog
import io.github.jaehun6912.remoteaccesshub.core.CrdOpenWith
import java.io.File

/**
 * 원격 데스크톱 앱을 연다.
 * 1) rdp:// 주소(Microsoft 원격 데스크톱/Windows App 형식) → 2) .rdp 연결 파일 → 둘 다 받을 앱이 없으면 설치 안내.
 */
class AndroidRdpLauncher(private val context: Context, private val log: AppLog) : RdpLauncher {
    override fun launch(host: String, port: Int): RdpOpenTarget {
        val uri = RemoteLinks.rdpUri(host, port)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            log.info("원격 데스크톱 앱 열기(rdp 주소)")
            return RdpOpenTarget.UriScheme
        } catch (_: ActivityNotFoundException) {
            log.info("rdp 주소를 여는 앱이 없어 .rdp 연결 파일로 엽니다.")
        }

        val dir = File(context.cacheDir, "rdp").apply { mkdirs() }
        val file = File(dir, "RemoteAccessHub.rdp")
        file.writeText(RemoteLinks.rdpFile(host, port), Charsets.UTF_8)
        val content = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(content, "application/x-rdp")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            log.info("원격 데스크톱 앱 열기(.rdp 파일)")
            return RdpOpenTarget.RdpFile
        } catch (_: ActivityNotFoundException) {
            throw RdpClientMissingException(
                "원격 데스크톱 앱이 없습니다. Play 스토어에서 Microsoft 'Windows App'(원격 데스크톱)을 설치한 뒤 다시 시도하세요.",
            )
        }
    }
}

/**
 * 크롬 원격 데스크톱을 연다.
 * 크롬 원격 데스크톱 앱은 주소를 받아도 특정 PC로 바로 가지 않고 첫 화면(기기 목록)만 연다(2026-10-05 실기기 확인,
 * 공개된 Chromium 원격 데스크톱 안드로이드 코드에도 인텐트로 기기를 지정하는 처리가 없음).
 * 그래서 기기 ID가 있으면 브라우저로 그 PC의 세션 주소를 바로 연다. 이때 앱이 주소를 가로채지 않도록 브라우저를 직접 지정한다.
 */
class AndroidCrdLauncher(private val context: Context, private val log: AppLog) : CrdLauncher {
    override fun open(hostId: String?, openWith: CrdOpenWith): CrdOpenTarget {
        val url = RemoteLinks.crdUrl(hostId)
        val saved = url != RemoteLinks.CRD_ACCESS_URL
        val launch = context.packageManager.getLaunchIntentForPackage(RemoteLinks.CRD_PACKAGE)
        val useApp = launch != null && (openWith == CrdOpenWith.App || !saved)
        if (useApp) {
            try {
                context.startActivity(launch!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                log.info("크롬 원격 데스크톱 앱 열기(기기 목록)")
                return CrdOpenTarget.AppHome
            } catch (e: ActivityNotFoundException) {
                log.warn("크롬 원격 데스크톱 앱 실행 실패, 브라우저로 엽니다: ${e.message}")
            }
        }

        val where = if (saved) "저장된 기기 세션" else "기기 목록"
        val browser = pickBrowser()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (browser != null) intent.setPackage(browser)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            if (browser == null) throw e
            log.warn("브라우저($browser)로 열지 못해 기본 동작으로 엽니다: ${e.message}")
            context.startActivity(intent.setPackage(null))
        }
        log.info("크롬 원격 데스크톱 브라우저로 열기: $where" + (browser?.let { " ($it)" } ?: ""))
        return CrdOpenTarget.Browser
    }

    /**
     * 주소를 열 브라우저. Chrome이 있으면 Chrome(크롬 원격 데스크톱 웹은 Chrome 기준), 없으면 기본 브라우저,
     * 기본 브라우저가 정해져 있지 않으면 설치된 브라우저 중 하나. 크롬 원격 데스크톱 앱은 고르지 않는다.
     */
    private fun pickBrowser(): String? {
        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(CHROME) != null) return CHROME
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val default = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // "android"는 기본 앱이 없어 고르는 창이 뜨는 경우다.
        if (default != null && default != "android" && default != RemoteLinks.CRD_PACKAGE) return default
        return pm.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }.firstOrNull { it != RemoteLinks.CRD_PACKAGE }
    }

    private companion object {
        const val CHROME = "com.android.chrome"
    }
}
