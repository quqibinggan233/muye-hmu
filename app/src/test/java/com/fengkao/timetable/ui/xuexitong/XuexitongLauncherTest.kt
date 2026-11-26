package com.fengkao.timetable.ui.xuexitong

import com.fengkao.timetable.data.xuexitong.NoticeCategory
import com.fengkao.timetable.data.xuexitong.XuexitongNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class XuexitongLauncherTest {

    @Test
    fun `buildWebDeepLink encodes chaoxing url and title reversibly`() {
        val targetUrl =
            "https://mooc1-api.chaoxing.com/examApi/intoexamorwork?taskrefId=12345&courseId=67890&classId=11122&workOrExam=0&enc=abcdef0123&note=a+b"
        val title = "高等数学 第三次作业 (重点+难点)"

        val deepLink = XuexitongLauncher.buildWebDeepLink(targetUrl, title)
        val uri = URI(requireNotNull(deepLink))

        assertEquals("chaoxingshareback", uri.scheme)
        assertEquals("xuexitong.com", uri.authority)
        assertTrue(deepLink.contains("%20"))
        assertTrue(deepLink.contains("%2B"))

        val params = decodeRawQuery(uri.rawQuery)
        assertEquals("1", params["sharebacktype"])
        assertEquals(targetUrl, params["url"])
        assertEquals(title, params["title"])
    }

    @Test
    fun `buildWebDeepLink rejects invalid schemes and caps long titles`() {
        assertNull(XuexitongLauncher.buildWebDeepLink(null))
        assertNull(XuexitongLauncher.buildWebDeepLink("   "))
        assertNull(XuexitongLauncher.buildWebDeepLink("javascript:alert(1)"))
        assertNull(XuexitongLauncher.buildWebDeepLink("chaoxing://showscreen"))

        val longTitle = "测".repeat(80)
        val deepLink = requireNotNull(XuexitongLauncher.buildWebDeepLink("https://example.com/a", longTitle))
        val params = decodeRawQuery(URI(deepLink).rawQuery)
        assertEquals(60, params["title"]?.length)
    }

    @Test
    fun `buildNoticeDeepLink validates positive numeric notice id`() {
        assertNull(XuexitongLauncher.buildNoticeDeepLink(null))
        assertNull(XuexitongLauncher.buildNoticeDeepLink(0))
        assertNull(XuexitongLauncher.buildNoticeDeepLink(-42))

        val deepLink = XuexitongLauncher.buildNoticeDeepLink(987654)
        assertEquals("chaoxingshareback://xuexitong.com?sharebacktype=4&noticeId=987654", deepLink)
    }

    @Test
    fun `fromNotice prioritizes attachmentUrl over numericNoticeId and falls back cleanly`() {
        val withAttachment = XuexitongNotice(
            id = "ID-1",
            title = "作业:《高等数学》第三次作业",
            category = NoticeCategory.HOMEWORK,
            attachmentUrl = "https://mooc1-api.chaoxing.com/examApi/intoexamorwork?taskrefId=1&courseId=2",
            numericNoticeId = 556677,
        )
        val attachmentLink = requireNotNull(
            XuexitongLauncher.buildDeepLink(XuexitongLaunchTarget.fromNotice(withAttachment))
        )
        val attachmentParams = decodeRawQuery(URI(attachmentLink).rawQuery)
        assertEquals("1", attachmentParams["sharebacktype"])
        assertEquals(withAttachment.attachmentUrl, attachmentParams["url"])
        assertEquals("《高等数学》第三次作业", attachmentParams["title"])

        val noticeWithNumericId = XuexitongNotice(
            id = "ID-2",
            title = "关于期中考试考场安排的通知",
            category = NoticeCategory.ANNOUNCEMENT,
            attachmentUrl = "",
            numericNoticeId = 556677,
        )
        val noticeLink = XuexitongLauncher.buildDeepLink(XuexitongLaunchTarget.fromNotice(noticeWithNumericId))
        assertEquals("chaoxingshareback://xuexitong.com?sharebacktype=4&noticeId=556677", noticeLink)

        val plainNotice = XuexitongNotice(
            id = "ID-3",
            title = "普通课程通知",
            category = NoticeCategory.OTHER,
            attachmentUrl = "",
            numericNoticeId = null,
        )
        val fallbackLink = requireNotNull(
            XuexitongLauncher.buildDeepLink(XuexitongLaunchTarget.fromNotice(plainNotice))
        )
        val fallbackParams = decodeRawQuery(URI(fallbackLink).rawQuery)
        assertEquals("1", fallbackParams["sharebacktype"])
        assertEquals(XuexitongLauncher.WEB_NOTICE, fallbackParams["url"])
    }

    @Test
    fun `normalizeUrl upgrades cleartext chaoxing and cldisk urls to https`() {
        val raw = "http://newes.chaoxing.com/newesMobile/goQuestion?source=2&id=1"
        val upgraded = "https://newes.chaoxing.com/newesMobile/goQuestion?source=2&id=1"
        assertEquals(upgraded, XuexitongLauncher.normalizeUrl(raw))
        assertEquals("https://d0.cldisk.com/download/abc", XuexitongLauncher.normalizeUrl("http://d0.cldisk.com/download/abc"))
        assertEquals("http://example.com/page", XuexitongLauncher.normalizeUrl("http://example.com/page"))

        val deepLink = requireNotNull(XuexitongLauncher.buildWebDeepLink(raw, "教学评价"))
        val params = decodeRawQuery(URI(deepLink).rawQuery)
        assertEquals(upgraded, params["url"])
    }

    private fun decodeRawQuery(rawQuery: String): Map<String, String> =
        rawQuery.split("&").filter { it.isNotBlank() }.associate { pair ->
            val parts = pair.split("=", limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
}
