package com.fengkao.timetable.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** 更新元数据（兼容 GitHub Releases `/releases/latest` 与可选静态 `update.json`）。 */
data class UpdateInfo(
    /** 去掉前缀 v 的版本号，如 0.2.0 */
    val version: String,
    /** Release 标题 */
    val title: String,
    /** Release 说明正文（Markdown 原文） */
    val notes: String,
    /** 浏览器可打开的发布页 */
    val pageUrl: String,
    /** 直连 APK 下载地址（优先按本机 ABI 匹配） */
    val apkUrl: String?,
    /** 是否比当前安装版本更新 */
    val newer: Boolean,
    val versionCode: Int = 0,
    val apkSizeBytes: Long = 0L,
    val sha256: String? = null,
)

/**
 * 检查应用更新：
 * 1. 若配置了静态清单 `manifestUrl`，优先拉取（可选走下载镜像前缀），失败再回退到 GitHub API；
 * 2. 若配置了 `owner/repo`，查询 GitHub `/repos/{owner}/{repo}/releases/latest` 并按设备 ABI 选择 APK。
 */
class UpdateChecker(
    private val apiBase: String = "https://api.github.com",
    private val owner: String = "",
    private val repo: String = "",
    private val currentVersion: String,
    private val currentVersionCode: Int = 0,
    private val manifestUrl: String = "",
    private val mirrorPrefix: String = "",
    private val supportedAbis: List<String> = emptyList(),
    client: OkHttpClient? = null,
) {

    private val http: OkHttpClient = (client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build())

    suspend fun check(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanManifest = manifestUrl.trim()
            val cleanOwner = owner.trim()
            val cleanRepo = repo.trim()
            val hasGithub = cleanOwner.isNotBlank() && cleanRepo.isNotBlank()

            if (cleanManifest.isBlank() && !hasGithub) {
                throw IllegalStateException("尚未配置更新仓库，请先在「关于 → 更新源与加速设置」填入 owner/repo")
            }

            if (cleanManifest.isNotBlank()) {
                val manifestResult = runCatching { fetchStaticManifest(cleanManifest) }
                manifestResult.getOrNull()?.let { return@runCatching it }
                if (!hasGithub) {
                    throw manifestResult.exceptionOrNull() ?: IOException("静态更新清单读取失败")
                }
            }

            fetchGithubLatest(cleanOwner, cleanRepo)
        }
    }

    private fun fetchStaticManifest(rawUrl: String): UpdateInfo {
        val candidates = listOf(applyMirror(rawUrl, mirrorPrefix), rawUrl).distinct()
        var lastError: Throwable? = null
        for (url in candidates) {
            try {
                val body = executeGet(url, accept = "application/json")
                return parseStaticManifest(body, rawUrl)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IOException("静态更新清单读取失败")
    }

    private fun parseStaticManifest(body: String, fallbackPageUrl: String): UpdateInfo {
        val json = JSONObject(body)
        val rawVersion = json.optString("version")
            .ifBlank { json.optString("tag_name") }
            .ifBlank { json.optString("name") }
        val version = normalizeVersion(rawVersion)
        if (version.isBlank()) throw IOException("更新清单版本号无法识别")
        val versionCode = json.optInt("versionCode", 0)
        val notes = json.optString("notes").ifBlank { json.optString("body") }.trim()
        val sha256 = cleanSha256(json.optString("sha256")) ?: extractSha256FromText(notes)
        val apkUrl = json.optString("apkUrl")
            .ifBlank { json.optString("downloadUrl") }
            .trim()
            .takeIf { it.isNotBlank() }
        val pageUrl = json.optString("pageUrl")
            .ifBlank { json.optString("html_url") }
            .ifBlank { fallbackPageUrl }
        return UpdateInfo(
            version = version,
            title = json.optString("title").ifBlank { "牧野生活 $version" },
            notes = notes,
            pageUrl = pageUrl,
            apkUrl = apkUrl,
            newer = isNewerRelease(version, versionCode, currentVersion, currentVersionCode),
            versionCode = versionCode,
            apkSizeBytes = json.optLong("size", json.optLong("apkSizeBytes", 0L)).coerceAtLeast(0L),
            sha256 = sha256,
        )
    }

    private fun fetchGithubLatest(cleanOwner: String, cleanRepo: String): UpdateInfo {
        val base = apiBase.trimEnd('/')
        val body = executeGet(
            url = "$base/repos/$cleanOwner/$cleanRepo/releases/latest",
            accept = "application/vnd.github+json",
        )
        val json = JSONObject(body)
        val tag = json.optString("tag_name").ifBlank { json.optString("name") }
        val version = normalizeVersion(tag)
        if (version.isBlank()) throw IOException("版本号无法识别")
        val notes = json.optString("body").trim()
        val asset = selectBestApkAsset(json.optJSONArray("assets"), supportedAbis)
        val sha256 = asset?.sha256 ?: extractSha256FromText(notes)
        return UpdateInfo(
            version = version,
            title = json.optString("name").ifBlank { "牧野生活 $version" },
            notes = notes,
            pageUrl = json.optString("html_url").ifBlank { "https://github.com/$cleanOwner/$cleanRepo/releases" },
            apkUrl = asset?.url,
            newer = isNewerRelease(version, 0, currentVersion, currentVersionCode),
            versionCode = 0,
            apkSizeBytes = asset?.sizeBytes ?: 0L,
            sha256 = sha256,
        )
    }

    private fun executeGet(url: String, accept: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "muye-android/$currentVersion")
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        403, 429 -> "请求太频繁，稍后再试"
                        404 -> "还没有发布过版本或仓库不存在"
                        else -> "服务器返回 ${response.code}"
                    },
                )
            }
            response.body?.string().orEmpty()
        }
    }

    private data class SelectedAsset(
        val url: String,
        val sizeBytes: Long,
        val sha256: String?,
    )

    private fun selectBestApkAsset(assets: JSONArray?, abis: List<String>): SelectedAsset? {
        if (assets == null || assets.length() == 0) return null
        val allApks = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter {
                it.optString("name").endsWith(".apk", ignoreCase = true) &&
                    it.optString("browser_download_url").isNotBlank()
            }
        if (allApks.isEmpty()) return null
        val releaseApks = allApks.filterNot { it.optString("name").contains("-debug", ignoreCase = true) }
        val pool = releaseApks.ifEmpty { allApks }

        var chosen: JSONObject? = null
        for (abi in abis) {
            chosen = pool.firstOrNull { it.optString("name").contains(abi, ignoreCase = true) }
            if (chosen != null) break
        }
        if (chosen == null) {
            val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            chosen = pool.firstOrNull { item ->
                val name = item.optString("name").lowercase()
                knownAbis.none { it in name }
            } ?: pool.first()
        }

        val digest = chosen.optString("digest").removePrefix("sha256:")
        return SelectedAsset(
            url = chosen.optString("browser_download_url"),
            sizeBytes = chosen.optLong("size", 0L).coerceAtLeast(0L),
            sha256 = cleanSha256(digest),
        )
    }

    companion object {
        private val SHA256_LINE_REGEX = Regex("""sha-?256[^\n\r]{0,32}?([a-fA-F0-9]{64})""", RegexOption.IGNORE_CASE)
        private val SHA256_EXACT_REGEX = Regex("""^[a-fA-F0-9]{64}$""")
        private val SLUG_PART_REGEX = Regex("""^[A-Za-z0-9_.-]+$""")
        private val GITHUB_HOSTS = setOf(
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

        /** "v1.2.0" / "1.2.0-beta" -> "1.2.0" */
        fun normalizeVersion(raw: String): String =
            raw.trim().removePrefix("v").removePrefix("V")
                .takeWhile { it.isDigit() || it == '.' }
                .trimEnd('.')

        /** 语义化比较：逐段比数字，缺的段按 0 算；无法解析返回 false。 */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.').map { it.toIntOrNull() ?: return false }
            val b = current.split('.').map { it.toIntOrNull() ?: return false }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** 先比较语义版本号；主版本号一致时再比较递增的 `versionCode`。 */
        fun isNewerRelease(
            candidateVersion: String,
            candidateCode: Int,
            currentVersion: String,
            currentCode: Int,
        ): Boolean {
            val cleanCand = normalizeVersion(candidateVersion)
            val cleanCur = normalizeVersion(currentVersion)
            if (cleanCand.isBlank() || cleanCur.isBlank()) return false
            if (isNewer(cleanCand, cleanCur)) return true
            if (isNewer(cleanCur, cleanCand)) return false
            return candidateCode > 0 && currentCode > 0 && candidateCode > currentCode
        }

        /** 支持 `owner/repo` 与完整 `https://github.com/owner/repo(.git)` 输入。 */
        fun parseRepoSlug(raw: String): Pair<String, String>? {
            val trimmed = raw.trim().removeSuffix(".git").trim('/')
            if (trimmed.isBlank()) return null
            val path = if (trimmed.contains("github.com", ignoreCase = true)) {
                val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
                runCatching { URI(withScheme).path }.getOrNull().orEmpty().trim('/')
            } else {
                trimmed
            }
            val parts = path.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            val owner = parts[0]
            val repo = parts[1]
            if (!SLUG_PART_REGEX.matches(owner) || !SLUG_PART_REGEX.matches(repo)) return null
            return owner to repo
        }

        /** 对 GitHub 资源 URL 拼接可选下载加速前缀（如 `https://gh-proxy.com/`）。 */
        fun applyMirror(url: String, mirrorPrefix: String): String {
            val cleanUrl = url.trim()
            val prefix = mirrorPrefix.trim().trimEnd('/')
            if (prefix.isBlank() || cleanUrl.isBlank()) return cleanUrl
            if (cleanUrl.startsWith(prefix, ignoreCase = true)) return cleanUrl
            val host = runCatching { URI(cleanUrl).host?.lowercase() }.getOrNull() ?: return cleanUrl
            if (host !in GITHUB_HOSTS) return cleanUrl
            return "$prefix/$cleanUrl"
        }

        fun extractSha256FromText(text: String): String? {
            if (text.isBlank()) return null
            return SHA256_LINE_REGEX.find(text)?.groupValues?.getOrNull(1)?.lowercase()
        }

        private fun cleanSha256(raw: String): String? =
            raw.trim().lowercase().takeIf { SHA256_EXACT_REGEX.matches(it) }
    }
}
