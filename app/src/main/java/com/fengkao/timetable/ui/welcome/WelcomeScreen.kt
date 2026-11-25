package com.fengkao.timetable.ui.welcome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fengkao.timetable.SyncScheduler
import com.fengkao.timetable.R
import kotlinx.coroutines.delay
import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.ui.MainUiState
import com.fengkao.timetable.ui.MainViewModel
import com.fengkao.timetable.ui.login.TeachingLoginForm
import com.fengkao.timetable.ui.navigation.LocalPredictiveBack
import com.fengkao.timetable.ui.navigation.PageStack
import com.fengkao.timetable.ui.navigation.PageMotion
import com.fengkao.timetable.ui.profile.*
import com.fengkao.timetable.ui.theme.*
import com.fengkao.timetable.ui.water.LifeCard
import com.fengkao.timetable.ui.water.LifeTheme
import com.fengkao.timetable.ui.water.ServiceMessage
import com.fengkao.timetable.ui.water.WaterLogin
import com.fengkao.timetable.ui.water.WaterViewModel
import com.fengkao.timetable.ui.xuexitong.XuexitongLogin
import com.fengkao.timetable.ui.xuexitong.XuexitongNotificationPermissionEffect
import com.fengkao.timetable.ui.xuexitong.XuexitongViewModel
import com.hmu.muye.data.jw.ChaoxingDirectLoginDialog
import com.hmu.muye.data.jw.ChaoxingWebLoginDialog
import com.hmu.muye.data.parcel.IdentityCodeShortcuts
import com.hmu.muye.ui.life.LifeViewModel

private enum class WelcomeStep(val label: String, val title: String, val subtitle: String) {
    INTRO("欢迎", "欢迎使用牧野生活", "HMU 校园大小事，一站搞定"),
    TEACHING("课表", "登录课表账户", "支持超星教务（学号密码）或学习通（手机号密码）纯接口直连登录，无需跳转网页。"),
    SERVICES("服务", "连接校园服务", "喝水（慧生活 798）、易校园、超星教务与学习通可在此直连配置，也可稍后绑定。"),
    PREFERENCES("偏好", "按你的习惯看课表", "调整常用设置，之后也能在“我的”里修改。"),
    READY("完成", "设置好了", "你的牧野生活，准备就绪。"),
}

@Composable
fun WelcomeScreen(mainModel: MainViewModel) {
    val main by mainModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Same activity-scoped models as the bottom navigation and account settings.
    val waterModel: WaterViewModel = viewModel()
    val xuexitongModel: XuexitongViewModel = viewModel()
    val lifeModel: LifeViewModel = viewModel()
    val water by waterModel.state.collectAsStateWithLifecycle()
    val xuexitong by xuexitongModel.state.collectAsStateWithLifecycle()
    val life by lifeModel.state.collectAsStateWithLifecycle()
    XuexitongNotificationPermissionEffect(xuexitongModel)
    var step by rememberSaveable { mutableIntStateOf(main.settings.welcomeStep) }
    var service by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var showJwWebLogin by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val keyboard = LocalSoftwareKeyboardController.current
    val busy = main.loginBusy || main.firstFetchActive || main.welcomeSaving ||
        water.busy || xuexitong.busy || life.yixiaoyuanBusy

    fun go(next: Int) {
        keyboard?.hide()
        service = null
        step = next.coerceIn(0, WelcomeStep.entries.lastIndex)
        mainModel.setWelcomeStep(step)
    }
    fun back() {
        keyboard?.hide()
        if (!main.loginBusy && !main.firstFetchActive && !main.welcomeSaving) {
            if (service != null) service = null else go(step - 1)
        }
    }
    LaunchedEffect(main.loggedIn, main.firstFetchActive, main.schedule, step) {
        xuexitongModel.reloadFromRepository()
    }
    LaunchedEffect(xuexitong.loggedIn) {
        if (xuexitong.loggedIn) {
            mainModel.syncAuthStateFromRepositories(autoSyncSchedule = true)
        }
    }
    LaunchedEffect(water.loggedIn, xuexitong.loggedIn) {
        if ((service == "water" && water.loggedIn) ||
            (service == "xuexitong" && xuexitong.loggedIn)
        ) {
            service = null
        }
    }

    val pages = WelcomeStep.entries.take(step + 1).map { it.name } + listOfNotNull(service)
    LifeTheme {
        CompositionLocalProvider(LocalPredictiveBack provides main.settings.predictiveBack) {
            val isService = service != null
            val current = WelcomeStep.entries[step]
            val canContinue = !busy && (current != WelcomeStep.TEACHING ||
                (main.loggedIn && !main.firstFetchFailed))
            WelcomeLayout(
                step = current,
                onBack = if (current == WelcomeStep.INTRO) null else ::back,
                backEnabled = !main.loginBusy && !main.firstFetchActive && !main.welcomeSaving,
                nextLabel = when {
                    isService -> "返回账户列表"
                    current == WelcomeStep.INTRO -> "开始设置"
                    current == WelcomeStep.READY -> if (main.loggedIn) "开始使用" else "完成并去登录"
                    else -> "下一步"
                },
                nextEnabled = if (isService) !busy else canContinue,
                saving = main.welcomeSaving,
                onNext = {
                    if (isService) { keyboard?.hide(); service = null }
                    else if (current == WelcomeStep.READY) mainModel.finishWelcome()
                    else go(current.ordinal + 1)
                },
                skip = if (current == WelcomeStep.TEACHING && !main.loggedIn && !busy)
                    ({ go(current.ordinal + 1) }) else null,
                error = main.welcomeError,
                servicePage = isService,
            ) {
                PageStack(pages, ::back, motion = PageMotion.WELCOME) { page ->
                    holder.SaveableStateProvider(page) {
                        val current = if (page == "water" || page == "shower" || page == "xuexitong") WelcomeStep.SERVICES else WelcomeStep.valueOf(page)
                        WelcomeBody(step = current,
                            title = when (page) {
                                "water" -> "登录喝水账户"
                                "shower" -> "易校园（洗澡 / 一卡通）"
                                "xuexitong" -> "登录学习通账户"
                                else -> current.title
                            },
                            subtitle = when (page) {
                                "water" -> "使用慧生活798账户，登录后可绑定龙子湖饮水设备。"
                                "shower" -> "可在此页使用手机号与密码直连登录查看余额（可按需隐藏），或配置 shiroJID 刷新 8 位动态用水码。"
                                "xuexitong" -> "登录后自动汇总作业、签到与课程公告，课表与学习页同步展示。"
                                else -> if (current == WelcomeStep.READY && !main.loggedIn)
                                    "偏好已保存，接下来可先进入牧野生活或绑定课表账户。" else current.subtitle
                            },
                            isService = page == "water" || page == "shower" || page == "xuexitong",
                        ) {
                            when (page) {
                                "water" -> {
                                    WaterLogin(water, waterModel, showHeading = false, submitLabel = "登录喝水账户")
                                    ServiceMessage(water.message, water.error)
                                }
                                "shower" -> {
                                    YixiaoyuanAccountPage(
                                        state = life.yixiaoyuan,
                                        signedIn = life.yixiaoyuanSignedIn,
                                        savedMobile = life.yixiaoyuanMobile,
                                        busy = life.yixiaoyuanBusy,
                                        waterCode = life.waterCodeInfo,
                                        statusMessage = life.statusMessage,
                                        statusError = life.statusError,
                                        onOpenApp = { IdentityCodeShortcuts.openYixiaoyuan(context) },
                                        onLogin = lifeModel::loginYixiaoyuan,
                                        onLoginByPassword = lifeModel::loginYixiaoyuanByPassword,
                                        onLogout = lifeModel::logoutYixiaoyuan,
                                        onRefreshBalance = lifeModel::refreshCardBalance,
                                        onRefreshWaterCode = lifeModel::saveShiroJidAndRefresh,
                                        onToggleShowBalance = lifeModel::setShowYixiaoyuanBalance,
                                        onSave = lifeModel::saveYixiaoyuanConfig,
                                    )
                                }
                                "xuexitong" -> {
                                    XuexitongLogin(xuexitong, xuexitongModel, showHeading = false, submitLabel = "登录学习通")
                                    ServiceMessage(xuexitong.message, xuexitong.error)
                                }
                                else -> when (current) {
                                    WelcomeStep.INTRO -> WelcomeIntro()
                                    WelcomeStep.TEACHING -> TeachingSetup(
                                        state = main,
                                        model = mainModel,
                                        xuexitongConnected = xuexitong.loggedIn && !xuexitong.sessionExpired,
                                    )
                                    WelcomeStep.SERVICES -> {
                                        val yxy = life.yixiaoyuan
                                        val yxyConfigured = life.yixiaoyuanSignedIn || yxy.hasCredential ||
                                            yxy.showerCode.isNotBlank() || yxy.cardBalanceYuan != null
                                        val yxySubtitle = when {
                                            life.yixiaoyuanSignedIn && yxy.showBalanceCard && yxy.cardBalanceYuan != null ->
                                                "已登录 · 余额 ¥%.2f（可点此配置用水码）".format(yxy.cardBalanceYuan)
                                            life.yixiaoyuanSignedIn ->
                                                "已登录 · ${maskAccount(life.yixiaoyuanMobile)}"
                                            yxy.showerCode.isNotBlank() ->
                                                "已配用水码 ${yxy.formattedShowerCode()}（可点此补充登录）"
                                            yxy.hasCredential ->
                                                "已配 shiroJID · 点此刷新 8 位用水码"
                                            else -> "支持页内密码直连登录查余额与配置 8 位用水码"
                                        }
                                        val xxConnected = xuexitong.loggedIn && !xuexitong.sessionExpired
                                        val xxSubtitle = if (xxConnected) {
                                            "已同步课表与通知 · ${maskAccount(xuexitong.phone)}"
                                        } else {
                                            "自动汇总作业、签到与课程公告（课表共用此账号）"
                                        }
                                        SettingsSection {
                                            SetupAccountItem(
                                                "喝水账户",
                                                Icons.Outlined.WaterDrop,
                                                if (water.loggedIn) maskAccount(water.phone) else "慧生活798 · 连接饮水设备",
                                                water.loggedIn,
                                                water.busy,
                                            ) { service = "water" }
                                            SettingsDivider()
                                            SetupAccountItem(
                                                "易校园（洗澡 / 一卡通）",
                                                Icons.Outlined.Shower,
                                                yxySubtitle,
                                                yxyConfigured,
                                                life.yixiaoyuanBusy,
                                                allowReopen = true,
                                            ) { service = "shower" }
                                            SettingsDivider()
                                            SetupAccountItem(
                                                "超星教务（成绩 / 考试 / 空教室）",
                                                Icons.Outlined.School,
                                                if (main.jwConnected) "已连接教务接口（会话加密保存）" else "使用教务学号密码直连登录，用于成绩、考试与空教室",
                                                main.jwConnected,
                                                false,
                                            ) { showJwWebLogin = true }
                                            SettingsDivider()
                                            SetupAccountItem(
                                                "学习通账户",
                                                Icons.Outlined.Notifications,
                                                xxSubtitle,
                                                xxConnected,
                                                xuexitong.busy,
                                            ) { service = "xuexitong" }
                                        }
                                    }
                                    WelcomeStep.PREFERENCES -> {
                                        val termWeeksSubtitle = when {
                                            main.settings.termWeeksManual -> "已手动调整"
                                            main.schedule != null -> "已按课表有课周数自动识别"
                                            else -> "同步课表后将自动识别有课周数"
                                        }
                                        SettingsSection {
                                            SettingsSwitchItem("显示周末", Icons.Outlined.DateRange,
                                                "显示周六、周日", main.settings.showWeekend,
                                                mainModel::setShowWeekend, divider = true, subtitleColor = InkMid)
                                            SettingsSwitchItem("自动更新课程表", Icons.Outlined.Sync,
                                                "启动时同步，每 ${SyncScheduler.PERIOD_HOURS} 小时后台更新",
                                                main.settings.autoSync, mainModel::setAutoSync, subtitleColor = InkMid)
                                        }
                                        SettingsSection("学期与作息") {
                                            val term = main.term ?: TermCalculator.autoTerm()
                                            SettingsItem("第一周第一天", Icons.Outlined.Event,
                                                value = mainModel.termStartOf(main.settings, term).toString(), divider = true, valueColor = InkMid) { editor = "date" }
                                            SettingsItem("学期周数", Icons.Outlined.CalendarMonth,
                                                subtitle = termWeeksSubtitle,
                                                value = "${main.settings.termWeeks} 周", divider = true, valueColor = InkMid) { editor = "weeks" }
                                            SettingsItem("上课时间", Icons.Outlined.Schedule,
                                                "查看并调整每一节课的时间") { editor = "periods" }
                                        }
                                        if (!main.loggedIn) {
                                            SettingsHint("完成登录并开启自动更新后，应用将在后台定期同步最新课表。")
                                        }
                                    }
                                    WelcomeStep.READY -> {
                                        val xxOk = xuexitong.loggedIn && !xuexitong.sessionExpired
                                        val yxy = life.yixiaoyuan
                                        val yxyOk = life.yixiaoyuanSignedIn || yxy.hasCredential || yxy.showerCode.isNotBlank()
                                        val yxyStatus = when {
                                            life.yixiaoyuanSignedIn -> "已登录"
                                            yxy.showerCode.isNotBlank() || yxy.hasCredential -> "已配用水码"
                                            else -> "可稍后配置"
                                        }
                                        val courseStatus = when {
                                            main.schedule != null -> "已同步 ${main.schedule!!.courses.size} 节"
                                            main.loggedIn -> "已登录"
                                            else -> "稍后登录"
                                        }
                                        ReadyMark()
                                        SettingsSection("账户连接") {
                                            SetupStatus("课程表", courseStatus, main.loggedIn || main.schedule != null)
                                            SetupStatus("学习通通知", if (xxOk) "已连接" else "稍后登录", xxOk)
                                            SetupStatus("超星教务（成绩 / 考试）", if (main.jwConnected) "已连接" else "可稍后登录", main.jwConnected)
                                            SetupStatus("喝水账户", if (water.loggedIn) "已登录" else "稍后登录", water.loggedIn)
                                            SetupStatus("易校园（洗澡 / 一卡通）", yxyStatus, yxyOk)
                                        }
                                        SettingsSection("课表偏好") {
                                            SettingsValue("显示周末", if (main.settings.showWeekend) "开启" else "关闭", InkMid)
                                            SettingsValue("自动更新", if (main.settings.autoSync) "开启" else "关闭", InkMid)
                                            SettingsValue("学期周数", "${main.settings.termWeeks} 周", InkMid)
                                        }
                                        SettingsHint("喝水设备与易校园洗澡用水码、一卡通余额可在「生活」页配置与查看；学习通通知可在首页与「学习」页查看。")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        ProfileEditors(editor, main.settings,
            mainModel.termStartOf(main.settings, main.term ?: TermCalculator.autoTerm()), mainModel) { editor = null }
        if (showJwWebLogin) {
            ChaoxingDirectLoginDialog(
                baseUrl = main.settings.baseUrl,
                initialUsername = mainModel.savedJwUsername(),
                onDismiss = { showJwWebLogin = false },
                onLoginSuccess = { user, pwd, cookies ->
                    showJwWebLogin = false
                    mainModel.onChaoxingDirectLoginSuccess(user, pwd, cookies)
                },
            )
        }
    }
}

@Composable
private fun WelcomeLayout(
    step: WelcomeStep, onBack: (() -> Unit)?, backEnabled: Boolean,
    nextLabel: String, nextEnabled: Boolean, saving: Boolean, onNext: () -> Unit,
    skip: (() -> Unit)?, error: String?, servicePage: Boolean, content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, tween(if (pressed) 80 else 160), label = "welcome-button")
    Column(Modifier.fillMaxSize().background(appBackground())
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (onBack != null) IconButton(onClick = onBack, enabled = backEnabled) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一步", tint = InkDark)
                }
            }
            Text("初次设置", color = InkMid, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${step.ordinal + 1} / ${WelcomeStep.entries.size}", color = InkMid, fontSize = 12.sp)
        }
        WelcomeProgressBar(step)
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp)) {
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp))
            Button(onClick = onNext, enabled = nextEnabled, interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().height(52.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)) {
                if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else {
                    AnimatedContent(nextLabel, transitionSpec = {
                        fadeIn(tween(140)) togetherWith fadeOut(tween(100))
                    }, label = "welcome-action-label") { label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(10.dp))
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                if (skip != null) TextButton(onClick = skip) { Text("暂时跳过", color = InkMid) }
                else {
                    val hint = when {
                        servicePage -> "可以稍后登录"
                        step == WelcomeStep.SERVICES -> "可跳过，之后也能添加"
                        step == WelcomeStep.READY -> "之后可在“我的”中修改设置"
                        else -> null
                    }
                    if (hint != null) Text(hint, color = InkMid, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun WelcomeProgressBar(step: WelcomeStep) {
    val position by animateFloatAsState(step.ordinal.toFloat(), tween(360, easing = FastOutSlowInEasing), label = "welcome-step-marker")
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = (maxWidth - 24.dp) / WelcomeStep.entries.size
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WelcomeStep.entries.forEach { _ ->
                    Box(Modifier.width(width).height(3.dp).clip(CircleShape).background(Hairline))
                }
            }
            Box(Modifier.offset(x = (width + 6.dp) * position).width(width).height(3.dp)
                .clip(CircleShape).background(BrandGreen))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WelcomeStep.entries.forEach { item -> Text(item.label, Modifier.weight(1f),
                color = if (item == step) BrandGreen else InkMid, textAlign = TextAlign.Center,
                fontSize = 12.sp, fontWeight = if (item == step) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
private fun WelcomeBody(step: WelcomeStep, title: String, subtitle: String, isService: Boolean,
    content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val centered = !isService && step == WelcomeStep.INTRO
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
            .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, if (centered) Alignment.CenterVertically else Alignment.Top)) {
            if (step != WelcomeStep.INTRO) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, color = InkDark, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    if (step == WelcomeStep.SERVICES && !isService) Text("可选", color = BrandGreen, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(BrandGreenSoft).padding(horizontal = 10.dp, vertical = 5.dp))
                }
                Text(subtitle, color = InkMid, fontSize = 14.sp, lineHeight = 22.sp)
            }
            content()
        }
    }
}

@Composable
private fun WelcomeIntro() {
    IntroReveal(0, scale = true) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_app_logo), "牧野生活 App 图标", modifier = Modifier.size(128.dp))
        }
    }
    IntroReveal(70) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(WelcomeStep.INTRO.title, color = InkDark, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(WelcomeStep.INTRO.subtitle, color = InkMid, fontSize = 14.sp, lineHeight = 22.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
    IntroReveal(140) {
        SettingsSection {
            WelcomeFeature("课程", "超星教务同步、日程与郑州天气", Icons.Outlined.CalendarMonth)
            WelcomeFeature("学习", "学习通待办、成绩 GPA 与考试便签", Icons.Outlined.School)
            WelcomeFeature("生活", "喝水 798、易校园用水码、快递码与校网", Icons.Outlined.WaterDrop)
        }
    }
}

@Composable
private fun IntroReveal(delayMillis: Long, scale: Boolean = false, content: @Composable () -> Unit) {
    var appeared by rememberSaveable { mutableStateOf(false) }
    val reveal = remember { Animatable(if (appeared) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!appeared) {
            appeared = true
            delay(delayMillis)
            reveal.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        }
    }
    Box(Modifier.fillMaxWidth().graphicsLayer {
        alpha = reveal.value
        translationY = (1f - reveal.value) * 16.dp.toPx()
        scaleX = if (scale) .96f + .04f * reveal.value else 1f
        scaleY = scaleX
    }) { content() }
}

@Composable
private fun WelcomeFeature(title: String, detail: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = BrandGreen, modifier = Modifier.size(22.dp))
        Text(title, color = InkDark, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(detail, color = InkMid, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TeachingSetup(state: MainUiState, model: MainViewModel, xuexitongConnected: Boolean) {
    when {
        state.firstFetchActive -> LifeCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), color = BrandGreen, strokeWidth = 2.dp)
                Text("正在登录并同步课程", color = InkDark, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(12.dp))
            Text(state.fetchLogs.lastOrNull() ?: "正在连接课表服务…", color = InkMid, fontSize = 13.sp, lineHeight = 20.sp)
        }
        state.loggedIn && !state.firstFetchFailed -> {
            val heading = when {
                xuexitongConnected && state.jwConnected -> "学习通与超星教务已连接"
                xuexitongConnected -> "学习通已连接（课表与通知共用）"
                state.jwConnected -> "超星教务已连接"
                else -> "课表账户已保存"
            }
            val accountLabel = state.schedule?.student?.name?.takeIf { it.isNotBlank() }
                ?: maskAccount(state.settings.account).ifBlank { "网页会话" }
            LifeCard {
                Icon(Icons.Outlined.CheckCircle, null, tint = BrandGreen, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Text(heading, color = InkDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(accountLabel, color = InkMid, modifier = Modifier.padding(top = 6.dp))
                Text(if (state.schedule != null) "课表已同步，点击下一步继续设置。" else "稍后可在“我的 → 同步与数据”中获取课程。",
                    color = InkGray, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
                else -> {
            var showWebLogin by rememberSaveable { mutableStateOf(false) }
            if (state.firstFetchFailed) LifeCard {
                Text("课表暂未获取成功", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                Text(state.fetchLogs.lastOrNull() ?: "请检查网络和教务账户后重试", color = InkMid, fontSize = 12.sp,
                    lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
                Row {
                    TextButton(onClick = model::retryFirstFetch) { Text("重试同步") }
                    TextButton(onClick = model::skipFirstFetch) { Text("稍后同步") }
                }
            }
            TeachingLoginForm(model)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = { showWebLogin = true }) {
                    Text("遇到验证码或统一认证？切换网页登录", fontSize = 12.sp, color = InkMid)
                }
            }
            SettingsHint("超星教务与学习通均支持纯接口登录，全部凭据与 Cookie 仅加密保存在本机。")
            if (showWebLogin) {
                ChaoxingWebLoginDialog(
                    baseUrl = state.settings.baseUrl,
                    onDismiss = { showWebLogin = false },
                    onCapturedCookies = { cookies ->
                        showWebLogin = false
                        model.onChaoxingWebLoginCaptured(cookies)
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupAccountItem(title: String, icon: ImageVector, subtitle: String,
    connected: Boolean, busy: Boolean, allowReopen: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = (!connected || allowReopen) && !busy, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = BrandGreen, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = InkDark, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = InkMid, fontSize = 13.sp, lineHeight = 20.sp)
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else if (connected) Icon(Icons.Outlined.CheckCircle, "已登录", tint = BrandGreen, modifier = Modifier.size(22.dp))
        else Text("登录", color = BrandGreen, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ReadyMark() {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_app_logo), null, modifier = Modifier.size(64.dp))
            Icon(Icons.Outlined.CheckCircle, "设置完成", tint = BrandGreen,
                modifier = Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(CardWhite))
        }
    }
}

@Composable
private fun SetupStatus(title: String, status: String, connected: Boolean) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = InkDark, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(status, color = if (connected) BrandGreen else InkMid, fontSize = 13.sp)
    }
}
