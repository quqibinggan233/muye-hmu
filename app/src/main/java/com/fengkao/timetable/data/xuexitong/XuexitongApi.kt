package com.fengkao.timetable.data.xuexitong

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class XuexitongLoginResult(val ok: Boolean, val message: String)

class XuexitongSessionExpired(message: String = "学习通登录已失效，请重新登录") : IOException(message)

/**
 * 学习通（超星）只读接口：手机号/账号 + 密码登录，以及通知中心列表。
 *
 * 协议参考 DanMo661/Xuexitong-mcp（MIT）：登录走 passport2.chaoxing.com/fanyalogin，
 * 通知走 notice.chaoxing.com/pc/notice/getNoticeList，列表正文 content 即全文。
 */
class XuexitongApi(
    private val passportBase: String = "https://passport2.chaoxing.com",
    private val noticeBase: String = "https://notice.chaoxing.com",
    private val cookieJar: XuexitongCookieJar,
    client: OkHttpClient? = null,
    private val kbBase: String = "https://kb.chaoxing.com",
) {

    private val http: OkHttpClient = (client ?: OkHttpClient())
        .newBuilder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun login(account: String, password: String): XuexitongLoginResult {
        val form = FormBody.Builder()
            .add("fid", "-1")
            .add("uname", XuexitongCrypto.encrypt(account))
            .add("password", XuexitongCrypto.encrypt(password))
            .add("refer", "https%3A%2F%2Fi.chaoxing.com")
            .add("t", "true")
            .add("forbidotherlogin", "0")
            .add("validate", "")
            .add("doubleFactorLogin", "0")
            .add("independentId", "0")
            .build()
        val request = Request.Builder()
            .url(passportBase.trimEnd('/') + "/fanyalogin")
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return XuexitongLoginResult(false, "登录服务暂时不可用（${response.code}）")
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return XuexitongLoginResult(
                    false,
                    if (body.trimStart().startsWith("<")) "学习通触发了安全验证，请稍后重试或先在官方 App 登录一次"
                    else "登录响应异常，请检查网络",
                )
            val ok = json.optBoolean("status", false)
            val message = json.optString("msg2").ifBlank {
                if (ok) "登录成功" else "账号或密码错误"
            }
            return XuexitongLoginResult(ok, message)
        }
    }

    /** 读取最近的通知（默认最多两页），列表已按服务端顺序倒序返回。 */
    fun fetchNotices(maxPages: Int = 2): List<XuexitongNotice> {
        val result = mutableListOf<XuexitongNotice>()
        var lastId = ""
        var page = 0
        while (page < maxPages.coerceAtLeast(1)) {
            val form = FormBody.Builder()
                .add("type", "0")
                .add("notice_type", "0")
                .add("lastValue", lastId)
                .add("sort", "0")
                .add("folderUUID", "")
                .add("kw", "")
                .add("startTime", "")
                .add("endTime", "")
                .add("gKw", "")
                .add("gName", "")
                .add("year", "")
                .add("tag", "")
                .add("fidsCode", "")
                .add("filterSenderPuids", "")
                .add("filterTags", "")
                .build()
            val request = Request.Builder()
                .url(noticeBase.trimEnd('/') + "/pc/notice/getNoticeList")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://notice.chaoxing.com/pc/notice/myNotice")
                .post(form)
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.request.url.host.contains("passport") ||
                    body.trimStart().startsWith("<")
                ) {
                    throw XuexitongSessionExpired()
                }
                if (!response.isSuccessful) throw IOException("通知服务暂时不可用（${response.code}）")
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: throw IOException("通知接口返回异常")
                if (!json.optBoolean("status", false)) {
                    val message = json.optString("msg")
                    if (message.contains("登录") || message.contains("失效") || message.contains("未授权")) {
                        throw XuexitongSessionExpired()
                    }
                    throw IOException(message.ifBlank { "通知接口返回失败" })
                }
                val notices = json.optJSONObject("notices") ?: JSONObject()
                val list = notices.optJSONArray("list") ?: JSONArray()
                for (i in 0 until list.length()) {
                    list.optJSONObject(i)?.let { result += parseNotice(it) }
                }
                val next = notices.optString("lastGetId")
                val lastPage = notices.optBoolean("lastPage", false)
                page++
                if (lastPage || next.isBlank() || next == lastId || list.length() == 0) {
                    lastId = ""
                    page = maxPages
                } else {
                    lastId = next
                }
            }
        }
        return result.distinctBy { it.id }
    }

    private fun parseNotice(item: JSONObject): XuexitongNotice {
        val title = NoticeFormat.stripHtml(text(item, "title"))
        val content = NoticeFormat.stripHtml(text(item, "content"))
        val sender = NoticeFormat.stripHtml(text(item, "createrName"))
        val timeText = text(item, "completeTime")
        val id = listOf("idCode", "uuid", "id", "noticeId", "noticeid", "notice_id", "noticeID")
            .map { text(item, it) }
            .firstOrNull { it.isNotBlank() }
            ?: hashOf("$title|$timeText|$sender")
        val numericNoticeId = listOf("id", "noticeId", "noticeid", "notice_id", "noticeID")
            .map { text(item, it).trim() }
            .mapNotNull { it.toIntOrNull() }
            .firstOrNull { it > 0 }
        val attachment = parseAttachment(text(item, "attachment"))
        return XuexitongNotice(
            id = id,
            title = title,
            content = content,
            sender = sender,
            timeText = timeText,
            timeMillis = NoticeFormat.parseTime(timeText),
            serverUnread = isUnread(item),
            category = NoticeFormat.classify(title, content),
            deadline = NoticeFormat.extractDeadline(title, content),
            attachmentName = attachment?.name.orEmpty(),
            attachmentUrl = attachment?.url.orEmpty(),
            attachmentKind = attachment?.kind.orEmpty(),
            attachmentSize = attachment?.size.orEmpty(),
            numericNoticeId = numericNoticeId,
        )
    }

    private data class Attachment(
        val name: String,
        val url: String,
        val kind: String,
        val size: String,
    )

    private fun parseAttachment(raw: String): Attachment? {
        if (raw.isBlank() || raw == "[]" || raw == "null") return null
        val first = runCatching {
            when {
                raw.trimStart().startsWith("[") -> JSONArray(raw).optJSONObject(0)
                else -> JSONObject(raw)
            }
        }.getOrNull() ?: return null
        val web = first.optJSONObject("att_web")
        val disk = first.optJSONObject("att_clouddisk")
        return when {
            web != null -> Attachment(
                name = web.optString("title").ifBlank { web.optString("atypeName") },
                url = web.optString("url"),
                kind = web.optString("examOrWork").ifBlank { "work" },
                size = "",
            )
            disk != null -> Attachment(
                name = disk.optString("name"),
                url = disk.optString("downPath"),
                kind = "file",
                size = disk.optString("fileSize"),
            )
            else -> null
        }
    }

    private fun isUnread(item: JSONObject): Boolean {
        val value = item.opt("isread") ?: return true
        if (value == JSONObject.NULL) return true
        return when (value) {
            is Boolean -> !value
            is Number -> value.toInt() == 0
            is String -> value == "0" || value.equals("false", ignoreCase = true)
            else -> true
        }
    }

    private fun text(item: JSONObject, key: String): String {
        val value = item.opt(key) ?: return ""
        return if (value == JSONObject.NULL) "" else value.toString()
    }

    private fun hashOf(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * 从超星学习通个人课表（kb.chaoxing.com）按周次拉取整学期课表与作息配置。
     *
     * 按周顺序请求，避免并发触发风控；连续 4 周无课时提前结束。
     */
    fun fetchScheduleFull(
        account: String = "",
        onProgress: (week: Int, total: Int) -> Unit = { _, _ -> },
    ): XuexitongSchedulePayload {
        val firstPage = fetchKbWeek(week = 1)
        val totalWeeks = firstPage.maxWeek.coerceIn(1, MAX_KB_WEEKS)
        onProgress(1, totalWeeks)

        val weekly = mutableListOf(1 to firstPage.lessons)
        var consecutiveEmpty = if (firstPage.lessons.isEmpty()) 1 else 0

        for (w in 2..totalWeeks) {
            val page = fetchKbWeek(week = w)
            onProgress(w, totalWeeks)
            if (page.lessons.isEmpty()) {
                consecutiveEmpty++
                if (consecutiveEmpty >= EMPTY_WEEK_STOP_THRESHOLD) break
            } else {
                consecutiveEmpty = 0
                weekly += w to page.lessons
            }
        }
        return XuexitongScheduleParser.buildPayload(
            account = account,
            firstPage = firstPage,
            weeklyLessons = weekly,
        )
    }

    private fun fetchKbWeek(week: Int): XuexitongScheduleParser.KbPageResult {
        val base = kbBase.trimEnd('/')
        val url = "$base/pc/curriculum/getMyLessons"
        val body = okhttp3.FormBody.Builder()
            .add("week", week.toString())
            .build()
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", base)
            .header("Referer", "$base/pc/curriculum/schedule")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body)
            .build()
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val finalUrl = resp.request.url.toString()
            if (finalUrl.contains("passport2.chaoxing.com", ignoreCase = true) ||
                finalUrl.contains("fanyalogin", ignoreCase = true)
            ) {
                throw XuexitongSessionExpired("学习通登录已失效，请重新登录")
            }
            if (!resp.isSuccessful) {
                throw java.io.IOException("学习通课表请求失败（HTTP ${resp.code}）")
            }
            return XuexitongScheduleParser.parseKbResponse(raw)
        }
    }

    internal companion object {
        const val MAX_KB_WEEKS = 25
        const val EMPTY_WEEK_STOP_THRESHOLD = 4
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/118.0.0.0 Mobile Safari/537.36"
    }
}
