package com.fengkao.timetable.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fengkao.timetable.BuildConfig
import com.fengkao.timetable.R
import com.fengkao.timetable.ui.MainUiState
import com.fengkao.timetable.ui.MainViewModel
import com.fengkao.timetable.ui.water.*
import com.fengkao.timetable.ui.theme.*
import com.hmu.muye.data.campus.YixiaoyuanState
import com.hmu.muye.data.yixiaoyuan.WaterCode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun LoadingHint(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BrandGreen)
        Text(text, color = InkGray, fontSize = 12.sp)
    }
}

@Composable
internal fun SyncSummary(state: MainUiState, onSync: () -> Unit) {
    val busy = state.syncing || state.backgroundSyncing
    val error = state.syncMessage ?: state.backgroundSyncError
    val status = when {
        busy -> "同步中"
        error != null -> "同步失败"
        state.settings.lastSyncAt > 0 -> "已同步"
        else -> "未同步"
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BrandGreenSoft).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Sync, null, tint = BrandGreen, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text("当前状态：$status", color = InkDark, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val at = state.settings.lastSyncAt
            val time = if (at > 0) Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)) else "从未同步"
            Text("上次同步：$time", color = InkGray, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        }
        TextButton(onClick = onSync, enabled = !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BrandGreen)
            else Text("立即同步", fontSize = 12.sp)
        }
    }
    error?.takeUnless { busy }?.let { SettingsHint(it, error = true) }
}

@Composable
internal fun WaterAccountPage(state: WaterUiState, model: WaterViewModel, confirm: (String) -> Unit) {
    if (state.loggedIn) {
        ServiceAccountDetails(maskAccount(state.phone), state.busy, "喝水", { confirm("water-login") }, { confirm("water") })
    } else WaterLogin(state, model)
    if (state.busy) LoadingHint("正在处理喝水账户…")
    state.message?.let { SettingsHint(it, state.error) }
}

@Composable
internal fun YixiaoyuanAccountPage(
    state: YixiaoyuanState,
    signedIn: Boolean,
    savedMobile: String,
    busy: Boolean,
    waterCode: WaterCode?,
    statusMessage: String?,
    statusError: Boolean,
    onOpenApp: () -> Unit,
    onLogin: (mobile: String, smsCode: String) -> Unit,
    onLogout: () -> Unit,
    onRefreshBalance: () -> Unit,
    onRefreshWaterCode: (shiroJid: String) -> Unit,
    onToggleShowBalance: (Boolean) -> Unit,
    onSave: (ymId: String, shiroJid: String?, balanceText: String, thresholdText: String, showerCode: String) -> Unit,
) {
    var mobile by rememberSaveable(savedMobile) { mutableStateOf(savedMobile) }
    var smsCode by rememberSaveable { mutableStateOf("") }
    var shiro by rememberSaveable { mutableStateOf("") }
    var threshold by rememberSaveable(state.lowBalanceThresholdYuan) {
        mutableStateOf(state.lowBalanceThresholdYuan.toString())
    }

    SettingsSection("显示偏好") {
        SettingsSwitchItem(
            title = "在「生活」页显示一卡通余额",
            icon = Icons.Outlined.AccountBalanceWallet,
            subtitle = "不往易校园充钱时可关闭",
            checked = state.showBalanceCard,
            onChange = onToggleShowBalance,
        )
    }

    // 卡片 1：一卡通余额与短信验证码登录
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("一卡通余额 · 短信验证码登录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
        if (signedIn) {
            Text(
                "已登录 ${maskAccount(savedMobile.ifBlank { state.ymId })} · 余额：${state.cardBalanceYuan?.let { "¥ %.2f".format(it) } ?: "待刷新"}",
                fontSize = 13.sp,
                color = InkMid,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onRefreshBalance,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                ) {
                    Text(if (busy) "刷新中…" else "刷新余额")
                }
                OutlinedButton(
                    onClick = onLogout,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("退出账号")
                }
            }
        } else {
            Text(
                "先打开易校园 App 点「获取验证码」，再回到此处填入手机号与短信验证码完成登录，即可实时刷新一卡通余额。",
                fontSize = 12.sp,
                color = InkGray,
                lineHeight = 18.sp,
            )
            OutlinedTextField(
                value = mobile,
                onValueChange = { mobile = it.filter(Char::isDigit).take(11) },
                label = { Text("手机号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = smsCode,
                onValueChange = { smsCode = it.filter(Char::isDigit).take(8) },
                label = { Text("短信验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onLogin(mobile, smsCode) },
                enabled = !busy && mobile.isNotBlank() && smsCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
            ) {
                Text(if (busy) "登录中…" else "短信验证码登录", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onOpenApp,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("打开易校园 App 获取验证码", color = BrandGreen)
            }
        }
    }

    // 卡片 2：浴室 8 位动态用水码（shiroJID）
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("浴室 8 位动态用水码", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
        val formatted = state.formattedShowerCode()
        if (formatted.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrandGreenSoft)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    formatted,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = BrandGreen,
                )
            }
            waterCode?.let {
                Text(
                    if (it.longTerm) "长期有效 · 当日剩余 ${it.remainTimes} 次"
                    else "有效 ${it.validSeconds / 60} 分钟 · 当日剩余 ${it.remainTimes} 次",
                    fontSize = 11.sp,
                    color = InkGray,
                )
            }
        }
        OutlinedTextField(
            value = shiro,
            onValueChange = { shiro = it },
            label = {
                Text(
                    if (state.hasCredential) "shiroJID Cookie（已配置，留空保持不变）"
                    else "shiroJID Cookie（填一次即可刷新 8 位用水码）",
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val draft = shiro
                    shiro = ""
                    onRefreshWaterCode(draft)
                },
                enabled = !busy && (state.hasCredential || shiro.isNotBlank()),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
            ) {
                Text(if (busy) "刷新中…" else "刷新 8 位用水码")
            }
            OutlinedButton(
                onClick = onOpenApp,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("打开易校园 App")
            }
        }
    }

    // 卡片 3：低余额阈值（仅当开启余额展示时展示）
    if (state.showBalanceCard) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardWhite)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = threshold,
                onValueChange = { threshold = it },
                label = { Text("低余额提醒阈值（元，默认 10）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { onSave(state.ymId, null, "", threshold, "") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("保存提醒阈值", color = BrandGreen, fontWeight = FontWeight.Medium)
            }
        }
    }

    statusMessage?.let { SettingsHint(it, error = statusError) }
}

@Composable
private fun ServiceAccountDetails(account: String, busy: Boolean, name: String, relogin: () -> Unit, logout: () -> Unit) {
    SettingsSection {
        SettingsValue("账号", account); SettingsValue("账户状态", "已登录")
        SettingsItem("重新登录", Icons.AutoMirrored.Outlined.Login, enabled = !busy, onClick = relogin)
    }
    SettingsSection(danger = true) {
        SettingsItem("退出${name}账户", Icons.AutoMirrored.Outlined.Logout, enabled = !busy, danger = true, onClick = logout)
    }
}

@Composable
internal fun AboutPage(
    openLicenses: () -> Unit,
    openUpdateSettings: () -> Unit,
    state: MainUiState,
    onCheckUpdate: () -> Unit,
    onOpenUpdate: (com.fengkao.timetable.data.update.UpdateInfo) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource(R.drawable.ic_launcher_foreground), "牧野生活图标", Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(BrandGreenSoft))
        Text("牧野生活 (HMU)", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = InkDark)
        Text("河南牧业经济学院 · 版本 ${BuildConfig.VERSION_NAME}", color = InkGray, fontSize = 13.sp)
    }
    com.fengkao.timetable.ui.update.UpdateRow(
        checking = state.updateChecking,
        latest = state.updateAvailable,
        hint = state.updateHint,
        hintError = state.updateHintError,
        onCheck = {
            // 已经查到新版本时再点一次，直接启动应用内更新或唤醒弹窗
            val found = state.updateAvailable
            if (found != null) onOpenUpdate(found) else onCheckUpdate()
        },
    )
    val repoText = state.settings.updateRepo.ifBlank { BuildConfig.UPDATE_REPO }.ifBlank { "未配置" }
    SettingsSection {
        SettingsItem("更新源与加速设置", Icons.Outlined.CloudDownload, value = repoText, divider = true, onClick = openUpdateSettings)
        SettingsItem("开源许可", Icons.Outlined.Description, onClick = openLicenses)
    }
    SettingsHint("基于 MIT 开源项目 f3ngka0/youwu 二次开发。教务直连 hnuahe.jw.chaoxing.com；全部账号密码、Cookie 与取件码仅加密保存在本机，无自建后端。")
}

@Composable
internal fun LicensesPage(model: ProfileInfoViewModel) {
    val state by model.licenses.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.loadLicenses() }
    if (state.loading) LoadingHint("正在读取开源许可…")
    state.error?.let {
        SettingsHint(it, error = true)
        TextButton(onClick = model::loadLicenses) { Text("重试") }
    }
    if (state.text.isNotBlank()) Text(state.text, fontSize = 12.sp, lineHeight = 19.sp, color = InkMid)
    if (!state.loading && state.error == null && state.text.isBlank()) SettingsHint("暂无许可信息")
}

@Composable
internal fun CourseManagement(state: MainUiState, model: MainViewModel) {
    val courses = state.schedule?.courses.orEmpty().sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }))
    val custom = state.settings.customCourses
    val hidden = state.settings.hiddenCourses
    var pendingDelete by remember { mutableStateOf<com.fengkao.timetable.data.model.CustomCourse?>(null) }
    SettingsHint("教务课程隐藏仅影响本地显示，不影响同步数据")
    if (hidden.isNotEmpty()) TextButton(onClick = model::restoreAllCourses) { Text("恢复全部隐藏课程") }
    if (courses.isEmpty() && custom.isEmpty()) SettingsHint(if (state.syncing || state.backgroundSyncing) "正在同步课程…" else "暂无课程数据，可先同步或在首页添加课程")
    if (courses.isNotEmpty()) SettingsSection("教务课程 · ${courses.size} 项") {
        courses.forEach { course ->
            val isHidden = course.stableKey in hidden
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(course.name, color = if (isHidden) InkGray else InkDark, fontSize = 14.sp,
                        textDecoration = if (isHidden) TextDecoration.LineThrough else null)
                    val day = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(course.dayOfWeek - 1) { "?" }
                    Text("周$day 第${course.startPeriod}-${course.endPeriod}节 · ${course.place.ifBlank { "待定" }} · ${course.teacher.ifBlank { "—" }} · ${course.weeksText}",
                        color = InkGray, fontSize = 11.sp, lineHeight = 16.sp)
                }
                TextButton(onClick = { model.toggleCourseHidden(course.stableKey) }) { Text(if (isHidden) "显示" else "隐藏") }
            }
        }
    }
    if (custom.isNotEmpty()) SettingsSection("手动添加 · ${custom.size} 项") {
        custom.forEach { course ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(course.name, color = InkDark, fontSize = 14.sp)
                    Text("第${course.startPeriod}-${course.endPeriod}节 · ${course.startWeek}-${course.endWeek}周", color = InkGray, fontSize = 11.sp)
                }
                TextButton(onClick = { pendingDelete = course }) { Text("删除", color = DangerFg) }
            }
        }
    }
    pendingDelete?.let { course ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除手动课程？") },
            text = { Text(course.name) }, confirmButton = { TextButton(onClick = { model.removeCustomCourse(course); pendingDelete = null }) { Text("删除", color = DangerFg) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }, containerColor = CardWhite)
    }
}
