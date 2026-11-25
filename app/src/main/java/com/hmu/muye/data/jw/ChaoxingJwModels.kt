package com.hmu.muye.data.jw

import kotlinx.serialization.Serializable

/** 单科成绩条目 */
@Serializable
data class GradeItem(
    val id: String,
    val termLabel: String,
    val courseName: String,
    val courseType: String = "必修",
    val credits: Double = 0.0,
    val scoreText: String = "",
    val gpa: Double = 0.0,
    val passed: Boolean = true,
    val isNew: Boolean = false,
)

/** 成绩汇总统计 */
data class GpaSummary(
    val weightedAverage: Double,
    val averageGpa: Double,
    val totalCredits: Double,
    val passedCount: Int,
    val totalCount: Int,
)

/** 考试安排条目 */
@Serializable
data class ExamItem(
    val id: String,
    val courseName: String,
    val timeText: String,
    val place: String,
    val seatNo: String = "",
    val status: String = "",
)

/** 用户本地考试便签 */
@Serializable
data class ExamNote(
    val id: String,
    val content: String,
    val createdAt: Long,
)

/** 空教室筛选结果条目 */
@Serializable
data class EmptyRoomSlot(
    val building: String,
    val roomName: String,
    val capacity: Int = 0,
    val freePeriods: String = "",
)
