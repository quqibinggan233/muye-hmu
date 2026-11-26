package com.fengkao.timetable.ui.xuexitong

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fengkao.timetable.data.model.CourseBlock
import com.fengkao.timetable.data.xuexitong.ChangeKind
import com.fengkao.timetable.data.xuexitong.NoticeFormat
import com.fengkao.timetable.data.xuexitong.ScheduleChange
import com.fengkao.timetable.data.xuexitong.TaskItem
import com.fengkao.timetable.data.xuexitong.TaskKind
import com.fengkao.timetable.data.xuexitong.TaskStatus
import com.fengkao.timetable.data.xuexitong.XuexitongNotice
import com.fengkao.timetable.ui.glass.liquidPress
import com.fengkao.timetable.ui.theme.IsDark

/**
 * 待办卡：一张卡 = 同一份作业/考试的所有提醒（可能 1~3 条通知）。
 *
 * 对照稿的三行结构，一屏能塞下 2.5 张：
 *  ① 课程色圆点 + 课程名（左）｜ 倒计时高亮胶囊（右）
 *  ② 16sp 粗体标题
 *  ③ 截止时间（左）｜ 「标记完成」唯一操作（右）
 */
@Composable
fun TaskCard(
    task: TaskItem,
    courses: List<CourseBlock>,
    now: Long,
    onOpenChaoxing: () -> Unit,
    done: Boolean = false,
    onToggleDone: () -> Unit = {},
    onClick: () -> Unit = {},
    onOpenTarget: (() -> Unit)? = null,
) {
    val visual = remember(task.courseName, courses) {
        courseVisual(NoticeFormat.shortCourse(task.courseName).ifBlank { task.title })
    }
    val closed = task.status == TaskStatus.CLOSED
    val urgent = task.status == TaskStatus.URGENT && !done && !closed
    val countdown = TaskFormat.countdownText(task, now)
    val hasCountdown = !done && !closed && countdown.isNotBlank()
    val isEval = task.kind == TaskKind.EVALUATION
    val primaryHeadline = if (isEval && visual.courseName.isNotBlank()) {
        visual.courseName
    } else {
        task.title.ifBlank { "学习通任务" }
    }
    val evalSubtitle = if (isEval) task.title.ifBlank { "教学评价表" } else null

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (done) NoticeCapsule else NoticeCard)
            .border(
                1.dp,
                if (done) Color.Transparent else if (IsDark) NoticeHairline else Color.White,
                RoundedCornerShape(20.dp),
            )
            .liquidPress(pressedScale = 0.99f, onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            // ── ① 课程归属 ＋ 类型标签 ＋ 倒计时
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (done || closed) NoticeFaint else visual.base)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    if (isEval) "课堂教学评价" else visual.courseName.ifBlank { "学习通" },
                    color = if (done) NoticeMeta else NoticeSub,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!done && !closed && (task.kind == TaskKind.EXAM || isEval)) {
                    Spacer(Modifier.width(6.dp))
                    val tagFg = if (isEval) {
                        if (IsDark) Color(0xFFC4B5FD) else Color(0xFF6D28D9)
                    } else {
                        NoticeBlue
                    }
                    val tagBg = if (isEval) {
                        if (IsDark) Color(0xFF2B2142) else Color(0xFFF3EEFF)
                    } else {
                        NoticeBlueSoft
                    }
                    Text(
                        if (isEval) "评价" else "考试",
                        color = tagFg,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(tagBg)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                when {
                    done -> Unit
                    closed -> StatusTag("已结束", NoticeMeta, NoticeCapsule)
                    hasCountdown -> StatusTag(
                        if (urgent) "⏰ $countdown" else countdown,
                        if (urgent) Color(0xFFE94814) else NoticeSub,
                        if (urgent) Color(0xFFFFF1EC) else NoticeCapsule,
                        borderColor = if (urgent) Color(0x33E94814) else null,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── ② 标题（评价类突出课程名，副标题写明具体评价表名称）
            Text(
                primaryHeadline,
                color = if (done) NoticeFaint else NoticeInk,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!evalSubtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    evalSubtitle,
                    color = NoticeMeta,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── ③ 截止时间（左）｜ 直达 + 标记完成（右）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TaskFormat.deadlineText(task),
                    color = NoticeFaint,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onOpenTarget != null && task.latest.attachmentUrl.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "直达 ↗",
                        color = NoticeBlue,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(NoticeCapsule)
                            .liquidPress(pressedScale = 0.92f, onClick = onOpenTarget)
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                DoneToggle(done, onToggleDone)
            }
        }
    }
}

@Composable
fun TaskDetailDialog(
    task: TaskItem,
    done: Boolean,
    onDismiss: () -> Unit,
    onToggleDone: () -> Unit,
    onOpenDirect: () -> Unit,
    onOpenChaoxing: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(NoticeCard)
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    task.kind.label,
                    color = NoticeBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(NoticeBlueSoft)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
                Text(
                    task.courseName.ifBlank { "课程任务" },
                    color = NoticeSub,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                task.title.ifBlank { "任务详情" },
                color = NoticeInk,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            val metaParts = buildList {
                if (task.deadlineMillis != null) add("截止：${TaskFormat.deadlineText(task)}")
                else if (!task.latest.deadline.isNullOrBlank()) add("截止：${task.latest.deadline}")
                if (task.reminderCount > 1) add("合并 ${task.reminderCount} 条提醒")
            }
            if (metaParts.isNotEmpty()) {
                Text(metaParts.joinToString(" · "), color = NoticeMeta, fontSize = 11.5.sp)
            }
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    NoticeFormat.cleanContent(
                        task.latest.content,
                        task.latest.title,
                        task.latest.attachmentName,
                    ).ifBlank { task.latest.content.ifBlank { task.latest.title } },
                    color = NoticeSub,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (done) "标为未完成" else "标记完成",
                    color = NoticeSub,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NoticeCapsule)
                        .liquidPress(pressedScale = 0.94f, onClick = onToggleDone)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "去学习通",
                    color = NoticeSub,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NoticeCapsule)
                        .liquidPress(pressedScale = 0.94f, onClick = onOpenChaoxing)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                Text(
                    "直达原页面 ↗",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NoticeBlue)
                        .liquidPress(pressedScale = 0.94f, onClick = onOpenDirect)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**
 *「标记完成 / 已完成」胶囊：替代原来标题左侧的圆形勾选框。
 *
 * 勾选框会给每张卡的标题前面留 32dp 空白，作业名和时间就不在一条左边线上；
 * 改成底部行尾的胶囊后，左边缘干净，右边缘也稳定。
 */
@Composable
private fun DoneToggle(done: Boolean, onToggle: () -> Unit) {
    // 外观与「去提交 ↗」完全同一套（浅蓝底 + 蓝粗体），否则灰底灰字看起来像标签、
    // 不像按钮，用户不会去点它。
    Text(
        if (done) "✓ 已完成" else "标记完成",
        color = if (done) Color.White else Color(0xFF1560BD),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (done) NoticeBlue else Color(0xFFE5F2FF))
            .liquidPress(pressedScale = 0.92f, onClick = onToggle)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    )
}

/** 右上角状态胶囊：11sp 粗体，浅底细边，和对照稿的 rounded-full 一致。 */
@Composable
private fun StatusTag(
    text: String,
    fg: Color,
    bg: Color,
    borderColor: Color? = null,
) {
    Text(
        text,
        color = fg,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, RoundedCornerShape(50))
                else Modifier
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/**
 * 课表异动卡。
 *
 * 左下「查看原文」就地弹出原文面板（不再把用户扔进浏览器），右下「改课表」
 * 固定为主操作。是否真的能改，仍由 `change.applicable` 决定：不能改时点「改课表」
 * 会走 onApply 里的原文回退，但按钮位置与外观不再随之漂移。
 *
 * 对照稿把变化本身画成一块「原定 → 新安排」的对比条，而不是让用户去读句子。
 */
@Composable
fun ChangeCard(
    change: ScheduleChange,
    onApply: () -> Unit,
    onOpenChaoxing: () -> Unit,
    applied: Boolean = false,
) {
    var showRaw by remember(change.noticeId) { mutableStateOf(false) }
    val visual = remember(change.courseName) {
        courseVisual(
            NoticeFormat.shortCourse(change.courseName).ifBlank {
                change.courseName.ifBlank { "课程" }
            }
        )
    }
    val (badgeText, accent) = when (change.kind) {
        ChangeKind.MOVE -> "调课" to NoticeBlue
        ChangeKind.SWAP -> "互调" to Color(0xFFB7791F)
        ChangeKind.CANCEL -> "停课" to NoticeDangerFg
        ChangeKind.SYNC_HINT -> "待确认" to NoticeMeta
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NoticeCard)
            .border(1.dp, if (IsDark) NoticeHairline else Color.White, RoundedCornerShape(20.dp))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            // ── ① 课程色圆点 + 课程名 + 异动类型（左）｜ 发布日期（右）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(visual.base)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    visual.courseName,
                    color = NoticeSub,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    badgeText,
                    color = accent,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accent.copy(alpha = 0.10f))
                        .border(1.dp, accent.copy(alpha = 0.20f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    NoticeFormat.relativeTime(change.notice.timeMillis),
                    color = NoticeFaint,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            Spacer(Modifier.height(6.dp))

            // ── ② 大标题
            Text(
                changeHeadline(change),
                color = NoticeInk,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // ── ③ 变更对比块 / 待确认横幅
            Spacer(Modifier.height(10.dp))
            when (change.kind) {
                ChangeKind.MOVE -> CompareBlock(
                    oldLabel = "原定安排",
                    oldTime = change.oldTimeText(),
                    newLabel = "新时间与地点",
                    newTime = change.newTimeText(),
                    newPlace = change.place.ifBlank { "见原文" },
                )
                // 互调没有「原定 → 新时间」这一层：新时间还没定，只把受影响的原场次和结论说清
                ChangeKind.SWAP -> CompareBlock(
                    oldLabel = "本场次",
                    oldTime = change.oldTimeText(),
                    newLabel = "处理方式",
                    newTime = "与另一门课互调",
                    newPlace = "需手动确认",
                )
                ChangeKind.CANCEL -> CancelBlock(change)
                ChangeKind.SYNC_HINT -> Text(
                    "⚠︎ ${change.excerpt.ifBlank { "原文未给出具体时间，需到教务系统确认" }}",
                    color = Color(0xFF92400E),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFEF6E7))
                        .border(1.dp, Color(0x33B7791F), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── ④ 查看原文（左）｜ 改课表（右，始终是主操作）
            // 「看原文」以前直接跳浏览器，把用户扔出 App；现在是就地弹一层面板，
            // 用户的上下文不丢，读完关掉还在这一页。右下的「改课表」固定不变，
            // 不给它随数据在两种文案/两种颜色间漂移。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "查看原文",
                    color = NoticeBlue,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NoticeBlueSoft)
                        .liquidPress(pressedScale = 0.94f, onClick = { showRaw = true })
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (applied) "✓ 已写入课表" else "改课表",
                    color = if (applied) Color(0xFF047857) else Color.White,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (applied) Color(0xFFECFDF5) else NoticeBlue)
                        .then(
                            if (applied) Modifier
                            else Modifier.liquidPress(pressedScale = 0.94f, onClick = onApply)
                        )
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (showRaw) {
        NoticeRawDialog(
            notice = change.notice,
            onDismiss = { showRaw = false },
            onOpenChaoxing = {
                showRaw = false
                onOpenChaoxing()
            },
        )
    }
}

/**
 * 原文面板：把学习通抓到的通知正文原样摊在用户面前。
 *
 * 用 Dialog 而不是再叠一层 BottomSheet——异动页本身已经是 ModalBottomSheet，
 * 两层 sheet 叠在一起在部分机型上会抢 drag handle。
 */
@Composable
private fun NoticeRawDialog(
    notice: XuexitongNotice,
    onDismiss: () -> Unit,
    onOpenChaoxing: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White)
                .padding(18.dp),
        ) {
            Text(
                NoticeFormat.cleanTitle(notice.title, notice.category).ifBlank { "通知原文" },
                color = NoticeInk,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${notice.sender} · ${NoticeFormat.relativeTime(notice.timeMillis)}",
                color = NoticeMeta,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            // 原文可选中复制：用户常常要拿作业要求去别处粘贴
            SelectionContainer {
                Text(
                    NoticeFormat.cleanContent(notice.content, notice.title).ifBlank { "（原文为空）" },
                    color = NoticeSub,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "去学习通",
                    color = NoticeSub,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NoticeCapsule)
                        .liquidPress(pressedScale = 0.94f, onClick = onOpenChaoxing)
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
                Text(
                    "关闭",
                    color = NoticeBlue,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NoticeBlueSoft)
                        .liquidPress(pressedScale = 0.94f, onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**「原定 → 新安排」并排对比块：左半边划线置灰，右半边高亮。 */
@Composable
private fun CompareBlock(    oldLabel: String,
    oldTime: String,
    newLabel: String,
    newTime: String,
    newPlace: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (IsDark) NoticeCapsule else Color(0xFFF5F7FA))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                oldLabel,
                color = NoticeFaint,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                oldTime.ifBlank { "未给出" },
                color = NoticeFaint,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = TextDecoration.LineThrough,
            )
        }
        Box(
            Modifier
                .padding(horizontal = 6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (IsDark) NoticeCard else Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text("➔", color = NoticeFaint, fontSize = 11.sp, lineHeight = 13.sp)
        }
        Column(
            Modifier
                .weight(1.45f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (IsDark) Color(0xFF16324F) else Color(0xFFEFF6FF))
                .border(1.dp, NoticeBlue.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Text(
                newLabel,
                color = NoticeBlue,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                newTime.ifBlank { "见原文" },
                color = NoticeInk,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                newPlace,
                color = if (IsDark) NoticeBlueSoft else Color(0xFF1D4ED8),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 停课：受影响场次划线 → 右侧「暂停/取消」胶囊。 */
@Composable
private fun CancelBlock(change: ScheduleChange) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (IsDark) NoticeCapsule else Color(0xFFF5F7FA))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "受影响场次",
                color = NoticeFaint,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(change.oldTimeText().ifBlank { "本周" })
                    if (change.place.isNotBlank()) append(" · ${change.place}")
                },
                color = NoticeSub,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = TextDecoration.LineThrough,
            )
        }
        Text("➔", color = NoticeFaint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            "暂停一次",
            color = NoticeDangerFg,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(NoticeDangerBg)
                .border(1.dp, NoticeDangerFg.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 课程视觉：圆点用原始课程色，胶囊用同色降饱和底 + 加深文字。 */
private fun courseVisual(name: String): NoticeCourseVisual {
    val base = courseBaseColor(name)
    return NoticeCourseVisual(
        courseName = name,
        base = base,
        pillBg = lerp(base, if (IsDark) Color.Black else Color.White, 0.55f),
        pillText = lerp(base, if (IsDark) Color.White else Color.Black, 0.48f),
    )
}

/**「改到 3-5节 · 9月23日 周三」这类一句话标题，让用户一眼看出改成什么。 */
private fun changeHeadline(change: ScheduleChange): String = when (change.kind) {
    ChangeKind.MOVE -> {
        val date = TaskFormat.dateText(change.toDate)
        val period = TaskFormat.periodText(change.toStartPeriod, change.toEndPeriod)
        listOf("改到", date, period).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "上课时间有调整" }
    }
    ChangeKind.SWAP -> "《${change.courseName.ifBlank { "课程" }}》与另一门课互调"
    ChangeKind.CANCEL -> "本次课程停课"
    ChangeKind.SYNC_HINT -> "课程时间有调整，需到教务系统查看"
}

/** 原定节次：只报「第 X-Y 节」，日期交给标题，避免同一行说两遍。 */
private fun ScheduleChange.oldTimeText(): String {
    val period = TaskFormat.periodText(fromStartPeriod, fromEndPeriod)
    if (period.isNotBlank()) return period
    // 原文没写原定节次时，退回「周三上午」这种粒度，别把「未给出」摆到对比块里当信息
    return excerpt.lineSequence()
        .firstOrNull { it.contains("周") || it.contains("上午") || it.contains("下午") }
        ?.take(14)?.trim().orEmpty()
}

private fun ScheduleChange.newTimeText(): String {
    val date = TaskFormat.dateText(toDate)
    val period = TaskFormat.periodText(toStartPeriod, toEndPeriod)
    return listOf(date, period).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "见原文" }
}
