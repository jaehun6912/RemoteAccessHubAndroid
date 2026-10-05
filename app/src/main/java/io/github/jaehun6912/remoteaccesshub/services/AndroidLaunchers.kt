package io.github.jaehun6912.remoteaccesshub.services

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.jaehun6912.remoteaccesshub.core.AppLog
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

/** 크롬 원격 데스크톱을 연다. 앱이 있으면 앱으로(가능하면 저장한 기기까지), 없으면 브라우저로. */
class AndroidCrdLauncher(private val context: Context, private val log: AppLog) : CrdLauncher {
    override fun open(hostId: String?): CrdOpenTarget {
        val url = RemoteLinks.crdUrl(hostId)
        val where = if (url == RemoteLinks.CRD_ACCESS_URL) "기기 목록" else "저장된 기기 세션"
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(RemoteLinks.CRD_PACKAGE)
        if (launch != null) {
            // 1) 앱이 그 주소를 받으면 앱으로 원하는 화면까지 연다.
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .setPackage(RemoteLinks.CRD_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                log.info("크롬 원격 데스크톱 앱 열기: $where")
                return CrdOpenTarget.App
            } catch (_: ActivityNotFoundException) {
                log.info("크롬 원격 데스크톱 앱이 주소를 받지 않아 앱 첫 화면을 엽니다.")
            }
            // 2) 주소를 넘길 수 없으면 앱만 띄운다(앱 첫 화면 = 기기 목록).
            try {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                log.info("크롬 원격 데스크톱 앱 열기: 앱 첫 화면(주소 전달 불가)")
                return CrdOpenTarget.AppHome
            } catch (e: ActivityNotFoundException) {
                log.warn("크롬 원격 데스크톱 앱 실행 실패, 브라우저로 엽니다: ${e.message}")
            }
        }
        // 3) 앱이 없거나 실행하지 못한 경우: 브라우저.
        log.info("크롬 원격 데스크톱 브라우저로 열기: $where")
        val browser = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(browser)
        return CrdOpenTarget.Browser
    }
}
