package com.fengkao.timetable.data

import android.content.Context
import com.fengkao.timetable.data.local.CourseChangeStore
import com.fengkao.timetable.data.local.ScheduleCache
import com.fengkao.timetable.data.local.SettingsStore
import com.fengkao.timetable.data.model.ChangeType
import com.fengkao.timetable.data.model.CourseChange
import com.fengkao.timetable.data.model.CachedSchedule
import com.fengkao.timetable.data.model.CourseBlock
import com.hmu.muye.data.jw.ChaoxingJwClient
import com.hmu.muye.data.jw.ChaoxingJwStore
import com.hmu.muye.data.jw.JwFetchResult
import com.hmu.muye.data.jw.JwLoginResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 课表仓库：纯 Kotlin 对接超星综合教学管理系统（hnuahe.jw.chaoxing.com），
 * 基于 WebView 登录后保存的加密 Session Cookie 拉取课表并写入本地缓存。
 */
class ScheduleRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val cache: ScheduleCache,
    private val changes: CourseChangeStore,
    private val jwStore: ChaoxingJwStore = ChaoxingJwStore(context),
    private val xuexitong: com.fengkao.timetable.data.xuexitong.XuexitongRepository? = null,
) {
    private val fetchMutex = Mutex()

    sealed interface SyncOutcome {
        data class Success(val schedule: CachedSchedule) : SyncOutcome
        data class Failure(val message: String) : SyncOutcome
    }

    /**
     * 同步课表。onLog：阶段性进度日志（供同步反馈展示）。
     */
    suspend fun sync(
        requested: TermSpec? = null,
        onLog: (String) -> Unit = {},
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val s = settings.first()
        val cookies = jwStore.cookies()
        val xx = xuexitong

        // Priority 1: 学习通同步个人课表（kb.chaoxing.com，免验证码/免 WebView）
        if (xx != null && xx.hasSession() && (requested == null || cookies.isBlank())) {
            val xxResult = fetchMutex.withLock {
                xx.fetchSchedule(onLog)
            }
            xxResult.onSuccess { payload ->
                if (requested != null && payload.term != requested) {
                    return@withContext SyncOutcome.Failure(
                        "学习通仅同步当前学期（${payload.term.label}），查询其他学期请通过超星教务网页登录"
                    )
                }
                if (payload.termStartDate != null && !s.hasTermStartFor(payload.term)) {
                    settings.setTermStartEpochDay(payload.termStartDate.toEpochDay(), payload.term)
                }
                if (s.periodTimesJson.isBlank() && payload.periodTimes.isNotEmpty()) {
                    settings.setPeriodTimes(payload.periodTimes)
                }
                return@withContext commitSchedule(payload.schedule, payload.term, onLog, fallbackMaxWeek = payload.maxWeek)
            }.onFailure { err ->
                if (cookies.isBlank()) {
                    return@withContext SyncOutcome.Failure(err.message ?: "学习通课表同步失败")
                }
                onLog("学习通课表拉取失败（${err.message ?: "网络异常"}），回退至超星教务…")
            }
        }

        val baseUrl = s.baseUrl.ifBlank { ChaoxingJwStore.DEFAULT_JW_BASE }
        val client = ChaoxingJwClient(baseUrl)
        var effectiveCookies = cookies.ifBlank {
            refreshJwSessionQuietly(client, baseUrl, onLog).orEmpty()
        }
        if (effectiveCookies.isBlank()) {
            return@withContext SyncOutcome.Failure("请先登录超星教务或学习通账号后再同步课表")
        }

        val calendarTerm = TermCalculator.autoTerm()
        val cachedTerm = cache.load()?.takeIf { it.courses.isNotEmpty() }?.let { TermSpec(it.xnm, it.term) }
        val automaticTerm = TermCalculator.automaticTarget(calendarTerm, cachedTerm)
        val target = requested ?: automaticTerm.also {
            onLog("当前目标为 ${it.xnm}-${it.xnm + 1} 学年第 ${it.term} 学期")
        }

        onLog("连接超星教务（${baseUrl.removePrefix("https://")}）…")

        var result = fetchMutex.withLock {
            client.fetchSchedule(effectiveCookies, s.account, target)
        }
        if (result is JwFetchResult.NeedWebLogin) {
            val renewed = refreshJwSessionQuietly(client, baseUrl, onLog)
            if (!renewed.isNullOrBlank()) {
                effectiveCookies = renewed
                result = fetchMutex.withLock {
                    client.fetchSchedule(effectiveCookies, s.account, target)
                }
            }
        }
        when (result) {
            is JwFetchResult.Success -> {
                if (!s.hasTermStartFor(target)) {
                    val xnxq = "${target.xnm}-${target.xnm + 1}-${target.term}"
                    val termStart = client.fetchTermStartDate(effectiveCookies, xnxq)
                    if (termStart != null) {
                        settings.setTermStartEpochDay(termStart.toEpochDay(), target)
                    }
                }
                commitSchedule(result.data, target, onLog)
            }
            is JwFetchResult.NeedWebLogin -> {
                onLog(result.reason)
                SyncOutcome.Failure(result.reason)
            }
            is JwFetchResult.PendingPhase2Route -> {
                onLog(result.message)
                SyncOutcome.Failure(result.message)
            }
            is JwFetchResult.Error -> {
                onLog(result.message)
                SyncOutcome.Failure(result.message)
            }
        }
    }

    private fun refreshJwSessionQuietly(
        client: ChaoxingJwClient,
        baseUrl: String,
        onLog: (String) -> Unit,
    ): String? {
        val user = jwStore.username()
        val pass = jwStore.password()
        if (user.isBlank() || pass.isBlank()) return null
        onLog("教务会话已过期，尝试自动重新登录…")
        return when (val login = client.loginWithPassword(user, pass)) {
            is JwLoginResult.Success -> {
                jwStore.saveSession(login.cookies, baseUrl)
                login.cookies
            }
            else -> null
        }
    }

    private suspend fun commitSchedule(
        schedule: CachedSchedule,
        candidate: TermSpec,
        onLog: (String) -> Unit,
        fallbackMaxWeek: Int? = null,
    ): SyncOutcome {
        if (schedule.courses.isEmpty()) {
            return SyncOutcome.Failure("该学期暂无课表数据，已保留上次成功获取的课程")
        }
        onLog("检测课程异动并写入缓存…")
        val now = System.currentTimeMillis()
        val oldCached = cache.load(candidate)
        val detected = if (oldCached != null &&
            oldCached.xnm == schedule.xnm && oldCached.term == schedule.term &&
            oldCached.courses.isNotEmpty()
        ) {
            diffSchedule(oldCached, schedule, now)
        } else {
            emptyList()
        }
        val isLatest = cache.save(schedule)
        if (isLatest) {
            val s = settings.first()
            val inferredWeeks = TermCalculator.inferTermWeeks(schedule.courses, fallbackMaxWeek)
            val termChanged = s.lastXnm > 0 && (s.lastXnm != candidate.xnm || s.lastTerm != candidate.term)
            if (inferredWeeks != null && (!s.termWeeksManual || termChanged) &&
                (inferredWeeks != s.termWeeks || s.termWeeksManual)
            ) {
                settings.setTermWeeks(inferredWeeks, manual = false)
            }
            changes.save(detected)
            settings.setLastSync(candidate.xnm, candidate.term, now)
        }
        onLog("完成：共 ${schedule.courses.size} 门课程")
        return SyncOutcome.Success(schedule)
    }

    // ------------------------------------------------------------ 课程异动对比
    private fun diffSchedule(old: CachedSchedule, new: CachedSchedule, now: Long): List<CourseChange> {
        fun key(c: CourseBlock) = Triple(c.dayOfWeek, c.startPeriod, c.endPeriod)
        val oldByName = old.courses.groupBy { it.name }
        val newByName = new.courses.groupBy { it.name }
        val out = mutableListOf<CourseChange>()
        fun dowName(d: Int) = "周${"一二三四五六日"[d - 1]}"
        fun CourseBlock.slotDesc() = "${dowName(dayOfWeek)} $startPeriod-${endPeriod}节 · ${place.ifBlank { "待定" }}"

        for ((name, newList) in newByName) {
            val oldList = oldByName[name]
            when {
                oldList == null -> out += CourseChange(
                    type = ChangeType.ADDED, course = name,
                    detail = "新增 ${newList.minByOrNull { it.startPeriod }!!.slotDesc()}",
                    detectedAt = now,
                )
                oldList.map { key(it) to it.place }.toSet() !=
                    newList.map { key(it) to it.place }.toSet() -> {
                    val o = oldList.minByOrNull { it.startPeriod }!!
                    val n = newList.minByOrNull { it.startPeriod }!!
                    out += CourseChange(
                        type = ChangeType.CHANGED, course = name,
                        detail = "${o.slotDesc()} → ${n.slotDesc()}",
                        detectedAt = now,
                    )
                }
            }
        }
        for ((name, oldList) in oldByName) {
            if (name !in newByName) {
                out += CourseChange(
                    type = ChangeType.REMOVED, course = name,
                    detail = "取消 ${oldList.minByOrNull { it.startPeriod }!!.slotDesc()}",
                    detectedAt = now,
                )
            }
        }
        // 排序：调整 > 新增 > 取消，同类按课名
        val order = mapOf(ChangeType.CHANGED to 0, ChangeType.ADDED to 1, ChangeType.REMOVED to 2)
        return out.sortedWith(compareBy({ order[it.type] ?: 3 }, { it.course }))
    }

    /** "1-2" → (1,2)；"3" → (3,3)；"1-2,5-6" 取第一段 */
    private fun parseSection(section: String): Pair<Int, Int> {
        val first = section.split(",", "，").firstOrNull { it.isNotBlank() } ?: "1"
        val nums = Regex("""\d+""").findAll(first).toList()
        return when {
            nums.isEmpty() -> 1 to 1
            nums.size == 1 -> {
                val v = nums[0].value.toIntOrNull() ?: 1
                v to v
            }
            else -> {
                val a = nums[0].value.toIntOrNull() ?: 1
                val b = nums[1].value.toIntOrNull() ?: a
                minOf(a, b) to maxOf(a, b)
            }
        }
    }
}
