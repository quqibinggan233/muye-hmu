package com.fengkao.timetable.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.fengkao.timetable.SyncScheduler
import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.ui.MainViewModel
import com.fengkao.timetable.ui.discover.DiscoverScreen
import com.fengkao.timetable.ui.shower.ShowerViewModel
import com.fengkao.timetable.ui.water.WaterViewModel
import com.fengkao.timetable.ui.xuexitong.XuexitongAccountPage
import com.fengkao.timetable.ui.xuexitong.XuexitongViewModel
import com.fengkao.timetable.ui.theme.*
import com.fengkao.timetable.ui.navigation.PageStack
import com.hmu.muye.data.parcel.IdentityCodeShortcuts
import com.hmu.muye.ui.life.LifeViewModel

@Composable
fun ProfileScreen(
    viewModel: MainViewModel,
    waterModel: WaterViewModel,
    showerModel: ShowerViewModel,
    xuexitongModel: XuexitongViewModel,
    lifeModel: LifeViewModel = composeViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val water by waterModel.state.collectAsStateWithLifecycle()
    val life by lifeModel.state.collectAsStateWithLifecycle()
    val xuexitong by xuexitongModel.state.collectAsStateWithLifecycle()
    val infoModel: ProfileInfoViewModel = composeViewModel()
    val context = LocalContext.current
    val pageStateHolder = rememberSaveableStateHolder()
    var stack by rememberSaveable { mutableStateOf(listOf(ProfilePage.HOME.name)) }
    fun go(next: ProfilePage) { stack = pushProfilePage(stack, next) }
    fun back() { stack = popProfilePage(stack) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val s = state.settings
    val student = state.schedule?.student
    val name = student?.name?.takeIf { it.isNotBlank() } ?: s.account.ifBlank { "当前用户" }
    val studentId = student?.studentId?.takeIf { it.isNotBlank() } ?: s.account
    val term = state.term ?: TermCalculator.autoTerm()
    val start = viewModel.termStartOf(s, term)
    val today = TermCalculator.today()
    val week = TermCalculator.weekOf(start, today)
    val weekText = when {
        today.isBefore(start) -> "尚未开学 · 共 ${s.termWeeks} 周"
        week > s.termWeeks -> "学期已结束 · 共 ${s.termWeeks} 周"
        else -> "当前第 $week 周 · 共 ${s.termWeeks} 周"
    }
    SettingsTheme {
        PageStack(stack, ::back) { key ->
        val page = ProfilePage.valueOf(key)
        if (page == ProfilePage.INSIGHTS) DiscoverScreen(viewModel, onBack = ::back)
        else pageStateHolder.SaveableStateProvider(page.name) {
        SettingsPage(page.title, if (page == ProfilePage.HOME) null else ::back) {
            when (page) {
                ProfilePage.HOME -> {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardWhite)
                        .clickable { go(ProfilePage.ACCOUNT) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        StudentAvatar(name)
                        Column(Modifier.weight(1f)) {
                            Text(name, color = InkDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("学号 ${studentId.ifBlank { "暂无信息" }}", color = InkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = InkGray)
                    }
                    SettingsSection("课程与同步") {
                        SettingsItem("课程表设置", Icons.Outlined.CalendarMonth, "学期、上课时间、显示设置", divider = true) { go(ProfilePage.SCHEDULE) }
                        SettingsItem("同步与数据", Icons.Outlined.Sync, "教务同步、自动更新") { go(ProfilePage.SYNC) }
                    }
                    SettingsSection("校园服务") { SettingsItem("校园服务账户", Icons.Outlined.WaterDrop, "喝水 798 · 易校园 · 学习通") { go(ProfilePage.SERVICES) } }
                    SettingsSection("其他") {
                        SettingsItem("应用设置", Icons.Outlined.Settings, divider = true) { go(ProfilePage.APP) }
                        SettingsItem("关于", Icons.Outlined.Info) { go(ProfilePage.ABOUT) }
                    }
                }
                ProfilePage.ACCOUNT -> {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StudentAvatar(name, large = true)
                        Text(name, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = InkDark)
                        Text("学号 ${studentId.ifBlank { "暂无信息" }}", color = InkGray, fontSize = 13.sp)
                    }
                    SettingsSection {
                        SettingsItem("个人信息", Icons.Outlined.Person, divider = true) { go(ProfilePage.PERSONAL_INFO) }
                        SettingsItem("教务系统账户", Icons.Outlined.School) { go(ProfilePage.TEACHING_ACCOUNT) }
                    }
                    SettingsSection(danger = true) {
                        SettingsItem("退出当前用户", Icons.AutoMirrored.Outlined.Logout, danger = true,
                            enabled = !state.syncing && !state.backgroundSyncing) { confirm = "user" }
                    }
                    if (state.syncing || state.backgroundSyncing) SettingsHint("正在同步，完成后可退出当前用户")
                }
                ProfilePage.PERSONAL_INFO -> {
                    SettingsSection {
                        SettingsValue("姓名", student?.name.orEmpty()); SettingsValue("学号", studentId)
                        SettingsValue("专业", student?.major.orEmpty()); SettingsValue("年级", student?.grade.orEmpty())
                        SettingsValue("学院", student?.department.orEmpty()); SettingsValue("班级", student?.className.orEmpty())
                    }
                    SettingsHint(if (student == null) "暂无学生信息，完成教务同步后会自动显示" else "个人信息来自教务系统，本页仅展示当前学生身份")
                }
                ProfilePage.TEACHING_ACCOUNT -> {
                    SettingsSection {
                        SettingsValue("账号", maskAccount(s.account)); SettingsValue("账户状态", if (state.loggedIn) "已绑定" else "未绑定")
                        SettingsValue("同步凭据", if (s.passwordEnc.isNotBlank()) "已加密保存" else "未保存，自动同步不可用")
                    }
                    SettingsHint("更换当前用户请返回个人账户退出后重新登录；喝水和洗澡账户独立保存。")
                }
                ProfilePage.SCHEDULE -> {
                    SettingsSection {
                        SettingsItem("学期设置", Icons.Outlined.CalendarMonth, weekText, divider = true) { go(ProfilePage.TERM) }
                        SettingsItem("上课时间", Icons.Outlined.Schedule, divider = true) { go(ProfilePage.PERIODS) }
                        SettingsSwitchItem("显示周末", Icons.Outlined.DateRange, "在课程表中显示周六 / 周日", s.showWeekend, viewModel::setShowWeekend)
                    }
                    SettingsSection { SettingsItem("管理已添加课程", Icons.AutoMirrored.Outlined.List) { go(ProfilePage.COURSES) } }
                }
                ProfilePage.TERM -> {
                    val termWeeksSubtitle = when {
                        s.termWeeksManual -> "已手动调整"
                        state.schedule != null -> "已按课表有课周数自动识别"
                        else -> "同步课表后将自动识别有课周数"
                    }
                    SettingsSection {
                        SettingsValue("当前学期", term.label)
                        SettingsItem("第一周第一天", Icons.Outlined.Event, value = start.toString(), divider = true) { editor = "date" }
                        SettingsItem("学期周数", Icons.Outlined.DateRange, subtitle = termWeeksSubtitle, value = "${s.termWeeks} 周") { editor = "weeks" }
                    }
                    SettingsHint(weekText)
                }
                ProfilePage.PERIODS -> {
                    SettingsSection {
                        s.periodTimes().forEach { SettingsValue("第 ${it.period} 节", "${it.startText()} – ${it.endText()}") }
                        SettingsItem("编辑上课时间", Icons.Outlined.Edit) { editor = "periods" }
                    }
                }
                ProfilePage.COURSES -> CourseManagement(state, viewModel)
                ProfilePage.SYNC -> {
                    SyncSummary(state, viewModel::refresh)
                    SettingsSection {
                        SettingsSwitchItem("自动更新课程表", Icons.Outlined.Sync,
                            "启动时自动同步，并每 ${SyncScheduler.PERIOD_HOURS} 小时后台更新", s.autoSync, viewModel::setAutoSync, divider = true)
                        SettingsItem("教务同步设置", Icons.Outlined.School, divider = true) { go(ProfilePage.TEACHING_SYNC) }
                        SettingsItem("课程表洞察", Icons.Outlined.Insights, "课程异动 · 课表统计") { go(ProfilePage.INSIGHTS) }
                    }
                }
                ProfilePage.TEACHING_SYNC -> {
                    var showJwWebLogin by rememberSaveable { mutableStateOf(false) }
                    SettingsSection {
                        SettingsValue("同步账号", maskAccount(s.account))
                        SettingsValue("数据来源", "超星综合教学管理系统")
                        SettingsValue("网页会话", if (state.jwConnected) "已连接（Cookie 加密保存）" else "未登录")
                        SettingsItem("教务系统地址", Icons.Outlined.Language, value = hostOf(s.baseUrl), divider = true) { editor = "url" }
                        SettingsItem("打开超星教务网页登录", Icons.Outlined.School, "兼容验证码、统一认证与学习通扫码") { showJwWebLogin = true }
                    }
                    SettingsHint("河南牧业经济学院教务入口默认连接 hnuahe.jw.chaoxing.com；网页登录完成后会自动加密保存本机 Cookie。")
                    if (showJwWebLogin) {
                        com.hmu.muye.data.jw.ChaoxingWebLoginDialog(
                            baseUrl = s.baseUrl,
                            onDismiss = { showJwWebLogin = false },
                            onCapturedCookies = { cookies ->
                                showJwWebLogin = false
                                viewModel.onChaoxingWebLoginCaptured(cookies)
                            },
                        )
                    }
                }
                ProfilePage.SERVICES -> {
                    val yxy = life.yixiaoyuan
                    val yxyConfigured = life.yixiaoyuanSignedIn || yxy.hasCredential ||
                        yxy.showerCode.isNotBlank() || yxy.cardBalanceYuan != null
                    val yxySubtitle = when {
                        life.yixiaoyuanSignedIn && yxy.showBalanceCard && yxy.cardBalanceYuan != null ->
                            "已登录 · 余额 ¥%.2f".format(yxy.cardBalanceYuan)
                        life.yixiaoyuanSignedIn -> "已登录 · ${maskAccount(life.yixiaoyuanMobile)}"
                        yxy.showerCode.isNotBlank() -> "用水码 ${yxy.formattedShowerCode()}"
                        yxy.hasCredential -> "已配 shiroJID · 点击刷新 8 位用水码"
                        else -> "短信登录查余额 · 8 位动态用水码"
                    }
                    SettingsSection {
                        AccountItem("喝水账户", Icons.Outlined.WaterDrop, maskAccount(water.phone), water.loggedIn, water.busy, { go(ProfilePage.WATER) }, divider = true)
                        AccountItem("易校园（洗澡 / 一卡通）", Icons.Outlined.Shower, yxySubtitle, yxyConfigured, life.yixiaoyuanBusy, { go(ProfilePage.SHOWER) }, divider = true)
                        AccountItem("学习通账户", Icons.Outlined.Notifications, maskAccount(xuexitong.phone), xuexitong.loggedIn && !xuexitong.sessionExpired, xuexitong.busy, { go(ProfilePage.XUEXITONG) })
                    }
                    SettingsHint("分别管理慧生活 798 喝水、易校园洗澡/一卡通与学习通账户")
                }
                ProfilePage.WATER -> WaterAccountPage(water, waterModel) { confirm = it }
                ProfilePage.SHOWER -> YixiaoyuanAccountPage(
                    state = life.yixiaoyuan,
                    signedIn = life.yixiaoyuanSignedIn,
                    savedMobile = life.yixiaoyuanMobile,
                    busy = life.yixiaoyuanBusy,
                    waterCode = life.waterCodeInfo,
                    statusMessage = life.statusMessage,
                    statusError = life.statusError,
                    onOpenApp = { IdentityCodeShortcuts.openYixiaoyuan(context) },
                    onLogin = lifeModel::loginYixiaoyuan,
                    onLogout = lifeModel::logoutYixiaoyuan,
                    onRefreshBalance = lifeModel::refreshCardBalance,
                    onRefreshWaterCode = lifeModel::saveShiroJidAndRefresh,
                    onToggleShowBalance = lifeModel::setShowYixiaoyuanBalance,
                    onSave = lifeModel::saveYixiaoyuanConfig,
                )
                ProfilePage.XUEXITONG -> XuexitongAccountPage(xuexitong, xuexitongModel)
                ProfilePage.APP -> {
                    SettingsSection {
                        SettingsItem("外观", Icons.Outlined.DarkMode, value = ThemeMode.of(s.themeMode).label,
                            divider = true) { editor = "theme" }
                        SettingsItem("欢迎与初次设置", Icons.Outlined.WavingHand,
                            "账户连接、课表偏好", divider = true, onClick = viewModel::reopenWelcome)
                        SettingsItem("高级设置", Icons.Outlined.Settings) { go(ProfilePage.ADVANCED) }
                    }
                }
                ProfilePage.ADVANCED -> {
                    SettingsSection {
                        SettingsItem("教务连接设置", Icons.Outlined.Language, "地址与数据来源", divider = true) { go(ProfilePage.TEACHING_SYNC) }
                        SettingsSwitchItem("预测性返回手势", Icons.Outlined.Swipe, "返回时随手势预览上一页，可取消返回", s.predictiveBack, viewModel::setPredictiveBack)
                    }
                    SettingsHint("连接设置与同步与数据使用同一份配置")
                }
                ProfilePage.ABOUT -> AboutPage(
                    openLicenses = { go(ProfilePage.LICENSES) },
                    openUpdateSettings = { editor = "update_source" },
                    state = state,
                    onCheckUpdate = { viewModel.checkUpdate(manual = true) },
                    onOpenUpdate = { viewModel.startInAppUpdate(it) },
                )
                ProfilePage.LICENSES -> LicensesPage(infoModel)
                ProfilePage.INSIGHTS -> Unit
            }
        }
        }
        }
        ProfileEditors(editor, s, start, viewModel) { editor = null }
        confirm?.let { action ->
            val user = action == "user"
            val relogin = action.endsWith("login")
            val busy = if (user) state.syncing || state.backgroundSyncing else water.busy
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = {
                    Text(
                        when {
                            user -> "确认退出当前用户？"
                            relogin -> "重新登录喝水账户？"
                            else -> "退出喝水账户？"
                        }
                    )
                },
                text = {
                    Text(
                        if (user) "将清除本机保存的账号密码与课表缓存，之后需要重新登录。喝水账户不受影响。"
                        else "仅移除慧生活 798 喝水服务的登录凭据，其他配置不受影响。"
                    )
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        confirm = null
                        if (user) viewModel.logout() else waterModel.logout()
                    }) { Text(if (relogin) "重新登录" else "退出", color = DangerFg) }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
                containerColor = CardWhite,
            )
        }
    }
}

private fun hostOf(url: String) = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
