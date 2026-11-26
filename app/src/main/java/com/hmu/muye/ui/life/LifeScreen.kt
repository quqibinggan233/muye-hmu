package com.hmu.muye.ui.life

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fengkao.timetable.ui.navigation.LocalPageActive
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
import com.fengkao.timetable.data.water.WaterDevice
import com.fengkao.timetable.ui.water.ServiceMessage
import com.fengkao.timetable.ui.water.WaterScreen
import com.fengkao.timetable.ui.water.WaterUiState
import com.fengkao.timetable.ui.water.WaterViewModel
import com.hmu.muye.data.campus.HmuCampus
import com.hmu.muye.data.campus.HmuCampusDirectory
import com.hmu.muye.data.parcel.IdentityCodeShortcuts
import com.hmu.muye.data.parcel.ParcelItem
import com.hmu.muye.data.parcel.ParcelNotificationListener
import com.hmu.muye.data.parcel.ParcelSmsScanner
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * 「生活」Tab —— 方案 A（一屏分层宫格，严格不含宿舍电费）：
 * 1. 顶部校区标识 + 龙子湖校园网 Portal 认证胶囊
 * 2. 易校园一卡通余额条（含低余额阈值本地提醒）
 * 3. 动态待取快递卡（有件展示取件码/已取；无件折叠为单行；内置淘宝/拼多多身份码深链 + 通知/剪贴板/可选短信识别）
 * 4. 2×2 核心设备瓷砖（慧生活 798 喝水 / 易校园洗澡用水码 / U净+海鸟吹风机 / 乐仔生活洗衣机）
 * 5. 校内速查与快捷入口（校历作息 / 校区速查 / 图书馆 / 自定义快捷入口）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeScreen(
    lifeModel: LifeViewModel,
    waterModel: WaterViewModel,
) {
    val state by lifeModel.state.collectAsStateWithLifecycle()
    val water by waterModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val active = LocalPageActive.current

    LifecycleResumeEffect(active) {
        if (active) {
            lifeModel.refreshPermissionsAndProbePortal()
            waterModel.enter()
        }
        onPauseOrDispose { }
    }

    var showCampusPicker by rememberSaveable { mutableStateOf(false) }
    var showPortalDialog by rememberSaveable { mutableStateOf(false) }
    var showYxyDialog by rememberSaveable { mutableStateOf(false) }
    var showWaterSheet by rememberSaveable { mutableStateOf(false) }
    var showParcelAddDialog by rememberSaveable { mutableStateOf(false) }
    var showParcelRulesDialog by rememberSaveable { mutableStateOf(false) }
    var showUjingDialog by rememberSaveable { mutableStateOf(false) }
    var showHainiaoSheet by rememberSaveable { mutableStateOf(false) }
    var showCalendarDialog by rememberSaveable { mutableStateOf(false) }
    var showPlacesDialog by rememberSaveable { mutableStateOf(false) }
    var showShortcutsSheet by rememberSaveable { mutableStateOf(false) }
    var pendingScannedQr by rememberSaveable { mutableStateOf<String?>(null) }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) lifeModel.scanRecentSms()
    }

    fun triggerSmsScan() {
        if (ParcelSmsScanner.hasPermission(context)) {
            lifeModel.scanRecentSms()
        } else {
            smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
        }
    }

    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        if (raw.isNotBlank()) {
            pendingScannedQr = raw
            IdentityCodeShortcuts.openScannedPaymentOrWeb(context, raw)
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            qrScanner.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("扫描合肥海鸟吹风机二维码")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        }
    }

    fun startHainiaoScan() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            qrScanner.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("扫描合肥海鸟吹风机二维码")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val ujingScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        if (raw.isNotBlank()) lifeModel.scanUjingQr(raw)
    }
    val ujingCameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            ujingScanner.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("扫描宿舍 U 净设备二维码")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        }
    }

    fun startUjingScan() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            ujingScanner.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("扫描宿舍 U 净设备二维码")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        } else {
            ujingCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(appBackground())
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. 顶部：校区切换 + 牧野生活标题 + 校园网 Portal 胶囊
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    "HMU · ${state.campus.label} ▾",
                    fontSize = 11.sp,
                    color = InkGray,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showCampusPicker = true }
                        .padding(vertical = 2.dp),
                )
                Text("牧野生活", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = InkDark)
            }
            PortalStatusPill(
                online = state.portalOnline,
                busy = state.portalBusy,
                onClick = { showPortalDialog = true },
            )
        }

        val visibleMessage = state.statusMessage?.takeUnless { msg ->
            !state.yixiaoyuan.showBalanceCard && msg.contains("余额")
        }
        Box(Modifier.clickable(enabled = visibleMessage != null) { lifeModel.clearMessage() }) {
            ServiceMessage(visibleMessage, state.statusError)
        }

        // 2. 易校园一卡通余额条（可选显示，仅一卡通，不含电费）
        if (state.yixiaoyuan.showBalanceCard) {
            YixiaoyuanBalanceStrip(
                yxy = state.yixiaoyuan,
                signedIn = state.yixiaoyuanSignedIn,
                busy = state.yixiaoyuanBusy,
                onRefresh = lifeModel::refreshCardBalance,
                onClick = { showYxyDialog = true },
            )
        }

        // 3. 动态待取快递卡（有件展示、无件自动折叠）
        ParcelCard(
            pending = state.pendingParcels,
            listenerEnabled = state.notificationListenerEnabled,
            onMarkPicked = lifeModel::markParcelPickedUp,
            onOpenTaobaoCode = { IdentityCodeShortcuts.openTaobaoIdentityCode(context) },
            onOpenPddCode = { IdentityCodeShortcuts.openPinduoduo(context) },
            onAddManual = { showParcelAddDialog = true },
            onScanSms = ::triggerSmsScan,
            onOpenRules = { showParcelRulesDialog = true },
        )

        // 4. 2×2 核心设备瓷砖
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WaterTile(
                water = water,
                onSelectDevice = waterModel::select,
                onOneTapStart = {
                    if (water.loggedIn && water.device != null) waterModel.start()
                    else showWaterSheet = true
                },
                onOpenManage = { showWaterSheet = true },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ShowerCodeTile(
                yxy = state.yixiaoyuan,
                signedIn = state.yixiaoyuanSignedIn,
                waterCode = state.waterCodeInfo,
                busy = state.yixiaoyuanBusy,
                feedback = state.showerFeedback,
                feedbackError = state.showerFeedbackError,
                onRefresh = {
                    lifeModel.refreshShowerCode(forceGenerate = state.yixiaoyuan.showerCode.isNotBlank())
                },
                onCancel = lifeModel::cancelShowerCode,
                onConfigure = { showYxyDialog = true },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HairdryerTile(
                bookmarkCount = state.hairdryerBookmarks.size,
                ujingStoreName = state.ujingSelectedStore?.name,
                onOpenUjing = { showUjingDialog = true },
                onOpenHainiao = { showHainiaoSheet = true },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            LaundryTile(
                onOpenLezai = { IdentityCodeShortcuts.openLezai(context) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        // 5. 校内速查与快捷入口
        LifeCardContainer {
            Text("校内速查与快捷入口", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkGray)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickPillButton("校历作息", Modifier.weight(1f)) { showCalendarDialog = true }
                QuickPillButton("校区速查", Modifier.weight(1f)) { showPlacesDialog = true }
                QuickPillButton("图书馆", Modifier.weight(1f)) { IdentityCodeShortcuts.openWeChat(context) }
                QuickPillButton("+ 快捷", Modifier.weight(1f)) { showShortcutsSheet = true }
            }
            if (state.customShortcuts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                state.customShortcuts.forEach { sc ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(softSurface())
                            .clickable { IdentityCodeShortcuts.openUrl(context, sc.targetUri) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(sc.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
                            Text(sc.subtitle, fontSize = 11.sp, color = InkGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("打开 →", fontSize = 12.sp, color = BrandGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // ==================== 弹窗与二级抽屉 ====================

    if (showCampusPicker) {
        AlertDialog(
            onDismissRequest = { showCampusPicker = false },
            containerColor = CardWhite,
            title = { Text("选择所在校区") },
            text = {
                Column {
                    HmuCampus.entries.forEach { campus ->
                        TextButton(
                            onClick = {
                                lifeModel.setCampus(campus)
                                showCampusPicker = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (campus == state.campus) "✓ ${campus.label}" else campus.label,
                                color = if (campus == state.campus) BrandGreen else InkDark,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCampusPicker = false }) { Text("关闭") } },
        )
    }

    if (showPortalDialog) {
        PortalConfigDialog(
            initialUrl = state.portal.authUrl,
            initialAccount = lifeModel.portalSavedAccount(),
            initialPassword = lifeModel.portalSavedPassword(),
            initialIsp = state.portal.ispSuffix,
            initialAuto = state.portal.autoAuth,
            online = state.portalOnline,
            busy = state.portalBusy,
            onDismiss = { showPortalDialog = false },
            onSave = { url, acc, pwd, isp, auto ->
                lifeModel.savePortalConfig(url, acc, pwd, isp, auto)
            },
            onAuthenticate = { lifeModel.authenticatePortal() },
        )
    }

    if (showYxyDialog) {
        YixiaoyuanConfigDialog(
            state = state.yixiaoyuan,
            signedIn = state.yixiaoyuanSignedIn,
            savedMobile = state.yixiaoyuanMobile,
            busy = state.yixiaoyuanBusy,
            waterCode = state.waterCodeInfo,
            statusMessage = state.statusMessage,
            statusError = state.statusError,
            onDismiss = { showYxyDialog = false },
            onOpenApp = { IdentityCodeShortcuts.openYixiaoyuan(context) },
            onLogin = lifeModel::loginYixiaoyuan,
            onLoginByPassword = lifeModel::loginYixiaoyuanByPassword,
            onLogout = lifeModel::logoutYixiaoyuan,
            onRefreshBalance = lifeModel::refreshCardBalance,
            onRefreshWaterCode = lifeModel::saveShiroJidAndRefresh,
            onGenerateWaterCode = { lifeModel.refreshShowerCode(forceGenerate = true) },
            onCancelWaterCode = lifeModel::cancelShowerCode,
            onToggleShowBalance = lifeModel::setShowYixiaoyuanBalance,
            onSave = { ymId, shiro, bal, threshold, code ->
                lifeModel.saveYixiaoyuanConfig(ymId, shiro, bal, threshold, code)
                showYxyDialog = false
            },
        )
    }

    if (showWaterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showWaterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SheetColor,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    "喝水设备管理（慧生活 798 · 3.1.10 协议 · 余额与账单）",
                    fontSize = 12.sp,
                    color = InkGray,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Box(Modifier.height(540.dp)) {
                    WaterScreen(waterModel, embedded = true)
                }
            }
        }
    }

    if (showParcelAddDialog) {
        ParcelIngestDialog(
            onDismiss = { showParcelAddDialog = false },
            onPasteClipboard = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            },
            onIngestText = { text ->
                if (lifeModel.ingestClipboardOrRawText(text)) showParcelAddDialog = false
            },
            onManualAdd = { code, station, carrier ->
                lifeModel.addManualParcel(code, station, carrier)
                showParcelAddDialog = false
            },
        )
    }

    if (showParcelRulesDialog) {
        ParcelRulesDialog(
            listenerEnabled = state.notificationListenerEnabled,
            rules = state.customParcelRules,
            onDismiss = { showParcelRulesDialog = false },
            onOpenNotificationSettings = { ParcelNotificationListener.openSettings(context) },
            onAddRule = lifeModel::addCustomParcelRule,
            onRemoveRule = lifeModel::removeCustomParcelRule,
        )
    }

    if (showUjingDialog) {
        var smsCooldown by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(smsCooldown) {
            if (smsCooldown > 0) {
                kotlinx.coroutines.delay(1000L)
                smsCooldown -= 1
            }
        }
        UjingSheet(
            state = state,
            smsCooldown = smsCooldown,
            onDismiss = { showUjingDialog = false },
            onSendCaptcha = { phone -> lifeModel.sendUjingCaptcha(phone) { smsCooldown = 60 } },
            onLogin = { phone, code -> lifeModel.loginUjing(phone, code) },
            onSaveJwt = lifeModel::saveUjingJwt,
            onLogout = lifeModel::logoutUjing,
            onKeywordChange = lifeModel::updateUjingKeyword,
            onSearchStores = { lifeModel.searchUjingStores() },
            onSelectStore = lifeModel::selectUjingStore,
            onRefreshStore = { lifeModel.refreshUjingStore() },
            onScanQr = ::startUjingScan,
            onOpenWeChat = { IdentityCodeShortcuts.openWeChat(context) },
        )
    }

    if (showHainiaoSheet) {
        HainiaoSheet(
            bookmarks = state.hairdryerBookmarks,
            pendingScannedQr = pendingScannedQr,
            onDismiss = {
                pendingScannedQr = null
                showHainiaoSheet = false
            },
            onStartScan = ::startHainiaoScan,
            onSaveBookmark = { label, payload ->
                lifeModel.addHairdryerBookmark(label, payload)
                pendingScannedQr = null
            },
            onOpenBookmark = { payload -> IdentityCodeShortcuts.openScannedPaymentOrWeb(context, payload) },
            onRemoveBookmark = lifeModel::removeHairdryerBookmark,
        )
    }

    if (showCalendarDialog) {
        DirectoryDialog(
            title = "校历与作息速查",
            entries = HmuCampusDirectory.CALENDAR_NOTES,
            onDismiss = { showCalendarDialog = false },
            onOpenUrl = { IdentityCodeShortcuts.openUrl(context, it) },
        )
    }

    if (showPlacesDialog) {
        DirectoryDialog(
            title = "龙子湖生活速查",
            entries = HmuCampusDirectory.LONGZIHU_PLACES,
            onDismiss = { showPlacesDialog = false },
            onOpenUrl = { IdentityCodeShortcuts.openUrl(context, it) },
        )
    }

    if (showShortcutsSheet) {
        ShortcutsSheet(
            customShortcuts = state.customShortcuts,
            onDismiss = { showShortcutsSheet = false },
            onAdd = lifeModel::addQuickShortcut,
            onRemove = lifeModel::removeQuickShortcut,
            onOpen = { IdentityCodeShortcuts.openUrl(context, it) },
        )
    }
}

// ==================== 核心卡片组件 ====================

@Composable
private fun PortalStatusPill(online: Boolean?, busy: Boolean, onClick: () -> Unit) {
    val bg = when {
        online == true -> if (IsDark) Color(0x2810B981) else Color(0xFFE7F6EE)
        online == false -> if (IsDark) Color(0x28F59E0B) else Color(0xFFFEF3E2)
        else -> softSurface()
    }
    val fg = when {
        online == true -> if (IsDark) Color(0xFF6EE7B7) else Color(0xFF137345)
        online == false -> if (IsDark) Color(0xFFFCD34D) else Color(0xFFB45309)
        else -> InkMid
    }
    val label = when {
        busy -> "认证中…"
        online == true -> "校园网已认证"
        online == false -> "点我连校网"
        else -> "校园网配置"
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun YixiaoyuanBalanceStrip(
    yxy: com.hmu.muye.data.campus.YixiaoyuanState,
    signedIn: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardWhite)
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(BrandGreenSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text("卡", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BrandGreen)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("易校园一卡通余额", fontSize = 11.sp, color = InkGray)
            Text(
                when {
                    yxy.cardBalanceYuan != null -> "¥ %.2f".format(yxy.cardBalanceYuan)
                    signedIn -> "点击刷新同步余额"
                    else -> "点此登录易校园或隐藏卡片"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = InkDark,
            )
        }
        if (yxy.isLowBalance) {
            Text(
                "低于 ¥${yxy.lowBalanceThresholdYuan.toInt()}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFD64541),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x1AD64541))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (signedIn) {
            if (yxy.isLowBalance) Spacer(Modifier.width(8.dp))
            Text(
                if (busy) "刷新中" else "刷新",
                fontSize = 12.sp,
                color = BrandGreen,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(enabled = !busy, onClick = onRefresh)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        } else if (!yxy.isLowBalance) {
            Text("配置 ›", fontSize = 12.sp, color = BrandGreen, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ParcelCard(
    pending: List<ParcelItem>,
    listenerEnabled: Boolean,
    onMarkPicked: (String) -> Unit,
    onOpenTaobaoCode: () -> Unit,
    onOpenPddCode: () -> Unit,
    onAddManual: () -> Unit,
    onScanSms: () -> Unit,
    onOpenRules: () -> Unit,
) {
    LifeCardContainer {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "待取快递",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (IsDark) Color(0xFFFCD34D) else Color(0xFFB45309),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (IsDark) Color(0x28F59E0B) else Color(0xFFFEF3E2))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (pending.isEmpty()) "暂无待取" else "${pending.size} 件",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = InkDark,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenRules, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Settings, "取件码规则", tint = if (listenerEnabled) BrandGreen else InkGray, modifier = Modifier.size(16.dp))
            }
        }

        if (pending.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            pending.take(5).forEach { parcel ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(softSurface())
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            parcel.code,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = InkDark,
                        )
                        Text(
                            listOf(parcel.station, parcel.carrier).filter { it.isNotBlank() }.joinToString(" · "),
                            fontSize = 11.sp,
                            color = InkMid,
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(BrandGreen)
                            .clickable { onMarkPicked(parcel.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("已取", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                if (listenerEnabled) "通知监听已开启，新取件码到达时自动收录" else "点右上角齿轮开启通知自动识别，或使用下方身份码/短信扫描",
                fontSize = 11.sp,
                color = InkGray,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuickPillButton("淘宝身份码", Modifier.weight(1f), onClick = onOpenTaobaoCode)
            QuickPillButton("拼多多码", Modifier.weight(1f), onClick = onOpenPddCode)
            QuickPillButton("粘贴/录入", Modifier.weight(1f), onClick = onAddManual)
            QuickPillButton("扫短信", Modifier.weight(1f), onClick = onScanSms)
        }
    }
}

private fun waterDeviceShortLabel(device: WaterDevice, all: List<WaterDevice>): String {
    val name = device.name.trim()
    val kindLabel: (String) -> String? = { raw ->
        when {
            raw.contains("冷水") || raw.contains("凉水") -> "冷水"
            raw.contains("常温") -> "常温"
            raw.contains("热水") -> "热水"
            raw.contains("开水") -> "开水"
            raw.contains("温水") -> "温水"
            else -> null
        }
    }
    val mapped = all.map { kindLabel(it.name) }
    val own = kindLabel(name)
    if (own != null && mapped.count { it == own } == 1) return own
    val names = all.map { it.name.trim() }
    val prefixLen = if (names.size > 1) {
        val minLen = names.minOf { it.length }
        var idx = 0
        while (idx < minLen && names.all { it[idx] == names[0][idx] }) idx++
        idx
    } else 0
    val suffix = name.drop(prefixLen).trim().trim('-', '_', ' ', '(', ')', '（', '）')
    return suffix.ifBlank { own ?: name.takeLast(5) }.take(6)
}

@Composable
private fun WaterTile(
    water: WaterUiState,
    onSelectDevice: (String) -> Unit,
    onOneTapStart: () -> Unit,
    onOpenManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSwitch = !water.busy && water.startSeconds == 0
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CardWhite)
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenManage)
            .padding(13.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                Text("喝水", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = InkDark)
                Text(
                    water.walletBalance?.let { "慧生活 · ¥%.2f".format(it) } ?: "慧生活 798",
                    fontSize = 11.sp,
                    color = InkGray,
                )
            }
            val tagText = when {
                water.startSeconds > 0 -> "${water.startSeconds}s"
                water.loggedIn && water.device != null -> "就绪"
                else -> "待配置"
            }
            Text(
                tagText,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (IsDark) Color(0xFF7DD3FC) else Color(0xFF0E6A8F),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (IsDark) Color(0x280EA5E9) else Color(0xFFE6F4FA))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            water.device?.name ?: if (water.loggedIn) "点此绑定常用饮水机" else "点此登录慧生活 798",
            fontSize = 11.sp,
            color = InkMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (water.loggedIn && water.devices.size > 1) {
            Spacer(Modifier.height(6.dp))
            val useScroll = water.devices.size > 2
            val rowMod = if (useScroll) {
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            }
            Row(rowMod, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                water.devices.forEach { dev ->
                    val selected = dev.id == water.selectedId
                    val isCold = dev.name.contains("冷水") || dev.name.contains("凉水") || dev.name.contains("常温")
                    val isHot = dev.name.contains("热水") || dev.name.contains("开水") || dev.name.contains("温水")
                    val (bg, fg) = when {
                        !selected -> softSurface() to InkMid
                        isCold -> (if (IsDark) Color(0x280EA5E9) else Color(0xFFE6F4FA)) to
                            (if (IsDark) Color(0xFF7DD3FC) else Color(0xFF0E6A8F))
                        isHot -> (if (IsDark) Color(0x28F97316) else Color(0xFFFEECE6)) to
                            (if (IsDark) Color(0xFFFDBA74) else Color(0xFFC2410C))
                        else -> BrandGreenSoft to BrandGreen
                    }
                    Box(
                        (if (useScroll) Modifier else Modifier.weight(1f))
                            .clip(RoundedCornerShape(8.dp))
                            .background(bg)
                            .clickable(enabled = canSwitch && !selected) { onSelectDevice(dev.id) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            waterDeviceShortLabel(dev, water.devices),
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOneTapStart,
            enabled = canSwitch,
            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
            shape = RoundedCornerShape(11.dp),
            modifier = Modifier.fillMaxWidth().height(34.dp),
        ) {
            Text(
                when {
                    water.startSeconds > 0 -> "接水中 ${water.startSeconds}s"
                    water.loggedIn && water.device != null -> "一键启动"
                    else -> "登录 / 绑定"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ShowerCodeTile(
    yxy: com.hmu.muye.data.campus.YixiaoyuanState,
    signedIn: Boolean,
    waterCode: com.hmu.muye.data.yixiaoyuan.WaterCode?,
    busy: Boolean,
    feedback: String?,
    feedbackError: Boolean,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canFetch = signedIn || yxy.hasCredential
    val formatted = yxy.formattedShowerCode()
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CardWhite)
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .clickable(onClick = onConfigure)
            .padding(13.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                Text("洗澡用水码", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = InkDark)
                Text(if (canFetch) "8 位动态码" else "登录即用", fontSize = 11.sp, color = InkGray)
            }
            Text(
                if (canFetch) "设置" else "去登录",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (canFetch) InkMid else BrandGreen,
                modifier = Modifier.clickable(onClick = onConfigure).padding(2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(BrandGreenSoft)
                .clickable(enabled = !busy) {
                    if (canFetch && formatted.isBlank()) onRefresh() else onConfigure()
                }
                .padding(vertical = 8.dp, horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                formatted.ifBlank { if (canFetch) "暂无生效码" else "登录获取" },
                fontSize = if (formatted.isNotBlank()) 16.sp else 12.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = if (formatted.isNotBlank()) FontFamily.Monospace else FontFamily.Default,
                color = BrandGreen,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(4.dp))
        val hintText = feedback?.takeIf { it.isNotBlank() }
            ?: waterCode?.summaryText()?.takeIf { it.isNotBlank() }
            ?: "8 位用水码 · 水控机直接输入"
        val hintColor = when {
            feedbackError -> Color(0xFFD64541)
            !feedback.isNullOrBlank() -> BrandGreen
            else -> InkGray
        }
        Text(
            hintText,
            fontSize = 10.sp,
            color = hintColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (canFetch) {
            Spacer(Modifier.height(6.dp))
            if (formatted.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(BrandGreenSoft)
                            .clickable(enabled = !busy, onClick = onRefresh)
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (busy) "刷新中" else "换新码",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BrandGreen,
                            maxLines = 1,
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (IsDark) Color(0x28D64541) else Color(0xFFFDECEC))
                            .clickable(enabled = !busy, onClick = onCancel)
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (busy) "处理中" else "停用",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFD64541),
                            maxLines = 1,
                        )
                    }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(BrandGreen)
                        .clickable(enabled = !busy, onClick = onRefresh)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (busy) "获取中…" else "获取用水码",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun HairdryerTile(
    bookmarkCount: Int,
    ujingStoreName: String?,
    onOpenUjing: () -> Unit,
    onOpenHainiao: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CardWhite)
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("吹风机", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = InkDark)
            val subtitle = when {
                !ujingStoreName.isNullOrBlank() -> "U净 · $ujingStoreName"
                bookmarkCount > 0 -> "宿舍 U净 · 海鸟已存 $bookmarkCount 码"
                else -> "宿舍 U净 · 浴室海鸟"
            }
            Text(
                subtitle,
                fontSize = 11.sp,
                color = InkGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (IsDark) Color(0x2E8B5CF6) else Color(0xFFF1ECFB))
                    .clickable(onClick = onOpenUjing)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "U 净",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (IsDark) Color(0xFFC4B5FD) else Color(0xFF5B3FA8),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(softSurface())
                    .clickable(onClick = onOpenHainiao)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("海鸟扫码", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
            }
        }
    }
}

@Composable
private fun LaundryTile(
    onOpenLezai: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CardWhite)
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                Text("洗衣机", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = InkDark)
                Text("乐仔生活", fontSize = 11.sp, color = InkGray)
            }
            Text(
                "快捷",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = InkMid,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(softSurface())
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(softSurface())
                .clickable(onClick = onOpenLezai)
                .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("打开乐仔生活 →", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
        }
    }
}

@Composable
private fun LifeCardContainer(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardWhite)
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .padding(13.dp),
    ) {
        content()
    }
}

@Composable
private fun QuickPillButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(softSurface())
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = InkDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun softSurface(): Color = if (IsDark) Color(0xFF25252B) else Color(0xFFEEF1F6)

// ==================== 辅助弹窗组件 ====================

@Composable
private fun PortalConfigDialog(
    initialUrl: String,
    initialAccount: String,
    initialPassword: String,
    initialIsp: String,
    initialAuto: Boolean,
    online: Boolean?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, String, Boolean) -> Unit,
    onAuthenticate: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var account by rememberSaveable { mutableStateOf(initialAccount) }
    var password by rememberSaveable { mutableStateOf(initialPassword) }
    var isp by rememberSaveable { mutableStateOf(initialIsp) }
    var auto by rememberSaveable { mutableStateOf(initialAuto) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text("龙子湖校园网 Portal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (online) {
                        true -> "当前状态：已放行外网"
                        false -> "当前状态：未通过 Portal 认证"
                        null -> "当前状态：未探测"
                    },
                    fontSize = 12.sp,
                    color = BrandGreen,
                )
                OutlinedTextField(url, { url = it }, label = { Text("认证页 URL（可选，探测可自动识别）") }, singleLine = true)
                OutlinedTextField(account, { account = it }, label = { Text("校园网账号") }, singleLine = true)
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("校园网密码（AES-GCM 本机加密）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(isp, { isp = it }, label = { Text("运营商后缀（可选，如 @cmcc）") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("切回「生活」页时自动探测", fontSize = 12.sp, color = InkMid, modifier = Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                onSave(url, account, password, isp, auto)
                onAuthenticate()
            }) { Text(if (busy) "认证中…" else "保存并一键认证") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun YixiaoyuanConfigDialog(
    state: com.hmu.muye.data.campus.YixiaoyuanState,
    signedIn: Boolean,
    savedMobile: String,
    busy: Boolean,
    waterCode: com.hmu.muye.data.yixiaoyuan.WaterCode?,
    statusMessage: String?,
    statusError: Boolean,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
    onLogin: (mobile: String, smsCode: String) -> Unit,
    onLoginByPassword: (mobile: String, password: String) -> Unit = { _, _ -> },
    onLogout: () -> Unit,
    onRefreshBalance: () -> Unit,
    onRefreshWaterCode: (shiroJid: String) -> Unit,
    onGenerateWaterCode: () -> Unit = {},
    onCancelWaterCode: () -> Unit = {},
    onToggleShowBalance: (Boolean) -> Unit,
    onSave: (String, String?, String, String, String) -> Unit,
) {
    var mobile by rememberSaveable(savedMobile) { mutableStateOf(savedMobile) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var useSmsLogin by rememberSaveable { mutableStateOf(false) }
    var smsCode by rememberSaveable { mutableStateOf("") }
    var ymId by rememberSaveable(state.ymId) { mutableStateOf(state.ymId) }
    var shiro by rememberSaveable { mutableStateOf("") }
    var balance by rememberSaveable(state.cardBalanceYuan) {
        mutableStateOf(state.cardBalanceYuan?.toString().orEmpty())
    }
    var threshold by rememberSaveable(state.lowBalanceThresholdYuan) {
        mutableStateOf(state.lowBalanceThresholdYuan.toString())
    }
    var code by rememberSaveable(state.showerCode) { mutableStateOf(state.showerCode) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val displayStatus = statusMessage?.takeUnless { !state.showBalanceCard && it.contains("余额") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = {
            Text(if (state.showBalanceCard) "易校园 · 用水码与一卡通" else "易校园 · 洗澡用水码")
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("在「生活」页显示余额卡", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = InkDark)
                        Text("不在易校园充钱时可关闭", fontSize = 11.sp, color = InkGray)
                    }
                    Switch(
                        checked = state.showBalanceCard,
                        onCheckedChange = onToggleShowBalance,
                    )
                }

                HorizontalDivider(color = Hairline)

                Text(
                    if (state.showBalanceCard) "易校园账号（洗澡用水码 · 一卡通余额）"
                    else "易校园账号（洗澡用水码）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = InkDark,
                )
                if (signedIn) {
                    if (state.showBalanceCard) {
                        Text(
                            "已登录 ${savedMobile.ifBlank { state.ymId }} · 当前余额：${state.cardBalanceYuan?.let { "¥ %.2f".format(it) } ?: "待刷新"}",
                            fontSize = 12.sp,
                            color = InkMid,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onRefreshBalance,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                            ) {
                                Text(if (busy) "处理中…" else "刷新余额")
                            }
                            OutlinedButton(
                                onClick = onLogout,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("退出账号")
                            }
                        }
                    } else {
                        Text(
                            "已登录 ${savedMobile.ifBlank { state.ymId }}（已自动关联洗澡用水码）",
                            fontSize = 12.sp,
                            color = InkMid,
                        )
                        OutlinedButton(
                            onClick = onLogout,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("退出账号")
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val pwdActive = !useSmsLogin
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (pwdActive) BrandGreen else BrandGreenSoft)
                                .clickable { useSmsLogin = false }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "密码直连登录",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (pwdActive) Color.White else BrandGreen,
                            )
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (useSmsLogin) BrandGreen else BrandGreenSoft)
                                .clickable { useSmsLogin = true }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "短信验证码",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (useSmsLogin) Color.White else BrandGreen,
                            )
                        }
                    }
                    Text(
                        if (useSmsLogin) {
                            "先打开易校园 App 获取短信验证码，填入此处提交登录即可同步用水码。"
                        } else {
                            "直接输入易校园手机号与密码即可直连登录，自动同步 8 位洗澡用水码，无需手动填 Cookie。"
                        },
                        fontSize = 11.sp,
                        color = InkGray,
                    )
                    OutlinedTextField(
                        mobile,
                        { mobile = it.filter(Char::isDigit).take(11) },
                        label = { Text("易校园绑定手机号") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!useSmsLogin) {
                        OutlinedTextField(
                            password,
                            { password = it },
                            label = { Text("易校园登录密码") },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = "显示/隐藏密码",
                                        tint = InkGray,
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onLoginByPassword(mobile, password) },
                            enabled = !busy && mobile.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                        ) {
                            Text(if (busy) "登录中…" else "登录易校园")
                        }
                    } else {
                        OutlinedTextField(
                            smsCode,
                            { smsCode = it.filter(Char::isDigit).take(8) },
                            label = { Text("短信验证码（在易校园 App 获取）") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onLogin(mobile, smsCode) },
                                enabled = !busy && mobile.isNotBlank() && smsCode.isNotBlank(),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                            ) {
                                Text(if (busy) "登录中…" else "验证码登录")
                            }
                            OutlinedButton(onClick = onOpenApp, modifier = Modifier.weight(1f)) {
                                Text("打开易校园")
                            }
                        }
                    }
                }

                HorizontalDivider(color = Hairline)

                Text("浴室 8 位动态用水码", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
                val formattedCode = state.formattedShowerCode()
                if (formattedCode.isNotBlank()) {
                    Text(
                        "当前码：$formattedCode" + (
                            waterCode?.summaryText()?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                        ),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandGreen,
                    )
                }
                val canFetchCode = signedIn || state.hasCredential || shiro.isNotBlank()
                if (canFetchCode) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val draft = shiro
                                shiro = ""
                                onRefreshWaterCode(draft)
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (busy) "刷新中…" else "刷新状态", color = BrandGreen, fontWeight = FontWeight.Medium)
                        }
                        OutlinedButton(
                            onClick = onGenerateWaterCode,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("换新码", color = InkDark, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (formattedCode.isNotBlank()) {
                        OutlinedButton(
                            onClick = onCancelWaterCode,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("停用当前码", color = Color(0xFFD64541), fontWeight = FontWeight.Medium)
                        }
                    }
                } else {
                    Text(
                        "在上方登录易校园账号后自动获取，无需手动配置 Cookie",
                        fontSize = 11.sp,
                        color = InkGray,
                    )
                }

                if (state.showBalanceCard) {
                    OutlinedTextField(
                        threshold,
                        { threshold = it },
                        label = { Text("低余额提醒阈值（元，默认 10）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(
                        if (showAdvanced) "收起高级 / 手动排查" else "高级 / 手动排查（可选）",
                        fontSize = 12.sp,
                        color = InkGray,
                    )
                }
                if (showAdvanced) {
                    OutlinedTextField(
                        shiro,
                        { shiro = it },
                        label = {
                            Text(
                                if (state.hasCredential) "shiroJID Cookie（已配置，留空保持不变）"
                                else "手动填入 shiroJID Cookie（通常无需填写）",
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) {
                        Text("打开易校园 App")
                    }
                    OutlinedTextField(
                        code,
                        { code = it.filter(Char::isDigit).take(8) },
                        label = { Text("手动记录 8 位洗澡用水码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.showBalanceCard) {
                        OutlinedTextField(
                            balance,
                            { balance = it },
                            label = { Text("手动修正当前余额（元）") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        ymId,
                        { ymId = it },
                        label = { Text("易校园 ymId（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                displayStatus?.let { msg ->
                    Text(
                        msg,
                        fontSize = 11.sp,
                        color = if (statusError) Color(0xFFD64541) else BrandGreen,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ymId, shiro.takeIf { it.isNotBlank() }, balance, threshold, code) }) {
                Text("保存设置")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ParcelIngestDialog(
    onDismiss: () -> Unit,
    onPasteClipboard: () -> String,
    onIngestText: (String) -> Unit,
    onManualAdd: (String, String, String) -> Unit,
) {
    var rawText by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var station by rememberSaveable { mutableStateOf("菜鸟驿站") }
    var carrier by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text("识别或录入取件码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    rawText,
                    { rawText = it },
                    label = { Text("粘贴快递短信或驿站通知原文") },
                    maxLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { rawText = onPasteClipboard() }, modifier = Modifier.weight(1f)) {
                        Text("读取剪贴板", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { onIngestText(rawText) },
                        enabled = rawText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                        modifier = Modifier.weight(1f),
                    ) { Text("智能提取", fontSize = 12.sp) }
                }
                HorizontalDivider(color = Hairline)
                Text("或直接手填：", fontSize = 11.sp, color = InkGray)
                OutlinedTextField(code, { code = it }, label = { Text("取件码（如 3-2-4082）") }, singleLine = true)
                OutlinedTextField(station, { station = it }, label = { Text("驿站名称") }, singleLine = true)
                OutlinedTextField(carrier, { carrier = it }, label = { Text("快递公司（可选）") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = code.isNotBlank(), onClick = { onManualAdd(code, station, carrier) }) {
                Text("添加手填")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ParcelRulesDialog(
    listenerEnabled: Boolean,
    rules: List<com.hmu.muye.data.parcel.CustomParcelRule>,
    onDismiss: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onAddRule: (String, String) -> Unit,
    onRemoveRule: (String) -> Unit,
) {
    var station by rememberSaveable { mutableStateOf("") }
    var pattern by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text("通知识别与自定义规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (listenerEnabled) "通知栏监听：已授权" else "通知栏监听：未开启",
                        fontSize = 12.sp,
                        color = if (listenerEnabled) BrandGreen else InkMid,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenNotificationSettings) { Text("去系统设置") }
                }
                HorizontalDivider(color = Hairline)
                OutlinedTextField(station, { station = it }, label = { Text("驿站标签（如 龙子湖西门）") }, singleLine = true)
                OutlinedTextField(pattern, { pattern = it }, label = { Text("正则表达式（组1为取件码）") }, singleLine = true)
                Button(
                    onClick = {
                        onAddRule(station, pattern)
                        station = ""
                        pattern = ""
                    },
                    enabled = station.isNotBlank() && pattern.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("添加自定义规则", fontSize = 12.sp) }
                rules.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.stationLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
                            Text(r.pattern, fontSize = 10.sp, color = InkGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onRemoveRule(r.id) }) {
                            Icon(Icons.Outlined.DeleteOutline, "删除", tint = InkGray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UjingSheet(
    state: LifeUiState,
    smsCooldown: Int,
    onDismiss: () -> Unit,
    onSendCaptcha: (String) -> Unit,
    onLogin: (String, String) -> Unit,
    onSaveJwt: (String) -> Unit,
    onLogout: () -> Unit,
    onKeywordChange: (String) -> Unit,
    onSearchStores: () -> Unit,
    onSelectStore: (com.hmu.muye.data.ujing.UjingStoreSummary) -> Unit,
    onRefreshStore: () -> Unit,
    onScanQr: () -> Unit,
    onOpenWeChat: () -> Unit,
) {
    var phone by rememberSaveable(state.yixiaoyuanMobile) { mutableStateOf(state.yixiaoyuanMobile) }
    var smsCode by rememberSaveable { mutableStateOf("") }
    var pastedJwt by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("宿舍吹风机 · U 净", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = InkDark)
            if (state.ujingConfigured) {
                ServiceMessage(state.ujingMessage, state.ujingError)
            }

            if (!state.ujingConfigured) {
                Text(
                    "输入手机号与短信验证码登录；若「发送验证码」被 U 净安全校验拦截，可先在微信「U 净」小程序发送短信后再填入验证码直接登录（或粘贴 JWT）。",
                    fontSize = 12.sp,
                    color = InkMid,
                )
                OutlinedTextField(
                    phone,
                    { phone = it.filter(Char::isDigit).take(11) },
                    label = { Text("U 净绑定手机号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        smsCode,
                        { smsCode = it.filter(Char::isDigit).take(8) },
                        label = { Text("短信验证码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = !state.ujingBusy && smsCooldown == 0,
                        onClick = { onSendCaptcha(phone) },
                    ) {
                        Text(
                            when {
                                state.ujingBusy -> "发送中…"
                                smsCooldown > 0 -> "${smsCooldown}s"
                                else -> "发送验证码"
                            },
                            fontSize = 12.sp,
                        )
                    }
                }
                ServiceMessage(state.ujingMessage, state.ujingError)
                Button(
                    onClick = { onLogin(phone, smsCode) },
                    enabled = !state.ujingBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.ujingBusy) "登录中…" else "登录 U 净", fontWeight = FontWeight.SemiBold) }

                HorizontalDivider(color = Hairline)
                Text("已有抓包 JWT 也可直接粘贴：", fontSize = 11.sp, color = InkGray)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        pastedJwt,
                        { pastedJwt = it },
                        label = { Text("U 净 JWT Token") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = pastedJwt.isNotBlank() && !state.ujingBusy,
                        onClick = {
                            onSaveJwt(pastedJwt)
                            pastedJwt = ""
                        },
                    ) { Text("保存") }
                }
                OutlinedButton(onClick = onOpenWeChat, modifier = Modifier.fillMaxWidth()) {
                    Text("去微信打开 U 净小程序")
                }
            } else {
                OutlinedTextField(
                    value = state.ujingKeyword,
                    onValueChange = onKeywordChange,
                    label = { Text("门店关键词（如 河南牧业 / 龙子湖，留空查2km附近）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSearchStores,
                        enabled = !state.ujingBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.ujingBusy) "查询中…" else "查找龙子湖附近门店", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = onScanQr,
                        enabled = !state.ujingBusy,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("扫机身码", fontSize = 12.sp)
                    }
                }

                state.ujingScannedDevice?.let { dev ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrandGreenSoft)
                            .padding(12.dp),
                    ) {
                        Text(
                            "${dev.name}${if (dev.typeName.isNotBlank()) " · ${dev.typeName}" else ""}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = InkDark,
                        )
                        Text("状态：${dev.statusText}", fontSize = 12.sp, color = BrandGreen, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (state.ujingStores.isNotEmpty()) {
                    Text("选择门店（查看设备空闲）：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkGray)
                    state.ujingStores.take(10).forEach { store ->
                        val selected = state.ujingSelectedStore?.id == store.id
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) BrandGreenSoft else CardWhite)
                                .border(1.dp, if (selected) BrandGreen else Hairline, RoundedCornerShape(12.dp))
                                .clickable { onSelectStore(store) }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(store.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
                            if (store.address.isNotBlank()) {
                                Text(store.address, fontSize = 11.sp, color = InkGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (store.categories.isNotEmpty()) {
                                val summary = store.categories.joinToString(" · ") { c ->
                                    "${c.label} 空闲 ${c.available}/${c.total}"
                                }
                                Text(summary, fontSize = 11.sp, color = BrandGreen, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                state.ujingSelectedStore?.let { store ->
                    HorizontalDivider(color = Hairline)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("当前门店：${store.name}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
                            if (state.ujingDevices.isEmpty()) {
                                Text("点击刷新或扫码查看具体机器状态", fontSize = 11.sp, color = InkGray)
                            }
                        }
                        TextButton(onClick = onRefreshStore, enabled = !state.ujingBusy) {
                            Text("刷新状态", color = BrandGreen, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    state.ujingDevices.take(20).forEach { dev ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CardWhite)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(dev.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = InkDark)
                                if (dev.typeName.isNotBlank()) {
                                    Text(dev.typeName, fontSize = 10.sp, color = InkGray)
                                }
                            }
                            Text(
                                dev.statusText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (dev.available) BrandGreen else InkMid,
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onOpenWeChat) { Text("去微信 U 净", color = InkMid) }
                    TextButton(onClick = onLogout) { Text("退出 U 净账号", color = Color(0xFFD64541)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HainiaoSheet(
    bookmarks: List<com.hmu.muye.data.campus.HairdryerBookmark>,
    pendingScannedQr: String?,
    onDismiss: () -> Unit,
    onStartScan: () -> Unit,
    onSaveBookmark: (String, String) -> Unit,
    onOpenBookmark: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("浴室常用吹风机") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("浴室吹风机 · 合肥海鸟", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = InkDark)
            Text("扫一次机身上的海鸟支付码即可收藏为常用按钮，下次不用切微信找扫一扫。", fontSize = 12.sp, color = InkGray)
            Button(
                onClick = onStartScan,
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("扫描海鸟二维码并拉起支付")
            }
            if (!pendingScannedQr.isNullOrBlank()) {
                OutlinedTextField(label, { label = it }, label = { Text("给刚扫到的吹风机起个名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = { onSaveBookmark(label, pendingScannedQr) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("收藏此吹风机码") }
            }
            if (bookmarks.isNotEmpty()) {
                HorizontalDivider(color = Hairline)
                Text("已收藏吹风机", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkGray)
                bookmarks.forEach { bm ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardWhite)
                            .clickable { onOpenBookmark(bm.qrPayload) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(bm.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
                            Text(bm.qrPayload, fontSize = 10.sp, color = InkGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("直达 ›", fontSize = 12.sp, color = BrandGreen, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { onRemoveBookmark(bm.id) }) {
                            Icon(Icons.Outlined.DeleteOutline, "删除", tint = InkGray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryDialog(
    title: String,
    entries: List<com.hmu.muye.data.campus.HmuCampusDirectory.DirectoryEntry>,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                entries.forEach { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(softSurface())
                            .then(
                                if (item.copyText.startsWith("http")) Modifier.clickable { onOpenUrl(item.copyText) }
                                else Modifier,
                            )
                            .padding(10.dp),
                    ) {
                        Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = InkDark)
                        Text(item.detail, fontSize = 12.sp, color = InkMid)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutsSheet(
    customShortcuts: List<com.hmu.muye.data.campus.QuickShortcut>,
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit,
    onRemove: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var uri by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("常用校内入口与自定义快捷", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = InkDark)
            HmuCampusDirectory.BUILTIN_PORTALS.forEach { portal ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardWhite)
                        .clickable { onOpen(portal.targetUri) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(portal.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkDark)
                        Text(portal.subtitle, fontSize = 11.sp, color = InkGray)
                    }
                    Text("打开 →", fontSize = 12.sp, color = BrandGreen)
                }
            }
            HorizontalDivider(color = Hairline)
            OutlinedTextField(title, { title = it }, label = { Text("快捷名称（如 选座网页）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(uri, { uri = it }, label = { Text("网址或 DeepLink（https://...）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    onAdd(title, uri, uri)
                    title = ""
                    uri = ""
                },
                enabled = title.isNotBlank() && uri.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("添加到生活主页") }
            customShortcuts.forEach { sc ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(sc.title, fontSize = 13.sp, color = InkDark, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemove(sc.id) }) {
                        Icon(Icons.Outlined.DeleteOutline, "删除", tint = InkGray, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
