package com.hmu.muye.data.jw

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fengkao.timetable.ui.theme.BrandGreen
import com.fengkao.timetable.ui.theme.CardWhite
import com.fengkao.timetable.ui.theme.InkDark
import com.fengkao.timetable.ui.theme.InkGray
import com.fengkao.timetable.ui.theme.SageBg

/**
 * 超星综合教学管理系统内置 WebView 登录页：
 * 支持账密（含图形验证码）、学校统一身份认证（CAS）及学习通扫码，
 * 由用户在页面正常完成认证后自动或手动采集 Cookie，不绕过任何验证。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChaoxingWebLoginDialog(
    baseUrl: String = ChaoxingJwStore.DEFAULT_JW_BASE,
    onDismiss: () -> Unit,
    onCapturedCookies: (String) -> Unit,
) {
    val loginUrl = baseUrl.trimEnd('/') + "/admin/login"
    var currentUrl by remember { mutableStateOf(loginUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var captured by remember { mutableStateOf(false) }

    fun mergeCookieStrings(vararg rawHeaders: String?): String {
        val merged = linkedMapOf<String, String>()
        for (raw in rawHeaders) {
            raw.orEmpty().split(";").forEach { pair ->
                val trimmed = pair.trim()
                val idx = trimmed.indexOf('=')
                if (idx > 0) {
                    merged[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
                }
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun tryCapture(url: String?, manual: Boolean) {
        if (captured) return
        val cm = CookieManager.getInstance()
        cm.flush()
        val targetUrl = url.orEmpty()
        val cookies = mergeCookieStrings(
            cm.getCookie(baseUrl),
            cm.getCookie(baseUrl.trimEnd('/') + "/admin"),
            targetUrl.takeIf { it.startsWith("http") }?.let { cm.getCookie(it) },
        )
        val leftLoginPage = targetUrl.startsWith(baseUrl) &&
            !targetUrl.contains("/admin/login", ignoreCase = true) &&
            !targetUrl.contains("/caslogin", ignoreCase = true)
        if (cookies.isNotBlank() && (manual || leftLoginPage)) {
            captured = true
            onCapturedCookies(cookies)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(SageBg)
                .statusBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(CardWhite)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, "关闭", tint = InkDark)
                }
                Column(Modifier.weight(1f)) {
                    Text("登录超星教务", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
                    Text(currentUrl, fontSize = 11.sp, color = InkGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { tryCapture(currentUrl, manual = true) }) {
                    Icon(Icons.Outlined.Check, null, tint = BrandGreen, modifier = Modifier.padding(end = 4.dp))
                    Text("已登录导入", color = BrandGreen, fontWeight = FontWeight.SemiBold)
                }
            }
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = BrandGreen,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        CookieManager.getInstance().setAcceptCookie(true)
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean = false

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    if (!url.isNullOrBlank()) currentUrl = url
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (!url.isNullOrBlank()) currentUrl = url
                                    tryCapture(url, manual = false)
                                }
                            }
                            loadUrl(loginUrl)
                        }
                    },
                )
            }
        }
    }
}
