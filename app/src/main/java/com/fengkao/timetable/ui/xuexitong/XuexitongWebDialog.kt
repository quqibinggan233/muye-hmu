package com.fengkao.timetable.ui.xuexitong

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fengkao.timetable.ui.theme.BrandGreen
import okhttp3.Cookie

/**
 * 带学习通登录态 Cookie 的内置直达页弹窗。
 *
 * 点击通知/作业/评价的「直达原页面」后，先同步 OkHttp 会话中的学习通 Cookie 到
 * [CookieManager]，免二次登录直达目标 H5 页面；同时安全拦截学习通自定义 scheme
 *（如 `chaoxing://`、`ssreader://`、`intent://`），避免 WebView 报错。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XuexitongWebDialog(
    title: String,
    url: String,
    cookies: List<Cookie>,
    onDismiss: () -> Unit,
    onOpenInApp: (title: String, url: String) -> Unit,
) {
    val context = LocalContext.current
    val initialUrl = remember(url) { XuexitongLauncher.normalizeUrl(url) ?: url }
    var progress by remember { mutableIntStateOf(0) }
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var pageTitle by remember(title) { mutableStateOf(title.ifBlank { "学习通详情" }) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(22.dp))
                .background(NoticeCard),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = NoticeInk, modifier = Modifier.size(20.dp))
                }
                Text(
                    pageTitle,
                    color = NoticeInk,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onOpenInApp(pageTitle, currentUrl) }) {
                    Text("学习通 App", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BrandGreen)
                }
                TextButton(onClick = { XuexitongLauncher.openInBrowser(context, currentUrl) }) {
                    Text("浏览器", fontSize = 12.sp, color = NoticeBlue)
                }
            }

            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = BrandGreen,
                    trackColor = Color.Transparent,
                )
            } else {
                HorizontalDivider(color = NoticeHairline)
            }

            Box(Modifier.fillMaxSize().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                userAgentString = MOBILE_UA
                            }
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            syncCookies(cm, initialUrl, cookies)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView?, newTitle: String?) {
                                    val clean = newTitle?.trim().orEmpty()
                                    if (clean.isNotBlank() &&
                                        !clean.startsWith("http") &&
                                        clean != "网页无法打开"
                                    ) {
                                        pageTitle = clean
                                    }
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val target = request?.url ?: return false
                                    val scheme = target.scheme?.lowercase().orEmpty()
                                    if (scheme == "http") {
                                        val raw = target.toString()
                                        val upgraded = XuexitongLauncher.normalizeUrl(raw)
                                        if (upgraded != null && upgraded != raw) {
                                            currentUrl = upgraded
                                            view?.loadUrl(upgraded)
                                            return true
                                        }
                                        currentUrl = raw
                                        return false
                                    }
                                    if (scheme == "https") {
                                        currentUrl = target.toString()
                                        return false
                                    }
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, target).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        ctx.startActivity(intent)
                                    }
                                    return true
                                }

                                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                    if (!finishedUrl.isNullOrBlank()) currentUrl = finishedUrl
                                }
                            }
                            webViewRef = this
                            loadUrl(initialUrl)
                        }
                    },
                )
            }
        }
    }
}

private fun syncCookies(cm: CookieManager, targetUrl: String, cookies: List<Cookie>) {
    val parsedTarget = runCatching { Uri.parse(targetUrl) }.getOrNull()
    val targetHost = parsedTarget?.host.orEmpty()
    for (cookie in cookies) {
        val rawHeader = buildString {
            append(cookie.name).append('=').append(cookie.value)
            append("; path=").append(cookie.path.ifBlank { "/" })
            if (!cookie.hostOnly) append("; domain=").append(cookie.domain)
            if (cookie.secure) append("; Secure")
            if (cookie.httpOnly) append("; HttpOnly")
        }
        val cleanDomain = cookie.domain.trimStart('.')
        if (cleanDomain.isNotBlank()) {
            cm.setCookie("https://$cleanDomain", rawHeader)
        }
        if (targetHost.isNotBlank() &&
            (targetHost == cleanDomain || targetHost.endsWith(".$cleanDomain"))
        ) {
            cm.setCookie(targetUrl, rawHeader)
        }
    }
    cm.flush()
}

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Mobile Safari/537.36"
