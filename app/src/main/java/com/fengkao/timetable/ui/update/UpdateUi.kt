package com.fengkao.timetable.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.fengkao.timetable.BuildConfig
import com.fengkao.timetable.data.update.ApkUpdater
import com.fengkao.timetable.data.update.UpdateChecker
import com.fengkao.timetable.data.update.UpdateDownloadState
import com.fengkao.timetable.data.update.UpdateInfo
import com.fengkao.timetable.ui.theme.BrandGreen
import com.fengkao.timetable.ui.theme.BrandGreenSoft
import com.fengkao.timetable.ui.theme.CardWhite
import com.fengkao.timetable.ui.theme.InkDark
import com.fengkao.timetable.ui.theme.InkGray
import com.fengkao.timetable.ui.theme.InkMid
import java.io.File

/**
 * 发现新版本 / 应用内下载安装弹窗。
 *
 * 完整支持：更新日志展示、OkHttp 流式下载实时进度条、SHA-256 校验、
 * Android 8+ 未知来源权限引导、一键拉起系统安装器以及浏览器下载兜底。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    downloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallApk: (File) -> Unit,
    onOpenBrowser: () -> Unit,
    onLater: () -> Unit,
    onSkipVersion: () -> Unit,
) {
    val context = LocalContext.current
    var canInstall by remember { mutableStateOf(ApkUpdater.canInstallPackages(context)) }
    var autoLaunchedPath by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        canInstall = ApkUpdater.canInstallPackages(context)
        onPauseOrDispose {}
    }

    LaunchedEffect(downloadState, canInstall) {
        if (downloadState is UpdateDownloadState.ReadyToInstall &&
            canInstall &&
            autoLaunchedPath != downloadState.apkFile.absolutePath
        ) {
            autoLaunchedPath = downloadState.apkFile.absolutePath
            onInstallApk(downloadState.apkFile)
        }
    }

    val downloading = downloadState is UpdateDownloadState.Downloading || downloadState is UpdateDownloadState.Verifying

    AlertDialog(
        onDismissRequest = { if (!downloading) onLater() },
        containerColor = CardWhite,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Text("发现新版本 ${info.version}", color = InkDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val sizeLabel = if (info.apkSizeBytes > 0L) " · 约 ${ApkUpdater.formatBytes(info.apkSizeBytes)}" else ""
                Text(
                    "当前版本 ${BuildConfig.VERSION_NAME}$sizeLabel",
                    color = InkGray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    if (info.notes.isNotBlank()) {
                        Text(cleanNotes(info.notes), color = InkMid, fontSize = 13.sp, lineHeight = 20.sp)
                    } else {
                        Text("这个版本带来了一些改进与修复。", color = InkMid, fontSize = 13.sp)
                    }
                }

                when (downloadState) {
                    UpdateDownloadState.Idle -> Unit
                    is UpdateDownloadState.Downloading -> {
                        val p = downloadState.progress
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (p != null) {
                                LinearProgressIndicator(
                                    progress = { p },
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                    color = BrandGreen,
                                    trackColor = BrandGreenSoft,
                                )
                                Text(
                                    "正在下载 ${ApkUpdater.formatBytes(downloadState.downloadedBytes)} / ${ApkUpdater.formatBytes(downloadState.totalBytes)}（${(p * 100).toInt()}%）",
                                    fontSize = 12.sp,
                                    color = BrandGreen,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                    color = BrandGreen,
                                    trackColor = BrandGreenSoft,
                                )
                                Text(
                                    "正在下载 ${ApkUpdater.formatBytes(downloadState.downloadedBytes)}…",
                                    fontSize = 12.sp,
                                    color = BrandGreen,
                                )
                            }
                        }
                    }
                    is UpdateDownloadState.Verifying -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                color = BrandGreen,
                                trackColor = BrandGreenSoft,
                            )
                            Text("正在校验安装包完整性（SHA-256）…", fontSize = 12.sp, color = BrandGreen)
                        }
                    }
                    is UpdateDownloadState.ReadyToInstall -> {
                        Text(
                            if (canInstall) "安装包已就绪，请点击下方确认覆盖安装。"
                            else "安装包已下载完成，需先在系统设置中允许本应用安装未知来源应用，授权后将自动继续。",
                            fontSize = 12.sp,
                            color = BrandGreen,
                            lineHeight = 18.sp,
                        )
                    }
                    is UpdateDownloadState.Error -> {
                        Text(
                            "应用内下载失败：${downloadState.message}。可点击重试或改用浏览器下载。",
                            fontSize = 12.sp,
                            color = Color(0xFFD64541),
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (downloadState) {
                is UpdateDownloadState.Downloading,
                is UpdateDownloadState.Verifying -> {
                    TextButton(onClick = onCancelDownload) {
                        Text("取消下载", color = InkGray, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                is UpdateDownloadState.ReadyToInstall -> {
                    if (canInstall) {
                        TextButton(onClick = { onInstallApk(downloadState.apkFile) }) {
                            Text("立即安装", color = BrandGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        TextButton(onClick = { ApkUpdater.openUnknownSourcesSettings(context) }) {
                            Text("去授权安装", color = BrandGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                is UpdateDownloadState.Error -> {
                    TextButton(onClick = onStartDownload) {
                        Text("重试下载", color = BrandGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                UpdateDownloadState.Idle -> {
                    TextButton(
                        onClick = {
                            if (info.apkUrl != null) onStartDownload() else onOpenBrowser()
                        },
                    ) {
                        Text(
                            if (info.apkUrl != null) "应用内更新" else "查看发布页",
                            color = BrandGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
        dismissButton = {
            when (downloadState) {
                is UpdateDownloadState.Downloading,
                is UpdateDownloadState.Verifying -> {
                    TextButton(onClick = onOpenBrowser) { Text("改用浏览器下载", color = BrandGreen, fontSize = 13.sp) }
                }
                is UpdateDownloadState.ReadyToInstall -> {
                    TextButton(onClick = onLater) { Text("稍后安装", color = InkGray, fontSize = 13.sp) }
                }
                is UpdateDownloadState.Error -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onLater) { Text("关闭", color = InkGray, fontSize = 13.sp) }
                        TextButton(onClick = onOpenBrowser) { Text("浏览器下载", color = BrandGreen, fontSize = 13.sp) }
                    }
                }
                UpdateDownloadState.Idle -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onLater) { Text("以后再说", color = InkGray, fontSize = 13.sp) }
                        TextButton(onClick = onSkipVersion) { Text("不再提示", color = InkGray, fontSize = 13.sp) }
                    }
                }
            }
        },
    )
}

/** 关于页里那行「检查更新 / 有新版本」入口。 */
@Composable
fun UpdateRow(
    checking: Boolean,
    latest: UpdateInfo?,
    hint: String?,
    hintError: Boolean,
    onCheck: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    checking -> "正在检查更新…"
                    latest != null -> "发现新版本 ${latest.version}"
                    else -> "检查更新"
                },
                color = if (latest != null) BrandGreen else InkDark,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            val sub = when {
                latest != null -> "当前 ${BuildConfig.VERSION_NAME} · 点击在应用内更新"
                checking -> "正在拉取最新版本信息"
                hint != null -> hint
                else -> "当前版本 ${BuildConfig.VERSION_NAME}"
            }
            Text(
                sub,
                color = if (hintError) InkMid else InkGray,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Spacer(Modifier.padding(horizontal = 4.dp))
        TextButton(onClick = onCheck, enabled = !checking) {
            Text(
                if (latest != null) "更新" else "检查",
                color = if (checking) InkGray else BrandGreen,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (checking) BrandGreenSoft.copy(alpha = 0.4f) else BrandGreenSoft)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** 浏览器兜底打开直连 APK（支持可选镜像前缀）或发布页 */
fun openUpdatePage(context: Context, info: UpdateInfo, mirrorPrefix: String = "") {
    val rawUrl = info.apkUrl ?: info.pageUrl
    val url = UpdateChecker.applyMirror(rawUrl, mirrorPrefix)
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private val mdLink = Regex("""\[([^\]]*)]\(([^)]*)\)""")

internal fun cleanNotes(raw: String): String = raw.lineSequence()
    .map { line ->
        line
            // 列表符号必须先转成「·」，否则下一步会把 `*` 当强调符号吃掉
            .replace(Regex("""^\s*[-*+]\s+"""), "· ")
            .replace(Regex("""^\s*#{1,6}\s*"""), "")
            .let { mdLink.replace(it, "$1") }
            .replace(Regex("""(\*\*|__|\*|`|~~)"""), "")
            .trim()
    }
    .filter { it.isNotBlank() }
    .joinToString("\n")
