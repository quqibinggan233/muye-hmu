package com.hmu.muye.data.jw

import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.data.TermSpec
import com.fengkao.timetable.data.model.CachedSchedule
import com.fengkao.timetable.data.model.CourseBlock
import com.fengkao.timetable.data.model.StudentInfo
import com.hmu.muye.data.campus.HmuCampusDirectory
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

sealed interface JwFetchResult<out T> {
    data class Success<T>(val data: T) : JwFetchResult<T>
    data class NeedWebLogin(val reason: String) : JwFetchResult<Nothing>
    data class PendingPhase2Route(val message: String) : JwFetchResult<Nothing>
    data class Error(val message: String) : JwFetchResult<Nothing>
}

sealed interface JwLoginResult {
    data class Success(val cookies: String) : JwLoginResult
    data class InvalidCredentials(val message: String) : JwLoginResult
    data class NeedWebFallback(val reason: String) : JwLoginResult
    data class Error(val message: String) : JwLoginResult
}

/**
 * 超星综合教学管理系统（hnuahe.jw.chaoxing.com）纯 Kotlin 网络客户端。
 *
 * 登录态由内置 WebView 获取（兼容账密验证码 / 统一认证 / 学习通扫码），绝不绕过验证码；
 * 所有路由与 JSON Schema 均来自河南牧业经济学院实测抓包。
 */
class ChaoxingJwClient(
    private val baseUrl: String = ChaoxingJwStore.DEFAULT_JW_BASE,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {

    private sealed interface HttpFetch {
        data class Ok(val body: String) : HttpFetch
        data object Expired : HttpFetch
        data class Error(val message: String) : HttpFetch
    }

    data class RoomRef(
        val id: String,
        val code: String,
        val name: String,
        val buildingName: String,
        val encodeId: String,
    )

    /**
     * 纯 HTTP API 学号密码登录：
     * 1. GET `/admin/login` 获取初始会话 Cookie；
     * 2. POST `/admin/login` 提交学号与密码表单；
     * 3. 跟随一次 302 `/admin` 重定向收集完整会话 Cookie（如 `uid` / `yysz`）。
     * 若服务端要求图形验证码（`jcaptchaError=1`），诚实返回 [JwLoginResult.NeedWebFallback]。
     */
    fun loginWithPassword(username: String, password: String): JwLoginResult {
        val cleanUser = username.trim()
        if (cleanUser.isBlank() || password.isBlank()) {
            return JwLoginResult.InvalidCredentials("请输入教务学号和密码")
        }
        val cleanBase = baseUrl.trimEnd('/')
        val loginUrl = "$cleanBase/admin/login"
        val jar = linkedMapOf<String, String>()

        fun absorbCookies(headers: List<String>) {
            for (raw in headers) {
                val nv = raw.substringBefore(';').trim()
                val eq = nv.indexOf('=')
                if (eq > 0) {
                    jar[nv.substring(0, eq).trim()] = nv.substring(eq + 1).trim()
                }
            }
        }
        fun cookieHeader(): String = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

        return runCatching {
            val initReq = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", MOBILE_UA)
                .build()
            client.newCall(initReq).execute().use { resp ->
                absorbCookies(resp.headers("Set-Cookie"))
            }

            val form = FormBody.Builder()
                .add("username", cleanUser)
                .add("password", password)
                .add("vcode", "")
                .add("jcaptchaCode", "")
                .add("rememberMe", "1")
                .build()
            val postReq = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", MOBILE_UA)
                .header("Origin", cleanBase)
                .header("Referer", loginUrl)
                .apply {
                    val ck = cookieHeader()
                    if (ck.isNotBlank()) header("Cookie", ck)
                }
                .post(form)
                .build()

            var redirectLocation: String? = null
            var postBody = ""
            var postCode = 0
            client.newCall(postReq).execute().use { resp ->
                absorbCookies(resp.headers("Set-Cookie"))
                postCode = resp.code
                redirectLocation = resp.header("Location")
                postBody = resp.body?.string().orEmpty()
            }

            if (postCode in 300..399 && !redirectLocation.isNullOrBlank()) {
                val loc = redirectLocation!!
                if (loc.contains("jcaptchaError", ignoreCase = true) ||
                    loc.contains("captcha", ignoreCase = true)
                ) {
                    return@runCatching JwLoginResult.NeedWebFallback("教务系统当前要求图形验证，请点击切换网页完成验证")
                }
                if (loc.contains("/admin/login", ignoreCase = true) ||
                    loc.contains("loginFail", ignoreCase = true)
                ) {
                    return@runCatching JwLoginResult.InvalidCredentials("教务登录未通过，请核对学号密码或改用网页登录")
                }
                val resolved = if (loc.startsWith("http")) loc else cleanBase + (if (loc.startsWith("/")) loc else "/$loc")
                val followReq = Request.Builder()
                    .url(resolved)
                    .header("User-Agent", MOBILE_UA)
                    .header("Referer", loginUrl)
                    .apply {
                        val ck = cookieHeader()
                        if (ck.isNotBlank()) header("Cookie", ck)
                    }
                    .build()
                var followOk = false
                var followLoc: String? = null
                var followBody = ""
                client.newCall(followReq).execute().use { resp ->
                    absorbCookies(resp.headers("Set-Cookie"))
                    followOk = resp.isSuccessful
                    followLoc = resp.header("Location")
                    followBody = resp.body?.string().orEmpty()
                }
                if (!followLoc.isNullOrBlank() && followLoc!!.contains("login", ignoreCase = true)) {
                    return@runCatching JwLoginResult.NeedWebFallback("教务会话未建立，请改用网页登录")
                }
                val cookies = cookieHeader()
                if (followOk && cookies.isNotBlank() && !looksLikeLoginPage(followBody)) {
                    return@runCatching JwLoginResult.Success(cookies)
                }
            }

            val inlineErr = extractLoginError(postBody)
            if (!inlineErr.isNullOrBlank()) {
                return@runCatching JwLoginResult.InvalidCredentials(inlineErr)
            }
            if (postBody.contains("jcaptchaError", ignoreCase = true) ||
                postBody.contains("验证码", ignoreCase = false)
            ) {
                return@runCatching JwLoginResult.NeedWebFallback("教务系统要求验证码，请点击切换网页完成验证")
            }
            JwLoginResult.Error("教务登录未返回有效会话（HTTP $postCode），可切换网页登录")
        }.getOrElse { e ->
            JwLoginResult.Error("连接超星教务失败：${e.message ?: "网络异常"}")
        }
    }

    /** 校验本地持有的 Cookie 是否仍有效（未重定向回 /admin/login）。 */
    fun verifySession(cookies: String): Boolean {
        if (cookies.isBlank()) return false
        return getText("/admin", cookies, isXhr = false) is HttpFetch.Ok
    }

    fun fetchCurrentXnxq(cookies: String): String? {
        if (cookies.isBlank()) return null
        val resp = getText("/admin/api/jcsj/xnxq/getCurrentXnxq", cookies, isXhr = true)
        if (resp !is HttpFetch.Ok) return null
        return runCatching {
            val data = JSONObject(resp.body).optJSONObject("data") ?: return@runCatching null
            listOf("xnxqh", "dataXnxq")
                .map { data.optString(it).trim() }
                .firstOrNull { it.isNotBlank() && it != "null" }
        }.getOrNull()
    }

    fun fetchCurrentWeek(cookies: String, xnxqHint: String? = null): Int? {
        if (cookies.isBlank()) return null
        val resp = getText("/admin/getCurrentPkZc", cookies, isXhr = true)
        if (resp !is HttpFetch.Ok) return null
        val list = runCatching {
            val arr = JSONObject(resp.body).optJSONArray("data") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().toIntOrNull() }
        }.getOrDefault(emptyList())
        if (list.size == 1) return list.first()

        val maxWeek = list.maxOrNull()?.coerceIn(1, 30) ?: 20
        val xnxq = xnxqHint?.takeIf { it.isNotBlank() } ?: fetchCurrentXnxq(cookies)
        val startDate = xnxq?.let { fetchTermStartDate(cookies, it) }
        if (startDate != null) {
            return TermCalculator.weekOf(startDate).coerceIn(1, maxWeek)
        }
        return list.firstOrNull()
    }

    fun fetchTermStartDate(cookies: String, xnxq: String): LocalDate? {
        if (cookies.isBlank() || xnxq.isBlank()) return null
        val resp = getText("/admin/xsd/jcsj/xlgl/getBz/${encode(xnxq)}", cookies, isXhr = true)
        if (resp !is HttpFetch.Ok) return null
        return parseTermStartDateJson(resp.body)
    }

    /** 拉取指定学期课表（先读取隐含参数 xhid/xqdm，再请求 sdpkkbList JSON）。 */
    fun fetchSchedule(cookies: String, account: String, term: TermSpec): JwFetchResult<CachedSchedule> {
        if (cookies.isBlank()) {
            return JwFetchResult.NeedWebLogin("请先在「超星教务」WebView 中登录一次，以同步最新课表")
        }
        val requestedXnxq = "${term.xnm}-${term.xnm + 1}-${term.term}"
        val pageRes = getText("/admin/xsd/pkgl/xskb/queryKbForXsd?xnxq=${encode(requestedXnxq)}", cookies, isXhr = false)
        val html = when (pageRes) {
            is HttpFetch.Ok -> pageRes.body
            HttpFetch.Expired -> return JwFetchResult.NeedWebLogin("超星教务登录已过期，请点击登录以刷新会话")
            is HttpFetch.Error -> return JwFetchResult.Error(pageRes.message)
        }
        val xhid = extractHiddenInput(html, "xhid")
        if (xhid.isBlank()) {
            return JwFetchResult.NeedWebLogin("未能读取教务学生标识，请重新登录超星教务")
        }
        val xqdm = extractHiddenInput(html, "xqdm")
        val hiddenXnxq = extractHiddenInput(html, "xnxq")
        val effectiveXnxq = requestedXnxq.ifBlank { hiddenXnxq }

        val listPath = "/admin/xsd/pkgl/xskb/sdpkkbList" +
            "?xnxq=${encode(effectiveXnxq)}" +
            "&xhid=${encode(xhid)}" +
            "&xqdm=${encode(xqdm)}" +
            "&zdzc=&zxzc=&xskbxslx=0"
        val jsonRes = getText(listPath, cookies, isXhr = true)
        val raw = when (jsonRes) {
            is HttpFetch.Ok -> jsonRes.body
            HttpFetch.Expired -> return JwFetchResult.NeedWebLogin("超星教务登录已过期，请重新登录")
            is HttpFetch.Error -> return JwFetchResult.Error(jsonRes.message)
        }
        val retCode = runCatching {
            if (raw.trimStart().startsWith("{")) JSONObject(raw).optInt("ret", 0) else 0
        }.getOrDefault(0)
        if (retCode != 0) {
            val msg = runCatching { JSONObject(raw).optString("msg") }.getOrDefault("")
            if (msg.contains("登录") || msg.contains("失效")) {
                return JwFetchResult.NeedWebLogin("超星教务登录已过期，请重新登录")
            }
            return JwFetchResult.Error(msg.ifBlank { "课表查询失败（ret=$retCode）" })
        }
        val parsed = parseChaoxingSdpkkbJson(raw, account.ifBlank { xhid }, term)
            ?: return JwFetchResult.Error("课表数据格式解析失败，请稍后重试")
        return JwFetchResult.Success(parsed)
    }

    /** 拉取全部学期成绩（主走 desktop xsdQueryXscjList，异常或空结果时回退移动端 getXscjJson）。 */
    fun fetchGrades(cookies: String): JwFetchResult<List<GradeItem>> {
        if (cookies.isBlank()) {
            return JwFetchResult.NeedWebLogin("请先登录超星教务以刷新成绩")
        }
        val primary = postForm(
            "/admin/xsd/xsdcjcx/xsdQueryXscjList?fxbz=0&gridtype=jqgrid",
            cookies,
            mapOf(
                "startXnxq" to "001",
                "endXnxq" to "001",
                "sfjg" to "",
                "kclx" to "",
                "kcgs" to "",
                "page" to "1",
                "rows" to "500",
                "page.pn" to "1",
                "page.size" to "500",
            ),
            isXhr = true,
        )
        when (primary) {
            HttpFetch.Expired -> return JwFetchResult.NeedWebLogin("超星教务登录已过期，请重新登录以刷新成绩")
            is HttpFetch.Ok -> {
                val parsed = parseChaoxingGradesJson(primary.body)
                if (parsed.isNotEmpty()) return JwFetchResult.Success(parsed)
            }
            is HttpFetch.Error -> Unit
        }
        val fallback = postForm("/admin/xsd/ydd/inf/getXscjJson", cookies, emptyMap(), isXhr = true)
        return when (fallback) {
            HttpFetch.Expired -> JwFetchResult.NeedWebLogin("超星教务登录已过期，请重新登录以刷新成绩")
            is HttpFetch.Error -> JwFetchResult.Error(fallback.message)
            is HttpFetch.Ok -> JwFetchResult.Success(parseChaoxingGradesJson(fallback.body))
        }
    }

    /** 拉取当前学期考试安排。 */
    fun fetchExams(cookies: String): JwFetchResult<List<ExamItem>> {
        if (cookies.isBlank()) {
            return JwFetchResult.NeedWebLogin("请先登录超星教务以读取考试安排")
        }
        val auto = TermCalculator.autoTerm()
        val xnxq = fetchCurrentXnxq(cookies) ?: "${auto.xnm}-${auto.xnm + 1}-${auto.term}"
        val res = postForm(
            "/admin/xsd/kcapcx/ajaxListxskc?sfxsd=1&gridtype=jqgrid",
            cookies,
            mapOf(
                "xnxqh" to xnxq,
                "pcId" to "",
                "page" to "1",
                "rows" to "100",
                "page.pn" to "1",
                "page.size" to "100",
            ),
            isXhr = true,
        )
        return when (res) {
            HttpFetch.Expired -> JwFetchResult.NeedWebLogin("超星教务登录已过期，请重新登录")
            is HttpFetch.Error -> JwFetchResult.Error(res.message)
            is HttpFetch.Ok -> JwFetchResult.Success(parseChaoxingExamsJson(res.body))
        }
    }

    /** 查询龙子湖指定教学楼、星期与节次的空闲教室（复用教室课表接口反推空闲）。 */
    fun fetchEmptyRooms(
        cookies: String,
        building: String,
        periodRange: String,
        dayOfWeek: Int = LocalDate.now().dayOfWeek.value,
    ): JwFetchResult<List<EmptyRoomSlot>> {
        if (cookies.isBlank()) {
            return JwFetchResult.NeedWebLogin("请先登录超星教务以查询空教室")
        }
        val campusId = HmuCampusDirectory.LONGZIHU_CAMPUS_ID
        val matched = matchBuilding(building, HmuCampusDirectory.LONGZIHU_BUILDINGS) ?: run {
            when (val remoteRes = getText("/admin/jwxtgld/kbcx/getJxlList?xqid=${encode(campusId)}", cookies, isXhr = true)) {
                HttpFetch.Expired -> return JwFetchResult.NeedWebLogin("超星教务登录已过期，请重新登录")
                is HttpFetch.Ok -> matchBuilding(building, parseBuildingsJson(remoteRes.body))
                is HttpFetch.Error -> null
            }
        } ?: return JwFetchResult.Error("未找到对应教学楼，请从龙子湖教学楼快捷标签中选择")

        val auto = TermCalculator.autoTerm()
        val xnxq = fetchCurrentXnxq(cookies) ?: "${auto.xnm}-${auto.xnm + 1}-${auto.term}"
        val week = fetchCurrentWeek(cookies, xnxq) ?: 1

        // 注意超星教务服务端拼写为 getRommList
        val roomsRes = postForm(
            "/admin/jwxtgld/kbcx/getRommList",
            cookies,
            mapOf(
                "xqid" to campusId,
                "jxlid" to matched.id,
                "jxlbh" to matched.code,
                "xnxq" to xnxq,
                "page" to "1",
                "rows" to "200",
            ),
            isXhr = true,
        )
        val roomsRaw = when (roomsRes) {
            HttpFetch.Expired -> return JwFetchResult.NeedWebLogin("超星教务登录已过期，请重新登录")
            is HttpFetch.Error -> return JwFetchResult.Error(roomsRes.message)
            is HttpFetch.Ok -> roomsRes.body
        }
        val rooms = parseBuildingRoomsJson(roomsRaw).take(30)
        if (rooms.isEmpty()) {
            return JwFetchResult.Success(emptyList())
        }

        val targetPeriods = parsePeriodRange(periodRange)
        val dayLabel = "周${"一二三四五六日"[dayOfWeek.coerceIn(1, 7) - 1]}"
        val freeLabel = "$dayLabel $periodRange 空闲"

        val pool = Executors.newFixedThreadPool(6)
        try {
            val tasks = rooms.map { room ->
                Callable<Pair<RoomRef, HttpFetch>> {
                    val rid = room.encodeId.ifBlank { room.id }
                    val path = "/admin/jwxtgld/kbcx/getRoomkb" +
                        "?xnxq=${encode(xnxq)}&rid=${encode(rid)}&xqid=${encode(campusId)}&week=$week"
                    room to getText(path, cookies, isXhr = true)
                }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            if (results.any { it.second is HttpFetch.Expired }) {
                return JwFetchResult.NeedWebLogin("超星教务登录已过期，请重新登录")
            }
            val freeRooms = results.mapNotNull { (room, fetch) ->
                val body = (fetch as? HttpFetch.Ok)?.body ?: return@mapNotNull null
                if (!isRoomFreeForPeriods(body, dayOfWeek, targetPeriods)) return@mapNotNull null
                EmptyRoomSlot(
                    building = room.buildingName.ifBlank { matched.name },
                    roomName = room.name,
                    freePeriods = freeLabel,
                )
            }.sortedBy { it.roomName }
            return JwFetchResult.Success(freeRooms)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun getText(path: String, cookies: String, isXhr: Boolean): HttpFetch {
        val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("User-Agent", MOBILE_UA)
            .header("Referer", baseUrl.trimEnd('/') + "/admin/index")
            .apply { if (isXhr) header("X-Requested-With", "XMLHttpRequest") }
            .build()
        return executeFetch(req)
    }

    private fun postForm(path: String, cookies: String, params: Map<String, String>, isXhr: Boolean): HttpFetch {
        val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
        val form = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("User-Agent", MOBILE_UA)
            .header("Referer", baseUrl.trimEnd('/') + "/admin/index")
            .apply { if (isXhr) header("X-Requested-With", "XMLHttpRequest") }
            .post(form)
            .build()
        return executeFetch(req)
    }

    private fun executeFetch(req: Request): HttpFetch = runCatching {
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) return@use HttpFetch.Expired
            if (resp.code in 300..399) {
                val loc = resp.header("Location").orEmpty()
                if (loc.contains("login", ignoreCase = true) || loc.contains("caslogin", ignoreCase = true)) {
                    return@use HttpFetch.Expired
                }
                return@use HttpFetch.Error("教务重定向异常（${resp.code}）")
            }
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return@use HttpFetch.Error("教务服务返回异常（HTTP ${resp.code}）")
            }
            if (looksLikeLoginPage(body)) return@use HttpFetch.Expired
            HttpFetch.Ok(body)
        }
    }.getOrElse { e ->
        HttpFetch.Error("连接超星教务失败：${e.message ?: "网络异常"}")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun looksLikeLoginPage(html: String): Boolean =
            html.contains("/admin/login") ||
                html.contains("id=\"loginform\"") ||
                html.contains("jcaptchaCodeChaoxing")

        fun extractLoginError(html: String): String? {
            if (html.isBlank()) return null
            val match = Regex("""var\s+error\s*=\s*"([^"]*)"""").find(html)
            return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        }

        fun extractHiddenInput(html: String, idOrName: String): String {
            val tagRegex = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
            val idRegex = Regex("""\b(?:id|name)\s*=\s*["']${Regex.escape(idOrName)}["']""", RegexOption.IGNORE_CASE)
            val valRegex = Regex("""\bvalue\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            for (tag in tagRegex.findAll(html)) {
                val rawTag = tag.value
                if (idRegex.containsMatchIn(rawTag)) {
                    val match = valRegex.find(rawTag)
                    if (match != null) return match.groupValues[1].trim()
                }
            }
            return ""
        }

        fun stripHtml(raw: String): String {
            if (raw.isBlank()) return ""
            return raw
                .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""<[^>]+>"""), "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        fun parseTermStartDateJson(raw: String): LocalDate? = runCatching {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) return null
            val root = JSONObject(trimmed)
            val obj = root.optJSONObject("data") ?: root
            val dateText = obj.optString("ksrq").trim().take(10)
            if (dateText.length == 10) LocalDate.parse(dateText) else null
        }.getOrNull()

        fun parseWeeksExpression(raw: String, zctype: String = ""): List<Int> {
            val cleaned = raw.trim()
            if (cleaned.isBlank() || cleaned == "null") return listOf(1)
            val weeks = sortedSetOf<Int>()
            val segments = cleaned.replace("周", "").split(",", "，", ";", "；")
            val rangeRegex = Regex("""(\d+)\s*[-~至]\s*(\d+)(?:\s*[（(]?(单|双)[)）]?)?""")
            val singleRegex = Regex("""(\d+)""")
            for (seg in segments) {
                val s = seg.trim()
                if (s.isBlank()) continue
                val rm = rangeRegex.find(s)
                if (rm != null) {
                    val a = rm.groupValues[1].toIntOrNull() ?: continue
                    val b = rm.groupValues[2].toIntOrNull() ?: a
                    val tag = rm.groupValues[3]
                    val start = minOf(a, b).coerceAtLeast(1)
                    val end = maxOf(a, b).coerceAtMost(30)
                    for (w in start..end) {
                        val ok = when {
                            tag == "单" -> w % 2 == 1
                            tag == "双" -> w % 2 == 0
                            zctype == "1" -> w % 2 == 1
                            zctype == "2" -> w % 2 == 0
                            else -> true
                        }
                        if (ok) weeks += w
                    }
                } else {
                    for (sm in singleRegex.findAll(s)) {
                        val w = sm.groupValues[1].toIntOrNull() ?: continue
                        if (w in 1..30) weeks += w
                    }
                }
            }
            return if (weeks.isEmpty()) listOf(1) else weeks.toList()
        }

        private fun resolvePeriodSpan(djc: Int, djs: Int): Pair<Int, Int> {
            val start = djc.coerceIn(1, 14)
            val end = when {
                djs > 1 && djs <= 6 && djs < start -> (start + djs - 1).coerceAtMost(14)
                djs in start..14 -> djs
                else -> start
            }
            return start to end
        }

        /** 解析超星教务 `/admin/xsd/pkgl/xskb/sdpkkbList` 返回 JSON，并合并同课相邻节次。 */
        fun parseChaoxingSdpkkbJson(raw: String, account: String, term: TermSpec): CachedSchedule? {
            val trimmed = raw.trim()
            val array = when {
                trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
                trimmed.startsWith("{") -> runCatching {
                    JSONObject(trimmed).optJSONArray("data")
                }.getOrNull()
                else -> null
            } ?: return null

            val rawBlocks = mutableListOf<CourseBlock>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = stripHtml(item.optString("kcmc")).ifBlank { continue }
                val day = item.optInt("xingqi", -1)
                if (day !in 1..7) continue
                val djc = item.optInt("djc", 1)
                val djs = item.optInt("djs", djc)
                val (start, end) = resolvePeriodSpan(djc, djs)
                val building = stripHtml(item.optString("jxlmc"))
                val room = stripHtml(item.optString("croommc"))
                val place = when {
                    building.isNotBlank() && room.isNotBlank() && !room.contains(building) -> "$building $room"
                    room.isNotBlank() -> room
                    else -> building
                }
                val teacher = stripHtml(item.optString("tmc"))
                val campus = stripHtml(item.optString("xqmc"))
                val zcRaw = stripHtml(item.optString("zc"))
                val zctype = item.optString("zctype").trim()
                val weeks = parseWeeksExpression(zcRaw, zctype)
                rawBlocks += CourseBlock(
                    name = name,
                    place = place,
                    teacher = teacher,
                    campus = campus,
                    dayOfWeek = day,
                    startPeriod = start,
                    endPeriod = end,
                    weeks = weeks,
                    weeksText = zcRaw,
                    className = stripHtml(item.optString("jxbmc")),
                )
            }

            // 合并同一课程、同教师/地点/星期/周次的相邻单节或连节记录
            data class MergeKey(
                val name: String,
                val teacher: String,
                val place: String,
                val campus: String,
                val dayOfWeek: Int,
                val weeks: List<Int>,
            )

            val merged = mutableListOf<CourseBlock>()
            rawBlocks
                .sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.name }))
                .groupBy { MergeKey(it.name, it.teacher, it.place, it.campus, it.dayOfWeek, it.weeks) }
                .values
                .forEach { group ->
                    var current = group.first()
                    for (idx in 1 until group.size) {
                        val next = group[idx]
                        if (next.startPeriod <= current.endPeriod + 1) {
                            current = current.copy(endPeriod = maxOf(current.endPeriod, next.endPeriod))
                        } else {
                            merged += current
                            current = next
                        }
                    }
                    merged += current
                }

            merged.sortWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.name }))
            return CachedSchedule(
                xnm = term.xnm,
                term = term.term,
                fetchedAt = System.currentTimeMillis(),
                student = StudentInfo(studentId = account),
                courses = merged,
            )
        }

        /** 解析超星教务成绩 JSON（同时兼容 desktop `results[]` 与 mobile `data.xscjList[]`）。 */
        fun parseChaoxingGradesJson(raw: String): List<GradeItem> {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) return emptyList()
            val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
            val array = root.optJSONArray("results")
                ?: root.optJSONObject("data")?.optJSONArray("xscjList")
                ?: return emptyList()

            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = stripHtml(obj.optString("kcmc")).ifBlank { continue }
                    val term = obj.optString("xnxq").takeUnless { it == "null" }.orEmpty().trim()
                    val score = obj.optString("zhcj").takeUnless { it.isBlank() || it == "null" }
                        ?: obj.optString("bfzzhcj").takeUnless { it == "null" }.orEmpty()
                    val sfjg = obj.optString("sfjg").takeUnless { it == "null" }.orEmpty().trim()
                    val passed = when {
                        sfjg == "0" || sfjg == "否" -> false
                        sfjg == "1" || sfjg == "是" -> true
                        score.contains("不及格") || score.contains("缺考") || score.contains("未通过") -> false
                        else -> score.toDoubleOrNull()?.let { it >= 60.0 } ?: true
                    }
                    val credits = obj.optDouble("hdxf", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                        ?: obj.optDouble("sjxf", 0.0).let { if (it.isNaN()) 0.0 else it }
                    val gpa = obj.optString("xfjd").trim().toDoubleOrNull()
                        ?: deriveGpa(score, passed)
                    val type = obj.optString("kcxz").takeUnless { it.isBlank() || it == "null" }
                        ?: obj.optString("kclx").takeUnless { it.isBlank() || it == "null" }
                        ?: obj.optString("xdxz").takeUnless { it.isBlank() || it == "null" }
                        ?: "必修"
                    val id = obj.optString("id").takeUnless { it.isBlank() || it == "null" }
                        ?: "$term|$name|$i"
                    add(
                        GradeItem(
                            id = id,
                            termLabel = term,
                            courseName = name,
                            courseType = type,
                            credits = credits,
                            scoreText = score.trim(),
                            gpa = gpa,
                            passed = passed,
                        ),
                    )
                }
            }.sortedWith(compareByDescending<GradeItem> { it.termLabel }.thenBy { it.courseName })
        }

        private fun deriveGpa(scoreText: String, passed: Boolean): Double {
            if (!passed) return 0.0
            val numeric = scoreText.toDoubleOrNull()
            return when {
                numeric != null -> if (numeric < 60.0) 0.0 else ((numeric - 50.0) / 10.0).coerceIn(1.0, 5.0)
                scoreText.contains("优") -> 4.5
                scoreText.contains("良") -> 3.5
                scoreText.contains("中") -> 2.5
                scoreText.contains("及格") -> 1.5
                else -> 0.0
            }
        }

        /** 解析超星教务考试安排 `/admin/xsd/kcapcx/ajaxListxskc` 返回 JSON。 */
        fun parseChaoxingExamsJson(raw: String): List<ExamItem> {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) return emptyList()
            val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
            val array = root.optJSONArray("results") ?: root.optJSONArray("data") ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = stripHtml(obj.optString("sxkc"))
                        .ifBlank { stripHtml(obj.optString("kcmc")) }
                        .ifBlank { continue }
                    val ksrq = stripHtml(obj.optString("ksrq"))
                    val kscc = stripHtml(obj.optString("kscc"))
                    val timeText = listOf(ksrq, kscc).filter { it.isNotBlank() }.joinToString(" ")
                    val place = stripHtml(obj.optString("kskc"))
                    val seatNo = stripHtml(obj.optString("kczwh"))
                    val batch = stripHtml(obj.optString("kspcmc"))
                    add(
                        ExamItem(
                            id = obj.optString("id").ifBlank { "$name|$timeText|$i" },
                            courseName = name,
                            timeText = timeText,
                            place = place,
                            seatNo = seatNo,
                            status = batch,
                        ),
                    )
                }
            }
        }

        fun matchBuilding(query: String, buildings: List<HmuCampusDirectory.HmuBuilding>): HmuCampusDirectory.HmuBuilding? {
            val norm = query.trim().lowercase().replace("龙子湖", "").replace("教学楼", "")
            val raw = query.trim().lowercase()
            if (raw.isBlank()) return buildings.firstOrNull()
            return buildings.firstOrNull {
                it.name.lowercase() == raw || it.code.lowercase() == raw
            } ?: buildings.firstOrNull {
                val bn = it.name.lowercase()
                bn.contains(raw) || raw.contains(bn) || (norm.isNotBlank() && bn.replace("教学楼", "").contains(norm))
            }
        }

        fun parseBuildingsJson(raw: String): List<HmuCampusDirectory.HmuBuilding> {
            val arr = extractArray(raw) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = stripHtml(obj.optString("jxlmc")).ifBlank { return@mapNotNull null }
                HmuCampusDirectory.HmuBuilding(id, obj.optString("jxlbh"), name)
            }
        }

        fun parseBuildingRoomsJson(raw: String): List<RoomRef> {
            val arr = extractArray(raw) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = stripHtml(obj.optString("rmc")).ifBlank { obj.optString("rbh") }.ifBlank { return@mapNotNull null }
                RoomRef(
                    id = id,
                    code = obj.optString("rbh"),
                    name = name,
                    buildingName = stripHtml(obj.optString("jxlmc")),
                    encodeId = obj.optString("encodeId").ifBlank { id },
                )
            }
        }

        fun parsePeriodRange(periodRange: String): Set<Int> {
            val nums = Regex("""\d+""").findAll(periodRange).mapNotNull { it.value.toIntOrNull() }.toList()
            return when {
                nums.isEmpty() -> setOf(1, 2)
                nums.size == 1 -> setOf(nums.first())
                else -> (minOf(nums[0], nums[1])..maxOf(nums[0], nums[1])).toSet()
            }
        }

        fun isRoomFreeForPeriods(roomKbRaw: String, dayOfWeek: Int, targetPeriods: Set<Int>): Boolean {
            val arr = extractArray(roomKbRaw) ?: return true
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optInt("xingqi", -1) != dayOfWeek) continue
                val djc = obj.optInt("djc", 1)
                val djs = obj.optInt("djs", djc)
                val (start, end) = resolvePeriodSpan(djc, djs)
                if ((start..end).any { it in targetPeriods }) return false
            }
            return true
        }

        private fun extractArray(raw: String): JSONArray? {
            val trimmed = raw.trim()
            return when {
                trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
                trimmed.startsWith("{") -> runCatching {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("data") ?: obj.optJSONArray("results")
                }.getOrNull()
                else -> null
            }
        }

        /**
         * 对比旧成绩与新成绩列表，为新出或分数变化的科目打上 `isNew = true` 高亮标记。
         */
        fun diffNewGrades(previous: List<GradeItem>, incoming: List<GradeItem>): List<GradeItem> {
            if (previous.isEmpty()) return incoming.map { it.copy(isNew = false) }
            val oldMap = previous.associateBy { "${it.termLabel}|${it.courseName}" }
            return incoming.map { item ->
                val prev = oldMap["${item.termLabel}|${item.courseName}"]
                val changed = prev == null || prev.scoreText != item.scoreText
                item.copy(isNew = changed)
            }
        }

        /** 按学分加权计算均分与平均绩点。 */
        fun computeGpaSummary(items: List<GradeItem>): GpaSummary {
            if (items.isEmpty()) return GpaSummary(0.0, 0.0, 0.0, 0, 0)
            var totalCredits = 0.0
            var weightedScoreSum = 0.0
            var scoreCredits = 0.0
            var weightedGpaSum = 0.0
            var gpaCredits = 0.0
            var passed = 0
            for (g in items) {
                if (g.passed) passed++
                if (g.credits > 0.0) {
                    totalCredits += g.credits
                    if (g.gpa > 0.0) {
                        weightedGpaSum += g.gpa * g.credits
                        gpaCredits += g.credits
                    }
                    val numeric = g.scoreText.toDoubleOrNull()
                    if (numeric != null) {
                        weightedScoreSum += numeric * g.credits
                        scoreCredits += g.credits
                    }
                }
            }
            val avgScore = if (scoreCredits > 0.0) weightedScoreSum / scoreCredits else 0.0
            val avgGpa = if (gpaCredits > 0.0) weightedGpaSum / gpaCredits else 0.0
            return GpaSummary(
                weightedAverage = avgScore,
                averageGpa = avgGpa,
                totalCredits = totalCredits,
                passedCount = passed,
                totalCount = items.size,
            )
        }

        /**
         * 通用教务课表 JSON 解析（兼容标准字段 courseName/kcmc, weekDay/xqj, startPeriod, endPeriod, weeks）。
         */
        fun parseScheduleJson(raw: String, account: String, term: TermSpec): CachedSchedule? {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val array = root.optJSONArray("courses")
                ?: root.optJSONArray("data")
                ?: JSONArray()
            val blocks = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("courseName")
                        .ifBlank { item.optString("course") }
                        .ifBlank { item.optString("kcmc") }
                        .ifBlank { continue }
                    val day = item.optInt("dayOfWeek", item.optInt("weekDay", item.optInt("xqj", -1)))
                    if (day !in 1..7) continue
                    val start = item.optInt("startPeriod", item.optInt("jcStart", 1)).coerceAtLeast(1)
                    val end = item.optInt("endPeriod", item.optInt("jcEnd", start)).coerceAtLeast(start)
                    val weeksArr = item.optJSONArray("weeks") ?: item.optJSONArray("weeks_arr")
                    val weeks = buildList {
                        if (weeksArr != null) {
                            for (w in 0 until weeksArr.length()) add(weeksArr.optInt(w))
                        }
                    }.filter { it > 0 }.ifEmpty { listOf(1) }
                    add(
                        CourseBlock(
                            name = name,
                            place = item.optString("place").ifBlank { item.optString("cdmc") },
                            teacher = item.optString("teacher").ifBlank { item.optString("xm") },
                            campus = item.optString("campus"),
                            dayOfWeek = day,
                            startPeriod = start,
                            endPeriod = end,
                            weeks = weeks,
                            weeksText = item.optString("weeksText").ifBlank { item.optString("zcd") },
                        ),
                    )
                }
            }
            val student = StudentInfo(
                name = root.optString("studentName").ifBlank { root.optString("username") },
                studentId = account,
                className = root.optString("className").ifBlank { root.optString("class_name") },
            )
            return CachedSchedule(
                xnm = term.xnm,
                term = term.term,
                fetchedAt = System.currentTimeMillis(),
                student = student,
                courses = blocks,
            )
        }
    }
}
