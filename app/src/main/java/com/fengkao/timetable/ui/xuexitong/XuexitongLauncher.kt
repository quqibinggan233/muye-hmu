package com.fengkao.timetable.ui.xuexitong

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.fengkao.timetable.data.xuexitong.NoticeFormat
import com.fengkao.timetable.data.xuexitong.XuexitongNotice
import java.net.URI
import java.net.URLEncoder

/**
 * 调起学习通 App（`com.chaoxing.mobile`）的直达目标。
 *
 * 优先级：
 * 1. 具体任务/网页链接（如作业、评价、附件页）→ `chaoxingshareback://xuexitong.com?sharebacktype=1&url=...`
 * 2. 原生通知整数 ID（无附件 URL 时）→ `chaoxingshareback://xuexitong.com?sharebacktype=4&noticeId=...`
 * 3. 无具体链接时退回学习通通知中心网页或 App 首页。
 */
data class XuexitongLaunchTarget(
    val url: String? = null,
    val title: String? = null,
    val numericNoticeId: Int? = null,
) {
    companion object {
        fun fromNotice(notice: XuexitongNotice, titleOverride: String? = null): XuexitongLaunchTarget {
            val cleanTitle = titleOverride?.trim()?.takeIf { it.isNotBlank() }
                ?: NoticeFormat.cleanTitle(notice.title, notice.category).ifBlank { "学习通通知" }
            val attUrl = XuexitongLauncher.normalizeUrl(notice.attachmentUrl)
            val validNoticeId = notice.numericNoticeId?.takeIf { it > 0 }
            val fallbackUrl = if (attUrl == null && validNoticeId == null) XuexitongLauncher.WEB_NOTICE else attUrl
            return XuexitongLaunchTarget(
                url = fallbackUrl,
                title = cleanTitle,
                numericNoticeId = if (attUrl == null) validNoticeId else null,
            )
        }
    }
}

private fun isHttpUrl(raw: String): Boolean =
    raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)

private val HTTP_HOST_REGEX = Regex("""^http://([^/:?#]+)""", RegexOption.IGNORE_CASE)

/**
 * 优先通过学习通 `JumpActivity`（`chaoxingshareback://`）直达目标页面；
 * 若链接不适用或学习通未安装，则依次退回学习通首页或系统浏览器。
 */
object XuexitongLauncher {

    private const val PACKAGE = "com.chaoxing.mobile"
    private const val SCHEME_SHAREBACK = "chaoxingshareback"
    private const val HOST_XUEXITONG = "xuexitong.com"
    private const val MAX_TITLE_CHARS = 60
    const val WEB_NOTICE = "https://notice.chaoxing.com/pc/notice/myNotice"

    /** 将 `*.chaoxing.com` / `*.cldisk.com` 的明文 `http://` 自动升级为 `https://`，避免触发 Android WebView 明文拦截。 */
    fun normalizeUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf(::isHttpUrl) ?: return null
        if (!trimmed.startsWith("http://", ignoreCase = true)) return trimmed
        val host = runCatching { URI(trimmed).host?.lowercase() }.getOrNull()
            ?: HTTP_HOST_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.lowercase()
            ?: return trimmed
        val upgradesToHttps = host == "chaoxing.com" ||
            host == "cldisk.com" ||
            host.endsWith(".chaoxing.com") ||
            host.endsWith(".cldisk.com")
        return if (upgradesToHttps) "https://" + trimmed.substring(7) else trimmed
    }

    /** 构造在学习通内置 `WebAppViewerActivity` 打开指定网页的 deep link（`sharebacktype=1`）。 */
    fun buildWebDeepLink(targetUrl: String?, title: String? = null): String? {
        val cleanUrl = normalizeUrl(targetUrl) ?: return null
        val cleanTitle = title?.trim()?.take(MAX_TITLE_CHARS)?.takeIf { it.isNotBlank() }
        return buildString {
            append(SCHEME_SHAREBACK).append("://").append(HOST_XUEXITONG)
            append("?sharebacktype=1&url=").append(encodeParam(cleanUrl))
            if (cleanTitle != null) {
                append("&title=").append(encodeParam(cleanTitle))
            }
        }
    }

    /** 构造在学习通原生 `NoticeBodyActivity` 打开指定通知的 deep link（`sharebacktype=4`）。 */
    fun buildNoticeDeepLink(numericNoticeId: Int?): String? {
        val id = numericNoticeId?.takeIf { it > 0 } ?: return null
        return "$SCHEME_SHAREBACK://$HOST_XUEXITONG?sharebacktype=4&noticeId=$id"
    }

    /** 按优先级为目标生成 `chaoxingshareback://` deep link。 */
    fun buildDeepLink(target: XuexitongLaunchTarget): String? =
        buildWebDeepLink(target.url, target.title) ?: buildNoticeDeepLink(target.numericNoticeId)

    private fun encodeParam(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    fun open(context: Context) {
        open(context, XuexitongLaunchTarget())
    }

    fun open(
        context: Context,
        targetUrl: String? = null,
        title: String? = null,
        numericNoticeId: Int? = null,
    ) {
        open(context, XuexitongLaunchTarget(url = targetUrl, title = title, numericNoticeId = numericNoticeId))
    }

    fun open(context: Context, target: XuexitongLaunchTarget) {
        val cleanUrl = normalizeUrl(target.url)
        val installedLaunch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (installedLaunch != null) {
            val deepLink = buildDeepLink(target)
            if (deepLink != null) {
                val deepIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                    setPackage(PACKAGE)
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (context.packageManager.resolveActivity(deepIntent, 0) != null &&
                    runCatching { context.startActivity(deepIntent) }.isSuccess
                ) {
                    return
                }
            }
            if (context !is Activity) installedLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(installedLaunch) }
            return
        }
        openInBrowser(context, cleanUrl ?: WEB_NOTICE)
    }

    fun openInBrowser(context: Context, targetUrl: String) {
        val cleanUrl = normalizeUrl(targetUrl) ?: WEB_NOTICE
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "未找到可用浏览器打开链接", Toast.LENGTH_SHORT).show()
        }
    }
}
