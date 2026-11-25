package com.fengkao.timetable.data.xuexitong

import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.data.TermSpec
import com.fengkao.timetable.data.model.CachedSchedule
import com.fengkao.timetable.data.model.CourseBlock
import com.fengkao.timetable.data.model.PeriodTime
import com.fengkao.timetable.data.model.StudentInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeSet

data class XuexitongSchedulePayload(
    val term: TermSpec,
    val schedule: CachedSchedule,
    val termStartDate: LocalDate?,
    val maxWeek: Int,
    val periodTimes: List<PeriodTime>,
)

internal object XuexitongScheduleParser {
    private val BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val YEAR_REGEX = Regex("""\b(20\d{2})\b""")
    private val LESSON_TIME_RANGE = Regex("""^\s*(\d{1,2}):(\d{2})\s*[-~—至到]\s*(\d{1,2}):(\d{2})\s*$""")

    internal data class RawKbLesson(
        val lessonId: String,
        val dayOfWeek: Int,
        val beginNumber: Int,
        val length: Int,
        val location: String,
        val teacherName: String,
        val name: String,
    )

    internal data class KbPageResult(
        val schoolYear: String,
        val semesterRaw: String,
        val maxWeek: Int,
        val firstWeekDateMs: Long,
        val userName: String,
        val periodTimes: List<PeriodTime>,
        val lessons: List<RawKbLesson>,
    )

    private data class LessonAggregationKey(
        val lessonId: String,
        val dayOfWeek: Int,
        val beginNumber: Int,
        val length: Int,
        val location: String,
        val teacherName: String,
        val name: String,
    )

    fun parseKbResponse(body: String): KbPageResult {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            throw IOException("学习通课表返回空响应")
        }
        if (trimmed.startsWith("<")) {
            if (trimmed.contains("登录") || trimmed.contains("passport2", ignoreCase = true)) {
                throw XuexitongSessionExpired("学习通登录已失效，请重新登录")
            }
            throw IOException("学习通课表返回异常页面")
        }
        val root = runCatching { JSONObject(trimmed) }.getOrElse {
            throw IOException("学习通课表响应解析失败")
        }
        val result = root.optInt("result", -1)
        if (result != 1) {
            val msg = cleanText(root.optString("msg"))
            if (isSessionExpiredMessage(msg)) {
                throw XuexitongSessionExpired(msg.ifBlank { "学习通登录已失效，请重新登录" })
            }
            throw IOException(msg.ifBlank { "学习通课表返回异常（result=$result）" })
        }

        val data = root.optJSONObject("data") ?: throw IOException("学习通课表缺少 data 字段")
        val curriculum = data.optJSONObject("curriculum") ?: JSONObject()
        val schoolYear = cleanText(curriculum.optString("schoolYear"))
        val semesterRaw = cleanText(curriculum.opt("semester")?.toString().orEmpty())
        val maxWeek = curriculum.optInt("maxWeek", 20).let { if (it <= 0) 20 else it }
        val firstWeekDateMs = curriculum.optLong("firstWeekDate", 0L)
        val userName = cleanText(curriculum.optString("userName"))
        val periodTimes = parseLessonTimes(curriculum.optJSONArray("lessonTimeConfigArray"))

        val lessonArr = data.optJSONArray("lessonArray") ?: JSONArray()
        val lessons = buildList {
            for (i in 0 until lessonArr.length()) {
                val item = lessonArr.optJSONObject(i) ?: continue
                val name = cleanText(item.optString("name"))
                val day = item.optInt("dayOfWeek", 0)
                val begin = item.optInt("beginNumber", 0)
                val length = item.optInt("length", 1).coerceAtLeast(1)
                if (name.isBlank() || day !in 1..7 || begin < 1) continue
                add(
                    RawKbLesson(
                        lessonId = cleanText(item.opt("lessonId")?.toString().orEmpty()),
                        dayOfWeek = day,
                        beginNumber = begin,
                        length = length,
                        location = cleanText(item.optString("location")),
                        teacherName = cleanText(item.optString("teacherName")),
                        name = name,
                    )
                )
            }
        }

        return KbPageResult(
            schoolYear = schoolYear,
            semesterRaw = semesterRaw,
            maxWeek = maxWeek,
            firstWeekDateMs = firstWeekDateMs,
            userName = userName,
            periodTimes = periodTimes,
            lessons = lessons,
        )
    }

    fun buildPayload(
        account: String,
        firstPage: KbPageResult,
        weeklyLessons: List<Pair<Int, List<RawKbLesson>>>,
        nowMillis: Long = System.currentTimeMillis(),
        today: LocalDate = TermCalculator.today(),
    ): XuexitongSchedulePayload {
        val grouped = LinkedHashMap<LessonAggregationKey, TreeSet<Int>>()
        for ((week, lessons) in weeklyLessons) {
            if (week <= 0) continue
            for (lesson in lessons) {
                val key = LessonAggregationKey(
                    lessonId = lesson.lessonId,
                    dayOfWeek = lesson.dayOfWeek,
                    beginNumber = lesson.beginNumber,
                    length = lesson.length,
                    location = lesson.location,
                    teacherName = lesson.teacherName,
                    name = lesson.name,
                )
                grouped.getOrPut(key) { TreeSet() }.add(week)
            }
        }

        val blocks = grouped.entries.map { (key, weeksSet) ->
            val weeksList = weeksSet.toList()
            CourseBlock(
                name = key.name,
                place = key.location,
                teacher = key.teacherName,
                campus = "",
                dayOfWeek = key.dayOfWeek,
                startPeriod = key.beginNumber,
                endPeriod = key.beginNumber + key.length - 1,
                weeks = weeksList,
                weeksText = compressWeeks(weeksList),
            )
        }.sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.name }))

        val term = parseTermSpec(firstPage.schoolYear, firstPage.semesterRaw, today)
        val termStart = parseFirstWeekDate(firstPage.firstWeekDateMs)
        val schedule = CachedSchedule(
            xnm = term.xnm,
            term = term.term,
            fetchedAt = nowMillis,
            student = StudentInfo(
                name = firstPage.userName,
                studentId = account,
            ),
            courses = blocks,
        )
        return XuexitongSchedulePayload(
            term = term,
            schedule = schedule,
            termStartDate = termStart,
            maxWeek = firstPage.maxWeek.coerceIn(1, XuexitongApi.MAX_KB_WEEKS),
            periodTimes = firstPage.periodTimes,
        )
    }

    fun parseTermSpec(
        schoolYear: String,
        semesterRaw: String,
        today: LocalDate = TermCalculator.today(),
    ): TermSpec {
        val fallback = TermCalculator.autoTerm(today)
        val xnm = YEAR_REGEX.find(schoolYear)?.groupValues?.get(1)?.toIntOrNull() ?: fallback.xnm
        val term = semesterRaw.toIntOrNull()?.takeIf { it in 1..3 } ?: when {
            "秋" in semesterRaw || "上" in semesterRaw || "一" in semesterRaw -> 1
            "春" in semesterRaw || "下" in semesterRaw || "二" in semesterRaw -> 2
            "夏" in semesterRaw || "暑" in semesterRaw || "三" in semesterRaw -> 3
            else -> fallback.term
        }
        return TermSpec(xnm = xnm, term = term)
    }

    fun parseFirstWeekDate(firstWeekDateMs: Long): LocalDate? {
        if (firstWeekDateMs <= 0L) return null
        return runCatching {
            Instant.ofEpochMilli(firstWeekDateMs).atZone(BEIJING_ZONE).toLocalDate()
        }.getOrNull()
    }

    fun parseLessonTimes(array: JSONArray?): List<PeriodTime> {
        if (array == null || array.length() == 0) return emptyList()
        val result = mutableListOf<PeriodTime>()
        for (i in 0 until array.length()) {
            val raw = cleanText(array.optString(i))
            if (raw.isBlank()) continue
            val m = LESSON_TIME_RANGE.matchEntire(raw) ?: continue
            val sh = m.groupValues[1].toIntOrNull() ?: continue
            val sm = m.groupValues[2].toIntOrNull() ?: continue
            val eh = m.groupValues[3].toIntOrNull() ?: continue
            val em = m.groupValues[4].toIntOrNull() ?: continue
            if (sh !in 0..23 || sm !in 0..59 || eh !in 0..23 || em !in 0..59) continue
            result += PeriodTime(
                period = i + 1,
                startHour = sh,
                startMinute = sm,
                endHour = eh,
                endMinute = em,
            )
        }
        return result
    }

    fun compressWeeks(weeks: Collection<Int>): String {
        val sorted = weeks.filter { it > 0 }.toSortedSet().toList()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = sorted[0]
        for (i in 1 until sorted.size) {
            val w = sorted[i]
            if (w == prev + 1) {
                prev = w
            } else {
                parts += if (start == prev) "$start" else "$start-$prev"
                start = w
                prev = w
            }
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        return parts.joinToString(",") + "周"
    }

    private fun isSessionExpiredMessage(msg: String): Boolean {
        if (msg.isBlank()) return false
        return msg.contains("登录") ||
            msg.contains("失效") ||
            msg.contains("过期") ||
            msg.contains("cookie", ignoreCase = true) ||
            msg.contains("auth", ignoreCase = true)
    }

    private fun cleanText(raw: String): String =
        raw.trim().let { if (it == "null") "" else it }
}
