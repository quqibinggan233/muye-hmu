package com.fengkao.timetable.ui.xuexitong

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fengkao.timetable.data.model.CourseBlock
import com.fengkao.timetable.data.xuexitong.NoticeCategory
import com.fengkao.timetable.data.xuexitong.NoticeExtract
import com.fengkao.timetable.data.xuexitong.NoticeFormat
import com.fengkao.timetable.data.xuexitong.ScheduleChange
import com.fengkao.timetable.data.xuexitong.TaskItem
import com.fengkao.timetable.data.xuexitong.TaskKind
import com.fengkao.timetable.ui.glass.liquidPress
import com.fengkao.timetable.data.xuexitong.TaskStatus
import com.fengkao.timetable.data.xuexitong.XuexitongNotice
import com.fengkao.timetable.ui.theme.BrandGreen

private enum class NoticeFilter(val label: String) {
    ALL("全部"),
    UNREAD("未读"),
    HOMEWORK("作业/测验"),
    EVALUATION("教学评价"),
    SIGNIN("签到"),
    ANNOUNCEMENT("课程公告"),
    ARCHIVED("归档"),
}

private enum class TodoFilter(val label: String) {
    ALL("全部"),
    STUDY("作业/考试"),
    EVALUATION("教学评价"),
    DONE("已完成"),
}

private enum class SheetTab(val label: String) {
    TODO("待办"),
    RAW("通知"),
    CHANGE("课表异动"),
}

/** 关键字匹配：课程名、标题、正文、发送者、附件名任一命中即可（不区分大小写）。 */
private fun XuexitongNotice.matches(keyword: String): Boolean {
    val needle = keyword.removeWhitespace().lowercase()
    if (needle.isEmpty()) return true
    val haystack = listOf(title, content, sender, attachmentName, deadline.orEmpty())
        .joinToString("\n")
        .removeWhitespace()
        .lowercase()
    return haystack.contains(needle)
}

private fun String.removeWhitespace(): String = filterNot { it.isWhitespace() }

/** 待办的关键字匹配：课程名 + 作业名 + 最新一条通知的标题。 */
private fun TaskItem.matches(keyword: String): Boolean {
    val needle = keyword.removeWhitespace().lowercase()
    if (needle.isEmpty()) return true
    return listOf(courseName, title, latest.title, latest.content)
        .joinToString("\n")
        .removeWhitespace()
        .lowercase()
        .contains(needle)
}

/** 课表异动的关键字匹配：课程名 + 原文摘要 + 地点。 */
private fun ScheduleChange.matches(keyword: String): Boolean {
    val needle = keyword.removeWhitespace().lowercase()
    if (needle.isEmpty()) return true
    return listOf(courseName, excerpt, place, notice.title)
        .joinToString("\n")
        .removeWhitespace()
        .lowercase()
        .contains(needle)
}

/**
 * 通知中心半屏抽屉。
 *
 * 学习通给的是一串「通知事件」，用户要的是「还有哪几件事要办」，所以这里分三层：
 * 默认停在「待办」（同一作业的 3 条提醒合并成 1 张卡），「课表异动」看调课停课，
 * 「通知原文」保留逐条流水的原始视图——不丢信息，只是默认折起来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeCenterSheet(
    notices: List<XuexitongNotice>,
    locallyReadIds: Set<String>,
    archivedIds: Set<String>,
    doneTaskKeys: Set<String>,
    unreadCount: Int,
    lastFetchAt: Long,
    loggedIn: Boolean,
    sessionExpired: Boolean,
    busy: Boolean,
    courses: List<CourseBlock>,
    now: Long,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit,
    onMarkRead: (String) -> Unit,
    onArchive: (String) -> Unit,
    onUnarchive: (String) -> Unit,
    onArchiveRead: () -> Unit,
    onTaskDone: (String, Boolean) -> Unit,
    onConnect: () -> Unit,
    onOpenChaoxing: (XuexitongLaunchTarget) -> Unit,
    onOpenUrl: (title: String, url: String) -> Unit,
    onApplyChange: (ScheduleChange) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NoticeBg,
        shape = NoticeSheetShape,
        scrimColor = Color(0x591A202C),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(NoticeFaint.copy(alpha = 0.45f))
                )
            }
        },
    ) {
        NoticeCenterPanel(
            notices = notices,
            locallyReadIds = locallyReadIds,
            archivedIds = archivedIds,
            doneTaskKeys = doneTaskKeys,
            unreadCount = unreadCount,
            lastFetchAt = lastFetchAt,
            loggedIn = loggedIn,
            sessionExpired = sessionExpired,
            busy = busy,
            courses = courses,
            now = now,
            embedded = false,
            contentBottomPadding = 24.dp,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.84f),
            onRefresh = onRefresh,
            onMarkAllRead = onMarkAllRead,
            onMarkRead = onMarkRead,
            onArchive = onArchive,
            onUnarchive = onUnarchive,
            onArchiveRead = onArchiveRead,
            onTaskDone = onTaskDone,
            onConnect = onConnect,
            onOpenChaoxing = onOpenChaoxing,
            onOpenUrl = onOpenUrl,
            onApplyChange = onApplyChange,
        )
    }
}

/**
 * 统一的学习通通知与作业中心主体面板。
 *
 * 既可直接嵌入「学习 → 学习通」首页标签，也可被首页通知卡的通知抽屉复用：
 * 顶栏状态 + 搜索 + 三段视图（待办 / 通知 / 课表异动），「作业与测验」与「教学评价」分区归类，
 * 点击任意卡片或「直达」按钮均可在内置带登录态页面打开原页面或跳往学习通 App。
 */
@Composable
fun NoticeCenterPanel(
    notices: List<XuexitongNotice>,
    locallyReadIds: Set<String>,
    archivedIds: Set<String>,
    doneTaskKeys: Set<String>,
    unreadCount: Int,
    lastFetchAt: Long,
    loggedIn: Boolean,
    sessionExpired: Boolean,
    busy: Boolean,
    courses: List<CourseBlock>,
    now: Long,
    embedded: Boolean = false,
    contentBottomPadding: Dp = 24.dp,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit,
    onMarkRead: (String) -> Unit,
    onArchive: (String) -> Unit,
    onUnarchive: (String) -> Unit,
    onArchiveRead: () -> Unit,
    onTaskDone: (String, Boolean) -> Unit,
    onConnect: () -> Unit,
    onOpenChaoxing: (XuexitongLaunchTarget) -> Unit,
    onOpenUrl: (title: String, url: String) -> Unit,
    onApplyChange: (ScheduleChange) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(SheetTab.TODO) }
    var todoFilter by rememberSaveable { mutableStateOf(TodoFilter.ALL) }
    var filter by rememberSaveable { mutableStateOf(NoticeFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTask by remember { mutableStateOf<TaskItem?>(null) }
    val horizontalPad = if (embedded) 16.dp else 20.dp

    fun isUnread(notice: XuexitongNotice) =
        notice.serverUnread && notice.id !in locallyReadIds

    val keyword = query.trim()

    val activeNotices = remember(notices, archivedIds) {
        notices.filter { it.id !in archivedIds }
    }
    val archivedNotices = remember(notices, archivedIds) {
        notices.filter { it.id in archivedIds }
    }

    val tasks = remember(activeNotices, now) { NoticeExtract.buildTasks(activeNotices, now) }
    val changes = remember(activeNotices) { NoticeExtract.buildChanges(activeNotices) }
    val matchedTasks = remember(tasks, keyword) {
        if (keyword.isEmpty()) tasks else tasks.filter { it.matches(keyword) }
    }
    val openTasks = remember(matchedTasks, doneTaskKeys) {
        matchedTasks.filter { it.key !in doneTaskKeys }
    }
    val studyTasks = remember(openTasks) {
        openTasks.filter { it.kind != TaskKind.EVALUATION }
    }
    val evalTasks = remember(openTasks) {
        openTasks.filter { it.kind == TaskKind.EVALUATION }
    }
    val doneTasks = remember(matchedTasks, doneTaskKeys) {
        matchedTasks.filter { it.key in doneTaskKeys }
    }
    val visibleChanges = remember(changes, keyword) {
        if (keyword.isEmpty()) changes else changes.filter { it.matches(keyword) }
    }

    val filtered = remember(activeNotices, archivedNotices, filter, locallyReadIds, keyword) {
        val byCategory = when (filter) {
            NoticeFilter.ALL -> activeNotices
            NoticeFilter.UNREAD -> activeNotices.filter { it.serverUnread && it.id !in locallyReadIds }
            NoticeFilter.HOMEWORK -> activeNotices.filter { it.category == NoticeCategory.HOMEWORK }
            NoticeFilter.EVALUATION -> activeNotices.filter { it.category == NoticeCategory.EVALUATION }
            NoticeFilter.SIGNIN -> activeNotices.filter { it.category == NoticeCategory.SIGNIN }
            NoticeFilter.ANNOUNCEMENT -> activeNotices.filter { it.category == NoticeCategory.ANNOUNCEMENT }
            NoticeFilter.ARCHIVED -> archivedNotices
        }
        if (keyword.isEmpty()) byCategory else byCategory.filter { it.matches(keyword) }
    }

    val studyTodoCount = tasks.count { it.key !in doneTaskKeys && it.kind != TaskKind.EVALUATION && it.status != TaskStatus.CLOSED }
    val evalTodoCount = tasks.count { it.key !in doneTaskKeys && it.kind == TaskKind.EVALUATION && it.status != TaskStatus.CLOSED }

    Column(modifier) {
        if (!embedded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPad)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "通知与作业中心",
                    color = NoticeInk,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unreadCount > 0) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(NoticeBlue),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$unreadCount",
                            color = Color.White,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    NoticeChipButton(label = "全部已读", onClick = onMarkAllRead)
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .liquidPress(enabled = loggedIn && !busy, onClick = onRefresh)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (busy) "抓取中…" else NoticeFormat.fetchAge(lastFetchAt),
                        color = NoticeMeta,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        Icons.Outlined.Refresh,
                        "刷新通知",
                        tint = NoticeMeta,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        } else if (loggedIn) {
            val statusSummary = buildString {
                append("待办 ").append(studyTodoCount)
                if (evalTodoCount > 0) append(" · 评价 ").append(evalTodoCount)
                append(" · 未读 ").append(unreadCount)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    statusSummary,
                    color = NoticeMeta,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unreadCount > 0) {
                    NoticeChipButton(label = "全部已读", onClick = onMarkAllRead)
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .liquidPress(enabled = !busy, onClick = onRefresh)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (busy) "抓取中…" else NoticeFormat.fetchAge(lastFetchAt),
                        color = NoticeMeta,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        Icons.Outlined.Refresh,
                        "刷新通知",
                        tint = BrandGreen,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 关键字搜索
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPad)
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(NoticeCard)
                .border(1.dp, NoticeHairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search,
                null,
                tint = NoticeFaint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("搜索课程、作业、评价或关键字", color = NoticeMeta, fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = NoticeInk, fontSize = 12.5.sp, lineHeight = 17.sp),
                    cursorBrush = SolidColor(NoticeBlue),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Outlined.Close,
                    "清空搜索",
                    tint = NoticeFaint,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .liquidPress(pressedScale = 0.9f) { query = "" },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 三段主视图：待办 / 通知 / 课表异动
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPad)
                .clip(RoundedCornerShape(12.dp))
                .background(NoticeCard)
                .border(1.dp, NoticeHairline, RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            SheetTab.entries.forEach { item ->
                val selected = tab == item
                val count = when (item) {
                    SheetTab.TODO -> openTasks.count { it.status != TaskStatus.CLOSED }
                    SheetTab.RAW -> filtered.size
                    SheetTab.CHANGE -> visibleChanges.size
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) NoticeBlueSoft else Color.Transparent)
                        .liquidPress(pressedScale = 1f) { tab = item }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (count > 0) "${item.label} $count" else item.label,
                        color = if (selected) NoticeBlue else NoticeMeta,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (tab == SheetTab.TODO && loggedIn && (evalTasks.isNotEmpty() || doneTasks.isNotEmpty())) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPad),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterPill(
                    label = "全部",
                    selected = todoFilter == TodoFilter.ALL,
                    onClick = { todoFilter = TodoFilter.ALL },
                )
                FilterPill(
                    label = if (studyTasks.isNotEmpty()) "作业/考试 ${studyTasks.size}" else "作业/考试",
                    selected = todoFilter == TodoFilter.STUDY,
                    onClick = { todoFilter = TodoFilter.STUDY },
                )
                if (evalTasks.isNotEmpty()) {
                    FilterPill(
                        label = "教学评价 ${evalTasks.size}",
                        selected = todoFilter == TodoFilter.EVALUATION,
                        onClick = { todoFilter = TodoFilter.EVALUATION },
                    )
                }
                if (doneTasks.isNotEmpty()) {
                    FilterPill(
                        label = "已完成 ${doneTasks.size}",
                        selected = todoFilter == TodoFilter.DONE,
                        onClick = { todoFilter = TodoFilter.DONE },
                    )
                }
            }
        }

        if (tab == SheetTab.RAW) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPad),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NoticeFilter.entries.forEach { item ->
                    FilterPill(
                        label = if (item == NoticeFilter.UNREAD) "未读 $unreadCount" else item.label,
                        selected = filter == item,
                        onClick = { filter = item },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        when {
            !loggedIn -> Box(Modifier.fillMaxWidth().weight(1f)) { ConnectState(onConnect = onConnect) }

            tab == SheetTab.TODO -> {
                val showEmpty = when (todoFilter) {
                    TodoFilter.ALL -> matchedTasks.isEmpty()
                    TodoFilter.STUDY -> studyTasks.isEmpty()
                    TodoFilter.EVALUATION -> evalTasks.isEmpty()
                    TodoFilter.DONE -> doneTasks.isEmpty()
                }
                if (showEmpty) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        EmptyState(
                            text = if (keyword.isNotEmpty()) "没有匹配「$keyword」的待办" else "暂无待办事项",
                            hint = if (keyword.isNotEmpty()) {
                                "换个关键字，或点搜索框右侧 × 清空"
                            } else {
                                "学习通有新作业、测验或教学评价时会自动汇总到这里"
                            },
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(start = horizontalPad, end = horizontalPad, bottom = contentBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (sessionExpired) {
                            item { SessionExpiredBanner(onConnect = onConnect) }
                        }
                        if ((todoFilter == TodoFilter.ALL || todoFilter == TodoFilter.STUDY) && studyTasks.isNotEmpty()) {
                            if (todoFilter == TodoFilter.ALL && evalTasks.isNotEmpty()) {
                                item(key = "hdr-study") { SectionGroupHeader("作业与测验", studyTasks.size) }
                            }
                            items(studyTasks, key = { it.key }) { task ->
                                val targetUrl = task.latest.attachmentUrl.ifBlank { XuexitongLauncher.WEB_NOTICE }
                                val pageTitle = task.title.ifBlank { "任务详情" }
                                TaskCard(
                                    task = task,
                                    courses = courses,
                                    now = now,
                                    done = false,
                                    onOpenChaoxing = {
                                        onOpenChaoxing(
                                            XuexitongLaunchTarget.fromNotice(task.latest, titleOverride = pageTitle)
                                        )
                                    },
                                    onToggleDone = { onTaskDone(task.key, true) },
                                    onClick = { selectedTask = task },
                                    onOpenTarget = if (task.latest.attachmentUrl.isNotBlank()) {
                                        { onOpenUrl(pageTitle, targetUrl) }
                                    } else null,
                                )
                            }
                        }
                        if ((todoFilter == TodoFilter.ALL || todoFilter == TodoFilter.EVALUATION) && evalTasks.isNotEmpty()) {
                            if (todoFilter == TodoFilter.ALL) {
                                item(key = "hdr-eval") { SectionGroupHeader("课堂教学评价", evalTasks.size) }
                            }
                            items(evalTasks, key = { it.key }) { task ->
                                val targetUrl = task.latest.attachmentUrl.ifBlank { XuexitongLauncher.WEB_NOTICE }
                                val displayTitle = NoticeFormat.shortCourse(task.courseName).ifBlank { task.title }
                                TaskCard(
                                    task = task,
                                    courses = courses,
                                    now = now,
                                    done = false,
                                    onOpenChaoxing = {
                                        onOpenChaoxing(
                                            XuexitongLaunchTarget.fromNotice(task.latest, titleOverride = displayTitle)
                                        )
                                    },
                                    onToggleDone = { onTaskDone(task.key, true) },
                                    onClick = { selectedTask = task },
                                    onOpenTarget = if (task.latest.attachmentUrl.isNotBlank()) {
                                        { onOpenUrl(displayTitle, targetUrl) }
                                    } else null,
                                )
                            }
                        }
                        if ((todoFilter == TodoFilter.ALL || todoFilter == TodoFilter.DONE) && doneTasks.isNotEmpty()) {
                            if (todoFilter == TodoFilter.ALL) {
                                item(key = "hdr-done") { SectionGroupHeader("已完成", doneTasks.size) }
                            }
                            items(doneTasks, key = { it.key }) { task ->
                                val targetUrl = task.latest.attachmentUrl.ifBlank { XuexitongLauncher.WEB_NOTICE }
                                val pageTitle = if (task.kind == TaskKind.EVALUATION) {
                                    NoticeFormat.shortCourse(task.courseName).ifBlank { task.title }
                                } else {
                                    task.title.ifBlank { "任务详情" }
                                }
                                TaskCard(
                                    task = task,
                                    courses = courses,
                                    now = now,
                                    done = true,
                                    onOpenChaoxing = {
                                        onOpenChaoxing(
                                            XuexitongLaunchTarget.fromNotice(task.latest, titleOverride = pageTitle)
                                        )
                                    },
                                    onToggleDone = { onTaskDone(task.key, false) },
                                    onClick = { selectedTask = task },
                                    onOpenTarget = if (task.latest.attachmentUrl.isNotBlank()) {
                                        { onOpenUrl(pageTitle, targetUrl) }
                                    } else null,
                                )
                            }
                        }
                        item {
                            SheetFooter("点击卡片看正文，「直达 ↗」在内置带登录态页面打开 · 已合并同一任务的多条提醒")
                        }
                    }
                }
            }

            tab == SheetTab.CHANGE -> if (visibleChanges.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    EmptyState(
                        text = "没有课表异动",
                        hint = "调课、停课、补课类公告会汇总到这里",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = horizontalPad, end = horizontalPad, bottom = contentBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (sessionExpired) {
                        item { SessionExpiredBanner(onConnect = onConnect) }
                    }
                    items(visibleChanges, key = { "change-${it.noticeId}" }) { change ->
                        ChangeCard(
                            change = change,
                            onApply = { onApplyChange(change) },
                            onOpenChaoxing = {
                                onOpenChaoxing(XuexitongLaunchTarget.fromNotice(change.notice))
                            },
                        )
                    }
                    item {
                        SheetFooter(
                            "只有明确写清「改到哪天第几节」的公告才提供改课表；" +
                                "互调与停课需自行确认"
                        )
                    }
                }
            }

            filtered.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    text = when {
                        keyword.isNotEmpty() -> "没有匹配「$keyword」的通知"
                        notices.isEmpty() -> "暂无学习通通知"
                        else -> "当前分类暂无通知"
                    },
                    hint = when {
                        keyword.isNotEmpty() -> "换个关键字，或点搜索框右侧 × 清空"
                        notices.isEmpty() -> "有新的作业、签到或公告时会自动出现在这里"
                        else -> null
                    },
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = horizontalPad, end = horizontalPad, bottom = contentBottomPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (sessionExpired) {
                    item { SessionExpiredBanner(onConnect = onConnect) }
                }
                items(filtered, key = { it.id }) { notice ->
                    val archived = notice.id in archivedIds
                    val targetUrl = notice.attachmentUrl.ifBlank { XuexitongLauncher.WEB_NOTICE }
                    val cleanTitle = NoticeFormat.cleanTitle(notice.title, notice.category).ifBlank { "学习通通知" }
                    NoticeItemCard(
                        notice = notice,
                        courses = courses,
                        unread = isUnread(notice),
                        expanded = expandedId == notice.id,
                        archived = archived,
                        onToggle = {
                            if (expandedId == notice.id) {
                                expandedId = null
                            } else {
                                expandedId = notice.id
                                if (isUnread(notice)) onMarkRead(notice.id)
                            }
                        },
                        onMarkRead = { onMarkRead(notice.id) },
                        onArchive = { onArchive(notice.id) },
                        onUnarchive = { onUnarchive(notice.id) },
                        onOpenDirect = { onOpenUrl(cleanTitle, targetUrl) },
                        onOpenChaoxing = {
                            onOpenChaoxing(XuexitongLaunchTarget.fromNotice(notice, titleOverride = cleanTitle))
                        },
                    )
                }
                item {
                    NoticeChipButton(
                        label = "归档全部已读",
                        onClick = onArchiveRead,
                    )
                }
                item { SheetFooter("点击展开可直达学习通 App 或内置带登录态页面查看详情 · 已读与归档仅保存在本机") }
            }
        }
    }

    selectedTask?.let { task ->
        val done = task.key in doneTaskKeys
        val targetUrl = task.latest.attachmentUrl.ifBlank { XuexitongLauncher.WEB_NOTICE }
        val pageTitle = if (task.kind == TaskKind.EVALUATION) {
            NoticeFormat.shortCourse(task.courseName).ifBlank { task.title }
        } else {
            task.title
        }
        TaskDetailDialog(
            task = task,
            done = done,
            onDismiss = { selectedTask = null },
            onToggleDone = {
                onTaskDone(task.key, !done)
                selectedTask = null
            },
            onOpenDirect = {
                selectedTask = null
                onOpenUrl(pageTitle, targetUrl)
            },
            onOpenChaoxing = {
                selectedTask = null
                onOpenChaoxing(XuexitongLaunchTarget.fromNotice(task.latest, titleOverride = pageTitle))
            },
        )
    }
}

@Composable
private fun SectionGroupHeader(title: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = NoticeSub,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$count",
            color = NoticeMeta,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SessionExpiredBanner(onConnect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(NoticeDangerBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "学习通登录已失效，通知可能不是最新的。",
            color = NoticeDangerFg,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            "重新登录",
            color = NoticeBlue,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .liquidPress(pressedScale = 0.94f, onClick = onConnect)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SheetFooter(text: String) {
    Text(
        text,
        color = NoticeMeta,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = if (selected) Color.White else NoticeSub,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        maxLines = 1,
        softWrap = false,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) NoticeBlue else NoticeCard)
            .border(1.dp, if (selected) NoticeBlue else NoticeHairline, shape)
            .liquidPress(pressedScale = 0.95f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun ConnectState(onConnect: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp),
        ) {
            Text("还没有连接学习通", color = NoticeInk, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "登录后自动汇总作业、签到与课程公告，课表页也会显示最新通知。",
                color = NoticeMeta,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onConnect,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
            ) {
                Text("连接学习通", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun EmptyState(text: String, hint: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = NoticeSub, fontSize = 14.sp, lineHeight = 20.sp)
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(hint, color = NoticeMeta, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun NoticeItemCard(
    notice: XuexitongNotice,
    courses: List<CourseBlock>,
    unread: Boolean,
    expanded: Boolean,
    archived: Boolean,
    onToggle: () -> Unit,
    onMarkRead: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onOpenDirect: () -> Unit,
    onOpenChaoxing: () -> Unit,
) {
    val visual = noticeCourseVisual(notice, courses)
    val shape = RoundedCornerShape(18.dp)
    val content = remember(notice.content, notice.title, notice.attachmentName) {
        NoticeFormat.cleanContent(notice.content, notice.title, notice.attachmentName)
    }
    val title = remember(notice.title, notice.category) {
        NoticeFormat.cleanTitle(notice.title, notice.category).ifBlank { "学习通通知" }
    }
        Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NoticeCard)
            .animateContentSize()
            .liquidPress(pressedScale = 0.985f, onClick = onToggle)
    ) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(visual.base)
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 13.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoursePill(visual)
                Spacer(Modifier.width(8.dp))
                val sender = NoticeFormat.senderLabel(notice.sender).ifBlank { "学习通" }
                val time = NoticeFormat.relativeTime(notice.timeMillis)
                Text(
                    listOf(sender, time).filter { it.isNotBlank() }.joinToString(" · "),
                    color = NoticeMeta,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(NoticeBlue))
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                title,
                color = NoticeInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 21.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    content,
                    color = NoticeSub,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(
                visible = expanded &&
                    notice.category == NoticeCategory.HOMEWORK &&
                    !notice.deadline.isNullOrBlank(),
                enter = expandVertically(animationSpec = androidx.compose.animation.core.tween(220)) + fadeIn(),
                exit = shrinkVertically(animationSpec = androidx.compose.animation.core.tween(180)) + fadeOut(),
            ) {
                Box(Modifier.padding(top = 8.dp)) { DeadlineChip(notice.deadline.orEmpty()) }
            }
            Spacer(Modifier.height(10.dp))
            val hasAttachment = notice.attachmentName.isNotBlank()
            if (expanded) {
                if (hasAttachment) {
                    AttachmentLabel(notice, Modifier.fillMaxWidth().padding(bottom = 8.dp))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "去学习通",
                        color = BrandGreen,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(NoticeCapsule)
                            .liquidPress(pressedScale = 0.94f, onClick = onOpenChaoxing)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    DirectPageChip(onClick = onOpenDirect)
                    NoticeChipButton(
                        label = if (archived) "还原" else "归档",
                        onClick = if (archived) onUnarchive else onArchive,
                    )
                    if (unread) {
                        NoticeChipButton(label = "已读", onClick = onMarkRead)
                    }
                }
            } else {
                // 未展开时保持单行紧凑布局：左附件 / 右操作按钮
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasAttachment) {
                        AttachmentLabel(notice, Modifier.weight(1f, fill = false))
                    } else {
                        Spacer(Modifier.width(0.dp))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (notice.attachmentUrl.isNotBlank()) {
                            DirectPageChip(onClick = onOpenDirect)
                        }
                        NoticeChipButton(
                            label = if (archived) "还原" else "归档",
                            onClick = if (archived) onUnarchive else onArchive,
                        )
                        if (unread) {
                            NoticeChipButton(label = "已读", onClick = onMarkRead)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentLabel(notice: XuexitongNotice, modifier: Modifier = Modifier) {
    Text(
        buildString {
            append("📎 ")
            append(notice.attachmentName)
            if (notice.attachmentSize.isNotBlank()) append("（${notice.attachmentSize}）")
        },
        color = NoticeBlue,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun DirectPageChip(onClick: () -> Unit) {
    Text(
        "打开原页面 ↗",
        color = NoticeBlue,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(NoticeBlueSoft)
            .liquidPress(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun NoticeChipButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = NoticeSub,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(NoticeCard)
            .border(1.dp, NoticeHairline, RoundedCornerShape(50))
            .liquidPress(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun CoursePill(visual: NoticeCourseVisual) {
    Text(
        visual.courseName.ifBlank { "学习通" },
        color = visual.pillText,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 160.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(visual.pillBg)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun DeadlineChip(deadline: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(NoticeDangerBg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⏰", fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.width(5.dp))
        Text(
            "截止：$deadline",
            color = NoticeDangerFg,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
