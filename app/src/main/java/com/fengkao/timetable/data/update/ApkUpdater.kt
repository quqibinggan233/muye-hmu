package com.fengkao.timetable.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** 应用内下载与安装阶段状态 */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState

    data class Downloading(
        val version: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateDownloadState {
        val progress: Float?
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    data class Verifying(val version: String) : UpdateDownloadState

    data class ReadyToInstall(val version: String, val apkFile: File) : UpdateDownloadState

    data class Error(val version: String, val message: String) : UpdateDownloadState
}

/**
 * 应用内 APK 下载与完整性校验器：
 * - 流式写入应用私有 `cacheDir/updates/` 目录，无需存储权限；
 * - 若配置了加速镜像前缀且失败，自动回退直连 URL；
 * - 支持可选 SHA-256 校验，通过后自动清理旧版本安装包并拉起系统安装器。
 */
class ApkUpdater(
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {

    suspend fun downloadAndVerify(
        info: UpdateInfo,
        mirrorPrefix: String = "",
        onState: (UpdateDownloadState) -> Unit,
    ): Result<File> {
        val rawUrl = info.apkUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("该版本未附带可直接下载的 APK 文件"))
        val candidateUrls = listOf(UpdateChecker.applyMirror(rawUrl, mirrorPrefix), rawUrl).distinct()
        return downloadAndVerifyCandidates(candidateUrls, info, onState)
    }

    internal suspend fun downloadAndVerifyCandidates(
        candidateUrls: List<String>,
        info: UpdateInfo,
        onState: (UpdateDownloadState) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        if (candidateUrls.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("没有可用的下载地址"))
        }

        val updatesDir = File(cacheDir, UPDATES_DIR).apply { mkdirs() }
        val safeVersion = info.version.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "latest" }
        val finalFile = File(updatesDir, "muye-$safeVersion.apk")
        val partFile = File(updatesDir, "muye-$safeVersion.apk.part")

        var lastError: Throwable? = null

        for (url in candidateUrls) {
            partFile.delete()
            try {
                streamToFile(
                    url = url,
                    version = info.version,
                    expectedBytes = info.apkSizeBytes,
                    targetFile = partFile,
                    onState = onState,
                )
                val expectedSha = info.sha256?.trim()?.lowercase()?.takeIf { it.length == 64 }
                if (expectedSha != null) {
                    onState(UpdateDownloadState.Verifying(info.version))
                    val actualSha = sha256Of(partFile)
                    if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                        partFile.delete()
                        throw IOException("安装包 SHA-256 校验失败，请重试")
                    }
                }
                moveReplacing(partFile, finalFile)
                pruneOldApks(updatesDir, keepName = finalFile.name)
                onState(UpdateDownloadState.ReadyToInstall(info.version, finalFile))
                return@withContext Result.success(finalFile)
            } catch (ce: CancellationException) {
                partFile.delete()
                onState(UpdateDownloadState.Idle)
                throw ce
            } catch (t: Throwable) {
                partFile.delete()
                lastError = t
            }
        }

        val msg = lastError?.message?.takeIf { it.isNotBlank() } ?: "下载失败，请检查网络或改用浏览器下载"
        onState(UpdateDownloadState.Error(info.version, msg))
        Result.failure(lastError ?: IOException(msg))
    }

    private suspend fun streamToFile(
        url: String,
        version: String,
        expectedBytes: Long,
        targetFile: File,
        onState: (UpdateDownloadState) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "muye-android-updater")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载失败（HTTP ${response.code}）")
            }
            val body = response.body ?: throw IOException("下载响应体为空")
            val contentLength = body.contentLength().takeIf { it > 0L } ?: expectedBytes.coerceAtLeast(0L)
            var downloaded = 0L
            var lastEmitAt = 0L
            onState(UpdateDownloadState.Downloading(version, 0L, contentLength))

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmitAt >= 80L) {
                            lastEmitAt = now
                            onState(UpdateDownloadState.Downloading(version, downloaded, contentLength))
                        }
                    }
                    output.flush()
                }
            }
            if (downloaded <= 0L) {
                throw IOException("下载文件为空")
            }
            if (contentLength > 0L && downloaded != contentLength) {
                throw IOException("下载文件不完整（$downloaded / $contentLength 字节）")
            }
            onState(UpdateDownloadState.Downloading(version, downloaded, contentLength))
        }
    }

    private fun moveReplacing(source: File, destination: File) {
        if (destination.exists()) destination.delete()
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun pruneOldApks(dir: File, keepName: String) {
        dir.listFiles()?.forEach { file ->
            if (file.name != keepName && (file.name.endsWith(".apk") || file.name.endsWith(".apk.part"))) {
                file.delete()
            }
        }
    }

    companion object {
        private const val UPDATES_DIR = "updates"

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8 * 1024)
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            return when {
                bytes < 1024L -> "$bytes B"
                bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
                else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }

        fun canInstallPackages(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

        fun openUnknownSourcesSettings(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }

        fun launchInstaller(context: Context, apkFile: File): Result<Unit> = runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }
}
