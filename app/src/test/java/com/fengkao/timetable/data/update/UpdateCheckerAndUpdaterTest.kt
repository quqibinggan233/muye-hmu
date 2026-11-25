package com.fengkao.timetable.data.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UpdateCheckerAndUpdaterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parseRepoSlug handles short slug and full github urls`() {
        assertEquals("alice" to "muye-hmu", UpdateChecker.parseRepoSlug("alice/muye-hmu"))
        assertEquals("alice" to "muye-hmu", UpdateChecker.parseRepoSlug("https://github.com/alice/muye-hmu"))
        assertEquals("alice" to "muye-hmu", UpdateChecker.parseRepoSlug("https://github.com/alice/muye-hmu.git"))
        assertNull(UpdateChecker.parseRepoSlug("invalid-only-one-segment"))
        assertNull(UpdateChecker.parseRepoSlug(""))
    }

    @Test
    fun `applyMirror prefixes github urls only and avoids double prefixing`() {
        val raw = "https://github.com/alice/muye-hmu/releases/download/v0.2.0/app.apk"
        assertEquals(
            "https://gh-proxy.com/$raw",
            UpdateChecker.applyMirror(raw, "https://gh-proxy.com/"),
        )
        assertEquals(
            "https://gh-proxy.com/$raw",
            UpdateChecker.applyMirror("https://gh-proxy.com/$raw", "https://gh-proxy.com"),
        )
        val nonGithub = "https://example.com/app.apk"
        assertEquals(nonGithub, UpdateChecker.applyMirror(nonGithub, "https://gh-proxy.com/"))
    }

    @Test
    fun `github release picks matching ABI apk and extracts digest`() = runBlocking {
        val expectedSha = "a".repeat(64)
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v0.2.0",
                  "name": "牧野生活 0.2.0",
                  "html_url": "https://github.com/alice/muye/releases/tag/v0.2.0",
                  "body": "## 更新内容\n- 修复若干问题",
                  "assets": [
                    {
                      "name": "muye-armeabi-v7a-release.apk",
                      "size": 1000,
                      "browser_download_url": "https://example.com/v7a.apk"
                    },
                    {
                      "name": "muye-arm64-v8a-release.apk",
                      "size": 2048,
                      "digest": "sha256:$expectedSha",
                      "browser_download_url": "https://example.com/arm64.apk"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val checker = UpdateChecker(
            apiBase = server.url("").toString().trimEnd('/'),
            owner = "alice",
            repo = "muye",
            currentVersion = "0.1.0-alpha",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )
        val info = checker.check().getOrThrow()
        assertEquals("0.2.0", info.version)
        assertTrue(info.newer)
        assertEquals("https://example.com/arm64.apk", info.apkUrl)
        assertEquals(2048L, info.apkSizeBytes)
        assertEquals(expectedSha, info.sha256)
    }

    @Test
    fun `extracts sha256 from release body when asset digest absent`() = runBlocking {
        val bodyHash = "0123456789abcdef".repeat(4)
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v0.2.0",
                  "body": "校验码 SHA-256: $bodyHash",
                  "assets": [
                    {"name": "muye-0.2.0.apk", "size": 512, "browser_download_url": "https://example.com/universal.apk"}
                  ]
                }
                """.trimIndent(),
            ),
        )

        val checker = UpdateChecker(
            apiBase = server.url("").toString().trimEnd('/'),
            owner = "alice",
            repo = "muye",
            currentVersion = "0.1.0",
        )
        val info = checker.check().getOrThrow()
        assertEquals(bodyHash, info.sha256)
        assertEquals("https://example.com/universal.apk", info.apkUrl)
    }

    @Test
    fun `manifestUrl takes priority and falls back to github release on error`() = runBlocking {
        // Case 1: static manifest succeeds (versionCode breaks tie on equal versionName)
        val manifestUrl = server.url("/update.json").toString()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "version": "0.1.0",
                  "versionCode": 2,
                  "notes": "热修包",
                  "apkUrl": "https://cdn.example.com/muye-0.1.0-b2.apk",
                  "size": 12345
                }
                """.trimIndent(),
            ),
        )
        val checker1 = UpdateChecker(
            apiBase = server.url("").toString().trimEnd('/'),
            owner = "alice",
            repo = "muye",
            currentVersion = "0.1.0-alpha",
            currentVersionCode = 1,
            manifestUrl = manifestUrl,
        )
        val info1 = checker1.check().getOrThrow()
        assertTrue(info1.newer)
        assertEquals("https://cdn.example.com/muye-0.1.0-b2.apk", info1.apkUrl)

        // Case 2: manifest 500 falls back to GitHub /releases/latest
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v0.3.0",
                  "assets": [{"name": "muye-0.3.0.apk", "size": 4096, "browser_download_url": "https://example.com/0.3.0.apk"}]
                }
                """.trimIndent(),
            ),
        )
        val info2 = checker1.check().getOrThrow()
        assertEquals("0.3.0", info2.version)
        assertTrue(info2.newer)
    }

    @Test
    fun `unconfigured repo and manifest returns failure`() = runBlocking {
        val checker = UpdateChecker(currentVersion = "0.1.0")
        val res = checker.check()
        assertTrue(res.isFailure)
    }

    @Test
    fun `apkUpdater streams downloads verifies sha256 and prunes old apks`() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache")
        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        val staleApk = File(updatesDir, "muye-0.0.9.apk").apply { writeText("old-apk") }

        val bytes = "mock-apk-payload-0123456789".repeat(128).toByteArray()
        val sha256 = sha256Hex(bytes)
        server.enqueue(MockResponse().setBody(String(bytes)))

        val updater = ApkUpdater(cacheDir = cacheDir)
        val states = mutableListOf<UpdateDownloadState>()
        val info = UpdateInfo(
            version = "0.2.0",
            title = "0.2.0",
            notes = "",
            pageUrl = "https://example.com",
            apkUrl = server.url("/muye-0.2.0.apk").toString(),
            newer = true,
            apkSizeBytes = bytes.size.toLong(),
            sha256 = sha256,
        )

        val result = updater.downloadAndVerify(info) { states += it }
        assertTrue(result.isSuccess)
        val downloaded = result.getOrThrow()
        assertTrue(downloaded.exists())
        assertEquals(sha256, ApkUpdater.sha256Of(downloaded))
        assertFalse(staleApk.exists())
        assertTrue(states.any { it is UpdateDownloadState.Downloading })
        assertTrue(states.any { it is UpdateDownloadState.Verifying })
        assertTrue(states.last() is UpdateDownloadState.ReadyToInstall)
    }

    @Test
    fun `apkUpdater fails and cleans up when sha256 mismatches`() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache-bad")
        server.enqueue(MockResponse().setBody("corrupted-apk-bytes"))

        val updater = ApkUpdater(cacheDir = cacheDir)
        val states = mutableListOf<UpdateDownloadState>()
        val info = UpdateInfo(
            version = "0.2.1",
            title = "0.2.1",
            notes = "",
            pageUrl = "https://example.com",
            apkUrl = server.url("/muye-0.2.1.apk").toString(),
            newer = true,
            sha256 = "f".repeat(64),
        )

        val result = updater.downloadAndVerify(info) { states += it }
        assertTrue(result.isFailure)
        assertTrue(states.last() is UpdateDownloadState.Error)
        assertEquals(0, File(cacheDir, "updates").listFiles()?.size ?: 0)
    }

    @Test
    fun `apkUpdater falls back from failing mirror to direct url`() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache-fallback")
        val payload = "direct-apk-payload"
        val failingMirrorUrl = server.url("/mirror-down/muye-0.2.2.apk").toString()
        val directUrl = server.url("/releases/muye-0.2.2.apk").toString()
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setBody(payload))

        val updater = ApkUpdater(cacheDir = cacheDir)
        val info = UpdateInfo(
            version = "0.2.2",
            title = "0.2.2",
            notes = "",
            pageUrl = "https://example.com",
            apkUrl = directUrl,
            newer = true,
            sha256 = sha256Hex(payload.toByteArray()),
        )
        val result = updater.downloadAndVerifyCandidates(listOf(failingMirrorUrl, directUrl), info) {}
        assertTrue(result.isSuccess)
        assertEquals(payload, result.getOrThrow().readText())
        assertEquals("/mirror-down/muye-0.2.2.apk", server.takeRequest().path)
        assertEquals("/releases/muye-0.2.2.apk", server.takeRequest().path)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
