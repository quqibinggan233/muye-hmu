package com.hmu.muye.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fengkao.timetable.data.model.CourseBlock
import com.fengkao.timetable.ui.MainViewModel
import com.fengkao.timetable.ui.theme.BrandGreen
import com.fengkao.timetable.ui.theme.BrandGreenSoft
import com.fengkao.timetable.ui.theme.CardWhite
import com.fengkao.timetable.ui.theme.Hairline
import com.fengkao.timetable.ui.theme.InkDark
import com.fengkao.timetable.ui.theme.InkGray
import com.fengkao.timetable.ui.theme.InkMid
import com.fengkao.timetable.ui.theme.IsDark
import com.fengkao.timetable.ui.theme.SheetColor
import com.fengkao.timetable.ui.theme.appBackground
import com.fengkao.timetable.ui.water.ServiceMessage
import com.fengkao.timetable.ui.xuexitong.NoticeBlue
import com.fengkao.timetable.ui.xuexitong.NoticeBlueSoft
import com.fengkao.timetable.ui.xuexitong.NoticeCenterPanel
import com.fengkao.timetable.ui.xuexitong.XuexitongLauncher
import com.fengkao.timetable.ui.xuexitong.XuexitongLogin
import com.fengkao.timetable.ui.xuexitong.XuexitongViewModel
import com.fengkao.timetable.ui.xuexitong.XuexitongWebDialog
import com.hmu.muye.data.jw.ChaoxingDirectLoginDialog
import com.hmu.muye.data.jw.ExamNote
import com.hmu.muye.data.jw.GradeItem
import com.hmu.muye.data.parcel.IdentityCodeShortcuts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    studyModel: StudyViewModel,
    mainModel: MainViewModel,
    xuexitongModel: XuexitongViewModel,
) {
    val state by studyModel.state.collectAsStateWithLifecycle()
    val main by mainModel.state.collectAsStateWithLifecycle()
    val xuexitong by xuexitongModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showJwLogin by rememberSaveable { mutableStateOf(false) }
    var showXuexitongLogin by rememberSaveable { mutableStateOf(false) }
    var webTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(Unit) {
        studyModel.refreshSessionState()
        xuexitongModel.enter()
    }
    LaunchedEffect(main.jwConnected) {
        studyModel.refreshSessionState()
    }
    LaunchedEffect(xuexitong.loggedIn, xuexitong.sessionExpired) {
        if (xuexitong.loggedIn && !xuexitong.sessionExpired) {
            showXuexitongLogin = false
            if (main.schedule == null && !main.syncing) {
                mainModel.refresh()
            }
        }
    }

    val courses: List<CourseBlock> =
        (main.schedule?.courses.orEmpty()) + main.settings.customCourses.map { it.toCourseBlock() }

    Column(
        Modifier
            .fillMaxSize()
            .background(appBackground())
            .statusBarsPadding(),
    ) {
        // 顶部标题与子标签固定在顶部，避免与下方 LazyColumn 产生嵌套滚动冲突
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("河南牧业经济学院 · 超星教务与学习通", fontSize = 11.sp, color = InkGray)
                    Text("学习", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = InkDark)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (state.jwConnected) BrandGreenSoft else CardWhite)
                        .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                        .clickable { showJwLogin = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (state.jwConnected) "教务已连接" else "登录超星教务",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.jwConnected) BrandGreen else InkMid,
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StudySubTab.entries.forEach { tab ->
                    val active = state.subTab == tab
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (active) BrandGreen else CardWhite)
                            .border(1.dp, if (active) Color.Transparent else Hairline, RoundedCornerShape(20.dp))
                            .clickable { studyModel.selectSubTab(tab) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            tab.label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) Color.White else InkMid,
                        )
                    }
                }
            }

            ServiceMessage(state.statusMessage, state.statusIsError)
        }

        Spacer(Modifier.height(10.dp))

        if (state.subTab == StudySubTab.XUEXITONG) {
            NoticeCenterPanel(
                notices = xuexitong.notices,
                locallyReadIds = xuexitong.locallyReadIds,
                archivedIds = xuexitong.archivedIds,
                doneTaskKeys = xuexitong.doneTaskKeys,
                unreadCount = xuexitong.unreadCount,
                lastFetchAt = xuexitong.lastFetchAt,
                loggedIn = xuexitong.loggedIn,
                sessionExpired = xuexitong.sessionExpired,
                busy = xuexitong.busy,
                courses = courses,
                now = System.currentTimeMillis(),
                embedded = true,
                contentBottomPadding = 124.dp,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onRefresh = { xuexitongModel.refresh() },
                onMarkAllRead = { xuexitongModel.markAllRead() },
                onMarkRead = xuexitongModel::markRead,
                onArchive = xuexitongModel::archive,
                onUnarchive = xuexitongModel::unarchive,
                onArchiveRead = { xuexitongModel.archiveRead() },
                onTaskDone = { key, done -> xuexitongModel.setTaskDone(key, done) },
                onConnect = { showXuexitongLogin = true },
                onOpenChaoxing = { target -> XuexitongLauncher.open(context, target) },
                onOpenUrl = { pageTitle, targetUrl -> webTarget = pageTitle to targetUrl },
                onApplyChange = { mainModel.setTab(0) },
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 124.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (state.subTab) {
                    StudySubTab.GRADES -> GradesSection(
                        state = state,
                        onOpenLogin = { showJwLogin = true },
                        onRefresh = studyModel::refreshGrades,
                        onSelectTerm = studyModel::selectGradeTerm,
                    )
                    StudySubTab.EXAMS -> ExamsSection(
                        state = state,
                        onRefresh = studyModel::refreshExams,
                        onAddNote = studyModel::addExamNote,
                        onDeleteNote = studyModel::removeExamNote,
                    )
                    StudySubTab.EMPTY_ROOMS -> EmptyRoomsSection(
                        state = state,
                        onBuildingChange = studyModel::updateBuildingQuery,
                        onPeriodChange = studyModel::updatePeriodQuery,
                        onDayChange = studyModel::updateDayOfWeek,
                        onQuery = studyModel::queryEmptyRooms,
                    )
                    StudySubTab.LIBRARY -> LibrarySection(
                        savedUrl = state.libraryUrl,
                        onSaveUrl = studyModel::saveLibraryUrl,
                        onOpenWeChat = { IdentityCodeShortcuts.openWeChat(context) },
                        onOpenUrl = { IdentityCodeShortcuts.openUrl(context, it) },
                    )
                    StudySubTab.XUEXITONG -> Unit
                }
            }
        }
    }

    if (showJwLogin) {
        ChaoxingDirectLoginDialog(
            baseUrl = main.settings.baseUrl,
            initialUsername = studyModel.savedJwUsername().ifBlank { mainModel.savedJwUsername() },
            onDismiss = { showJwLogin = false },
            onLoginSuccess = { user, pwd, cookies ->
                showJwLogin = false
                studyModel.onDirectLoginSuccess(user, pwd, cookies)
                mainModel.onChaoxingDirectLoginSuccess(user, pwd, cookies)
            },
        )
    }

    if (showXuexitongLogin) {
        ModalBottomSheet(
            onDismissRequest = { showXuexitongLogin = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SheetColor,
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
                XuexitongLogin(
                    state = xuexitong,
                    model = xuexitongModel,
                    showHeading = true,
                    submitLabel = if (xuexitong.sessionExpired) "重新登录学习通" else "登录学习通",
                )
                ServiceMessage(xuexitong.message, xuexitong.error)
            }
        }
    }

    webTarget?.let { (pageTitle, targetUrl) ->
        XuexitongWebDialog(
            title = pageTitle,
            url = targetUrl,
            cookies = xuexitongModel.webViewCookies(),
            onDismiss = { webTarget = null },
            onOpenInApp = { currentTitle, currentUrl ->
                XuexitongLauncher.open(context, targetUrl = currentUrl, title = currentTitle)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GradesSection(
    state: StudyUiState,
    onOpenLogin: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTerm: (String) -> Unit,
) {
    StudyCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("成绩与绩点（GPA）", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = InkDark)
                Text(
                    if (state.gradesUpdatedAt > 0L) "更新于 ${formatTime(state.gradesUpdatedAt)}" else "刷新后自动与本地缓存对比，高亮新出成绩",
                    fontSize = 12.sp,
                    color = InkGray,
                )
            }
            TextButton(onClick = onRefresh, enabled = !state.busy) {
                Text(if (state.busy) "查询中…" else "刷新成绩", color = BrandGreen, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricBox("加权均分", if (state.gpaSummary.totalCount > 0) "%.1f".format(state.gpaSummary.weightedAverage) else "—", Modifier.weight(1f))
            MetricBox("平均绩点", if (state.gpaSummary.totalCount > 0) "%.2f".format(state.gpaSummary.averageGpa) else "—", Modifier.weight(1f))
            MetricBox("总学分", if (state.gpaSummary.totalCount > 0) "%.1f".format(state.gpaSummary.totalCredits) else "—", Modifier.weight(1f))
            MetricBox("通过", if (state.gpaSummary.totalCount > 0) "${state.gpaSummary.passedCount}/${state.gpaSummary.totalCount}" else "—", Modifier.weight(1f))
        }
        if (!state.jwConnected) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenLogin, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text("先登录超星教务网页（支持验证码）", color = BrandGreen)
            }
        }
    }

    val terms = listOf("全部") + state.grades.map { it.termLabel }.filter { it.isNotBlank() }.distinct()
    if (terms.size > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            terms.forEach { t ->
                FilterChip(
                    selected = state.selectedGradeTerm == t,
                    onClick = { onSelectTerm(t) },
                    label = { Text(t, fontSize = 12.sp) },
                )
            }
        }
    }

    val shownGrades = if (state.selectedGradeTerm == "全部") state.grades
    else state.grades.filter { it.termLabel == state.selectedGradeTerm }

    if (shownGrades.isEmpty()) {
        StudyCard {
            Text("暂无本地成绩缓存", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
            Spacer(Modifier.height(4.dp))
            Text(
                "点击右上角「刷新成绩」即可从河牧超星教务拉取各科分数、学分与绩点，新出的成绩会自动高亮。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = InkGray,
            )
        }
    } else {
        StudyCard {
            shownGrades.forEachIndexed { idx, item ->
                if (idx > 0) HorizontalDivider(color = Hairline)
                GradeRow(item)
            }
        }
    }
}

@Composable
private fun GradeRow(item: GradeItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.courseName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = InkDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.examNature.isNotBlank()) {
                    val isRelearn = item.examNature == "重修"
                    val badgeBg = when {
                        isRelearn -> NoticeBlueSoft
                        IsDark -> Color(0x28F59E0B)
                        else -> Color(0xFFFEF3E2)
                    }
                    val badgeFg = when {
                        isRelearn -> NoticeBlue
                        IsDark -> Color(0xFFFCD34D)
                        else -> Color(0xFFB45309)
                    }
                    Text(
                        item.examNature,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeFg,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (item.isNew) {
                    Text(
                        "新出成绩",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD64541),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x1AD64541))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            val detail = buildString {
                append("${item.courseType} · ${item.credits} 学分 · 绩点 ${"%.2f".format(item.gpa)}")
                if (item.initialScoreText.isNotBlank()) {
                    append(" · 初修 ${item.initialScoreText}")
                }
            }
            Text(
                detail,
                fontSize = 11.sp,
                color = InkGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            item.scoreText.ifBlank { "—" },
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = if (item.passed) BrandGreen else Color(0xFFD64541),
        )
    }
}

@Composable
private fun ExamsSection(
    state: StudyUiState,
    onRefresh: () -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
) {
    var showAddNote by rememberSaveable { mutableStateOf(false) }
    var draftNote by rememberSaveable { mutableStateOf("") }

    StudyCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("考试安排", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = InkDark)
                Text("拉取一次后本地缓存，考前无需再反复登录教务", fontSize = 12.sp, color = InkGray)
            }
            Button(
                onClick = onRefresh,
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (state.busy) "读取中…" else "读取考试", fontSize = 12.sp)
            }
        }
        if (state.exams.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("暂无考试安排缓存", fontSize = 12.sp, color = InkGray)
        } else {
            Spacer(Modifier.height(8.dp))
            state.exams.forEachIndexed { idx, exam ->
                if (idx > 0) HorizontalDivider(color = Hairline)
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(exam.courseName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
                    Text("${exam.timeText} · ${exam.place} ${if (exam.seatNo.isNotBlank()) "座位 ${exam.seatNo}" else ""}", fontSize = 12.sp, color = InkMid)
                }
            }
        }
    }

    StudyCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("考试便签 (${state.examNotes.size} 条)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkDark, modifier = Modifier.weight(1f))
            TextButton(onClick = { showAddNote = true }) {
                Icon(Icons.Outlined.Add, null, tint = BrandGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("便签", color = BrandGreen, fontWeight = FontWeight.SemiBold)
            }
        }
        if (state.examNotes.isEmpty()) {
            Text("记一条复习提醒、考场备忘或考前鼓励吧", fontSize = 12.sp, color = InkGray)
        } else {
            state.examNotes.forEachIndexed { idx, note ->
                if (idx > 0) HorizontalDivider(color = Hairline)
                ExamNoteRow(note, onDelete = { onDeleteNote(note.id) })
            }
        }
    }

    if (showAddNote) {
        AlertDialog(
            onDismissRequest = { showAddNote = false },
            containerColor = CardWhite,
            title = { Text("添加考试便签") },
            text = {
                OutlinedTextField(
                    value = draftNote,
                    onValueChange = { draftNote = it },
                    label = { Text("备忘内容（如考场携带物品、复习重点）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAddNote(draftNote)
                    draftNote = ""
                    showAddNote = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showAddNote = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ExamNoteRow(note: ExamNote, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(note.content, fontSize = 13.sp, color = InkDark)
            Text(formatTime(note.createdAt), fontSize = 11.sp, color = InkGray)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, "删除", tint = InkGray, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyRoomsSection(
    state: StudyUiState,
    onBuildingChange: (String) -> Unit,
    onPeriodChange: (String) -> Unit,
    onDayChange: (Int) -> Unit,
    onQuery: () -> Unit,
) {
    val buildingPresets = listOf(
        "1号教学楼", "2号教学楼A座", "2号教学楼B座", "2号教学楼C座",
        "生工组团A区", "生工组团B区", "生工组团C区", "大学生活动中心",
    )
    val dayOptions = listOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")
    val periodOptions = listOf("1-2节", "3-4节", "5-6节", "7-8节", "9-10节")
    StudyCard {
        Text("龙子湖空教室查询", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = InkDark)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.buildingQuery,
            onValueChange = onBuildingChange,
            label = { Text("教学楼名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            buildingPresets.forEach { b ->
                FilterChip(
                    selected = state.buildingQuery == b,
                    onClick = { onBuildingChange(b) },
                    label = { Text(b, fontSize = 12.sp) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            dayOptions.forEach { (day, label) ->
                FilterChip(
                    selected = state.selectedDayOfWeek == day,
                    onClick = { onDayChange(day) },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            periodOptions.forEach { p ->
                FilterChip(
                    selected = state.periodQuery == p,
                    onClick = { onPeriodChange(p) },
                    label = { Text(p, fontSize = 12.sp) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onQuery,
            enabled = !state.busy,
            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.busy) "查询中…" else "查询当前空闲教室", fontWeight = FontWeight.SemiBold)
        }
    }
    if (state.emptyRooms.isNotEmpty()) {
        StudyCard {
            state.emptyRooms.forEachIndexed { idx, room ->
                if (idx > 0) HorizontalDivider(color = Hairline)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("${room.building} ${room.roomName}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkDark, modifier = Modifier.weight(1f))
                    Text(room.freePeriods.ifBlank { "空闲" }, fontSize = 12.sp, color = BrandGreen)
                }
            }
        }
    }
}

@Composable
private fun LibrarySection(
    savedUrl: String,
    onSaveUrl: (String) -> Unit,
    onOpenWeChat: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var draftUrl by rememberSaveable(savedUrl) { mutableStateOf(savedUrl) }
    StudyCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.MenuBook, null, tint = BrandGreen, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("河牧图书馆座位预约", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = InkDark)
                Text("学校当前主要通过图书馆微信公众号提供选座功能", fontSize = 12.sp, color = InkGray)
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onOpenWeChat,
            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("一键打开微信前往预约", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = draftUrl,
            onValueChange = { draftUrl = it },
            label = { Text("可选：填入复制出的图书馆网页预约链接") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSaveUrl(draftUrl) }, modifier = Modifier.weight(1f)) {
                Text("保存链接")
            }
            OutlinedButton(
                onClick = { if (draftUrl.isNotBlank()) onOpenUrl(draftUrl) },
                enabled = draftUrl.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("直接打开链接")
            }
        }
    }
}

@Composable
private fun MetricBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (IsDark) Color(0xFF25252B) else Color(0xFFEEF1F6))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = InkDark)
        Text(label, fontSize = 11.sp, color = InkGray)
    }
}

@Composable
private fun StudyCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardWhite)
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        content()
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
