package com.hmu.muye.ui.life

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fengkao.timetable.TimetableApp
import com.hmu.muye.data.campus.HairdryerBookmark
import com.hmu.muye.data.campus.HmuCampus
import com.hmu.muye.data.campus.PortalAuthResult
import com.hmu.muye.data.campus.PortalConfig
import com.hmu.muye.data.campus.QuickShortcut
import com.hmu.muye.data.campus.YixiaoyuanState
import com.hmu.muye.data.parcel.CustomParcelRule
import com.hmu.muye.data.parcel.ParcelItem
import com.hmu.muye.data.parcel.ParcelNotificationListener
import com.hmu.muye.data.parcel.ParcelSmsScanner
import com.hmu.muye.data.ujing.UjingClient
import com.hmu.muye.data.ujing.UjingDeviceInfo
import com.hmu.muye.data.ujing.UjingResult
import com.hmu.muye.data.ujing.UjingStoreSummary
import com.hmu.muye.data.yixiaoyuan.WaterCode
import com.hmu.muye.data.yixiaoyuan.YixiaoyuanClient
import com.hmu.muye.data.yixiaoyuan.YixiaoyuanCredential
import com.hmu.muye.data.yixiaoyuan.YixiaoyuanResult
import com.hmu.muye.data.yixiaoyuan.YixiaoyuanWaterClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LifeUiState(
    val campus: HmuCampus = HmuCampus.LONGZIHU,
    val portal: PortalConfig = PortalConfig(),
    val portalOnline: Boolean? = null,
    val portalBusy: Boolean = false,
    val yixiaoyuan: YixiaoyuanState = YixiaoyuanState(),
    val yixiaoyuanSignedIn: Boolean = false,
    val yixiaoyuanMobile: String = "",
    val yixiaoyuanBusy: Boolean = false,
    val waterCodeInfo: WaterCode? = null,
    val showerFeedback: String? = null,
    val showerFeedbackError: Boolean = false,
    val pendingParcels: List<ParcelItem> = emptyList(),
    val archivedParcelCount: Int = 0,
    val customParcelRules: List<CustomParcelRule> = emptyList(),
    val notificationListenerEnabled: Boolean = false,
    val hairdryerBookmarks: List<HairdryerBookmark> = emptyList(),
    val customShortcuts: List<QuickShortcut> = emptyList(),
    val ujingConfigured: Boolean = false,
    val ujingBusy: Boolean = false,
    val ujingKeyword: String = "河南牧业",
    val ujingStores: List<UjingStoreSummary> = emptyList(),
    val ujingSelectedStore: UjingStoreSummary? = null,
    val ujingDevices: List<UjingDeviceInfo> = emptyList(),
    val ujingScannedDevice: UjingDeviceInfo? = null,
    val ujingMessage: String? = null,
    val ujingError: Boolean = false,
    val statusMessage: String? = null,
    val statusError: Boolean = false,
)

class LifeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TimetableApp
    private val container = app.container
    private val campusLife = container.campusLife
    private val parcels = container.parcels
    private val portalClient = container.portalClient
    private val ujingClient = UjingClient(
        log = { msg -> android.util.Log.d("UjingApi", msg) },
    )
    private val yixiaoyuanClient = YixiaoyuanClient()
    private val waterClient = YixiaoyuanWaterClient(
        log = { msg -> android.util.Log.d("YxyWater", msg) },
    )

    private val _state = MutableStateFlow(
        campusLife.yixiaoyuanCredential().let { cred ->
            LifeUiState(
                campus = campusLife.campus(),
                portal = campusLife.portal(),
                yixiaoyuan = campusLife.yixiaoyuan(),
                yixiaoyuanSignedIn = cred.signedIn,
                yixiaoyuanMobile = cred.mobile,
                pendingParcels = parcels.pendingItems(),
                archivedParcelCount = parcels.allItems().count { it.pickedUp },
                customParcelRules = parcels.customRules(),
                notificationListenerEnabled = ParcelNotificationListener.isEnabled(application),
                hairdryerBookmarks = campusLife.hairdryerBookmarks(),
                customShortcuts = campusLife.shortcuts(),
                ujingConfigured = campusLife.ujingJwt().isNotBlank(),
                ujingKeyword = campusLife.ujingKeyword(),
                ujingSelectedStore = campusLife.ujingStoreId().takeIf { it.isNotBlank() }?.let { id ->
                    UjingStoreSummary(id = id, name = campusLife.ujingStoreName().ifBlank { "已选门店" })
                },
            )
        },
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                campusLife.campusFlow,
                campusLife.portalFlow,
                campusLife.yixiaoyuanFlow,
                parcels.itemsFlow,
                parcels.rulesFlow,
            ) { campus, portal, yxy, allParcels, rules ->
                _state.value.copy(
                    campus = campus,
                    portal = portal,
                    yixiaoyuan = yxy,
                    pendingParcels = allParcels.filterNot { it.pickedUp },
                    archivedParcelCount = allParcels.count { it.pickedUp },
                    customParcelRules = rules,
                )
            }.collect { updated ->
                val cred = campusLife.yixiaoyuanCredential()
                _state.value = updated.copy(
                    yixiaoyuanSignedIn = cred.signedIn,
                    yixiaoyuanMobile = cred.mobile,
                    hairdryerBookmarks = campusLife.hairdryerBookmarks(),
                    customShortcuts = campusLife.shortcuts(),
                    ujingConfigured = campusLife.ujingJwt().isNotBlank(),
                    notificationListenerEnabled = ParcelNotificationListener.isEnabled(app),
                )
            }
        }
    }

    fun refreshPermissionsAndProbePortal() {
        _state.update {
            it.copy(notificationListenerEnabled = ParcelNotificationListener.isEnabled(app))
        }
        viewModelScope.launch {
            val (online, _) = portalClient.probeCaptivePortal()
            _state.update { it.copy(portalOnline = online) }
        }
    }

    fun setCampus(campus: HmuCampus) {
        campusLife.setCampus(campus)
    }

    fun clearMessage() {
        _state.update { it.copy(statusMessage = null, statusError = false) }
    }

    // ---------------- 龙子湖校园网 Portal ----------------

    fun authenticatePortal() {
        if (_state.value.portalBusy) return
        _state.update { it.copy(portalBusy = true, statusMessage = null, statusError = false) }
        viewModelScope.launch {
            val res = portalClient.authenticate(campusLife.portal(), campusLife.portalPassword())
            when (res) {
                is PortalAuthResult.Authenticated -> {
                    campusLife.updatePortalStatus(true)
                    _state.update {
                        it.copy(portalBusy = false, portalOnline = true, statusMessage = res.message, statusError = false)
                    }
                }
                is PortalAuthResult.NeedConfig -> {
                    _state.update {
                        it.copy(portalBusy = false, statusMessage = res.message, statusError = false)
                    }
                }
                is PortalAuthResult.Failed -> {
                    campusLife.updatePortalStatus(false)
                    _state.update {
                        it.copy(portalBusy = false, portalOnline = false, statusMessage = res.message, statusError = true)
                    }
                }
            }
        }
    }

    fun savePortalConfig(authUrl: String, account: String, password: String?, ispSuffix: String, autoAuth: Boolean) {
        val next = _state.value.portal.copy(
            authUrl = authUrl.trim(),
            account = account.trim(),
            ispSuffix = ispSuffix.trim(),
            autoAuth = autoAuth,
        )
        campusLife.savePortalConfig(next, password)
        _state.update { it.copy(statusMessage = "校园网认证配置已加密保存", statusError = false) }
    }

    fun portalSavedAccount(): String = campusLife.portal().account
    fun portalSavedPassword(): String = campusLife.portalPassword()

    // ---------------- 易校园：一卡通余额 & 浴室 8 位动态用水码 ----------------

    fun saveYixiaoyuanConfig(
        ymId: String,
        shiroJid: String?,
        balanceText: String,
        thresholdText: String,
        showerCode: String,
    ) {
        val balance = balanceText.trim().toDoubleOrNull() ?: _state.value.yixiaoyuan.cardBalanceYuan
        val threshold = thresholdText.trim().toDoubleOrNull()?.coerceIn(0.0, 500.0)
            ?: _state.value.yixiaoyuan.lowBalanceThresholdYuan
        val cleanCode = showerCode.filter(Char::isDigit).take(8)
        val current = _state.value.yixiaoyuan
        val next = current.copy(
            ymId = ymId.trim(),
            cardBalanceYuan = balance,
            lowBalanceThresholdYuan = threshold,
            showerCode = cleanCode.ifBlank { current.showerCode },
            showerCodeAt = if (cleanCode.isNotBlank()) System.currentTimeMillis() else current.showerCodeAt,
        )
        campusLife.saveYixiaoyuan(next, shiroJid)
        _state.update { it.copy(statusMessage = "已保存易校园配置", statusError = false) }
    }

    fun setShowYixiaoyuanBalance(show: Boolean) {
        val next = campusLife.yixiaoyuan().copy(showBalanceCard = show)
        campusLife.saveYixiaoyuan(next)
        _state.update { cur ->
            val clearBalanceMsg = !show && cur.statusMessage?.contains("余额") == true
            cur.copy(
                yixiaoyuan = next,
                statusMessage = if (clearBalanceMsg) null else cur.statusMessage,
                statusError = if (clearBalanceMsg) false else cur.statusError,
            )
        }
    }

    fun logoutYixiaoyuan() {
        campusLife.saveYixiaoyuanCredential(YixiaoyuanCredential())
        val cleared = campusLife.yixiaoyuan().copy(
            hasCredential = false,
            ymId = "",
            cardBalanceYuan = null,
            showerCode = "",
            showerCodeAt = 0L,
        )
        campusLife.saveYixiaoyuan(cleared, shiroJid = "")
        _state.update {
            it.copy(
                yixiaoyuan = cleared,
                yixiaoyuanSignedIn = false,
                yixiaoyuanMobile = "",
                waterCodeInfo = null,
                showerFeedback = null,
                showerFeedbackError = false,
                statusMessage = "已退出易校园账号",
                statusError = false,
            )
        }
    }

    fun saveShiroJidAndRefresh(shiroJid: String) {
        val clean = shiroJid.trim()
        if (clean.isNotBlank()) {
            campusLife.saveYixiaoyuan(campusLife.yixiaoyuan(), clean)
        }
        refreshShowerCode(forceGenerate = false)
    }

    /**
     * 拉取或刷新浴室 8 位动态用水码。
     *
     * 若尚未配置 `shiroJID` 或服务端返回 204 过期，会自动用已登录账号的 `ymId` 无感换票并重试。
     * [forceGenerate] 为 true 时主动调用 `insertRanCode` 申请新码。
     */
    fun refreshShowerCode(forceGenerate: Boolean = false) {
        if (_state.value.yixiaoyuanBusy) return
        val cred = campusLife.yixiaoyuanCredential()
        val initialJid = campusLife.yixiaoyuanShiroJid()
        if (initialJid.isBlank() && !cred.signedIn) {
            _state.update {
                it.copy(
                    statusMessage = "请先登录易校园账号以获取洗澡用水码",
                    statusError = true,
                    showerFeedback = "请先登录易校园账号",
                    showerFeedbackError = true,
                )
            }
            return
        }
        val previousCode = campusLife.yixiaoyuan().showerCode
        _state.update {
            it.copy(
                yixiaoyuanBusy = true,
                statusMessage = null,
                statusError = false,
                showerFeedback = null,
                showerFeedbackError = false,
            )
        }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                fetchOrRenewWaterCode(cred, initialJid, forceGenerate)
            }
            when (res) {
                is YixiaoyuanResult.Success -> applyWaterCode(res.data, previousCode, forceGenerate)
                is YixiaoyuanResult.SessionExpired ->
                    _state.update {
                        it.copy(
                            yixiaoyuanBusy = false,
                            statusMessage = "易校园会话已过期，请重新登录易校园账号",
                            statusError = true,
                            showerFeedback = "会话已过期，点此重登",
                            showerFeedbackError = true,
                        )
                    }
                is YixiaoyuanResult.Error ->
                    _state.update {
                        it.copy(
                            yixiaoyuanBusy = false,
                            statusMessage = res.message,
                            statusError = true,
                            showerFeedback = res.message,
                            showerFeedbackError = true,
                        )
                    }
            }
        }
    }

    /** 停用当前正在生效的 8 位浴室用水码（`/app/hzsun/cloudwater/cancelRanCode`）。 */
    fun cancelShowerCode() {
        if (_state.value.yixiaoyuanBusy) return
        val currentCode = campusLife.yixiaoyuan().showerCode.trim()
        if (currentCode.isBlank()) {
            _state.update {
                it.copy(
                    showerFeedback = "当前没有启用中的用水码",
                    showerFeedbackError = false,
                )
            }
            return
        }
        val cred = campusLife.yixiaoyuanCredential()
        val initialJid = campusLife.yixiaoyuanShiroJid()
        if (initialJid.isBlank() && !cred.signedIn) {
            _state.update {
                it.copy(
                    statusMessage = "请先登录易校园账号后再停用用水码",
                    statusError = true,
                    showerFeedback = "请先登录易校园账号",
                    showerFeedbackError = true,
                )
            }
            return
        }
        _state.update {
            it.copy(
                yixiaoyuanBusy = true,
                statusMessage = null,
                statusError = false,
                showerFeedback = null,
                showerFeedbackError = false,
            )
        }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                withValidShiroJid(cred, initialJid) { jid ->
                    waterClient.cancelCode(jid, currentCode)
                }
            }
            when (res) {
                is YixiaoyuanResult.Success -> {
                    val cleared = campusLife.yixiaoyuan().copy(showerCode = "", showerCodeAt = 0L)
                    campusLife.saveYixiaoyuan(cleared)
                    _state.update {
                        it.copy(
                            yixiaoyuan = cleared,
                            waterCodeInfo = null,
                            yixiaoyuanBusy = false,
                            statusMessage = "已停用当前洗澡用水码",
                            statusError = false,
                            showerFeedback = "已停用，需要时可重新获取",
                            showerFeedbackError = false,
                        )
                    }
                }
                is YixiaoyuanResult.SessionExpired ->
                    _state.update {
                        it.copy(
                            yixiaoyuanBusy = false,
                            statusMessage = "易校园会话已过期，请重新登录易校园账号",
                            statusError = true,
                            showerFeedback = "会话已过期，点此重登",
                            showerFeedbackError = true,
                        )
                    }
                is YixiaoyuanResult.Error ->
                    _state.update {
                        it.copy(
                            yixiaoyuanBusy = false,
                            statusMessage = res.message,
                            statusError = true,
                            showerFeedback = res.message,
                            showerFeedbackError = true,
                        )
                    }
            }
        }
    }

    private fun <T> withValidShiroJid(
        cred: YixiaoyuanCredential,
        initialJid: String,
        block: (String) -> YixiaoyuanResult<T>,
    ): YixiaoyuanResult<T> {
        var jid = initialJid
        if (jid.isBlank()) {
            if (!cred.signedIn) return YixiaoyuanResult.Error("请先登录易校园账号")
            when (val exchanged = waterClient.exchangeShiroJid(cred)) {
                is YixiaoyuanResult.Success -> {
                    jid = exchanged.data
                    campusLife.saveYixiaoyuan(campusLife.yixiaoyuan(), jid)
                }
                is YixiaoyuanResult.SessionExpired -> return exchanged
                is YixiaoyuanResult.Error -> return exchanged
            }
        }

        val firstTry = block(jid)
        if (firstTry !is YixiaoyuanResult.SessionExpired || !cred.signedIn) {
            return firstTry
        }

        return when (val renewed = waterClient.exchangeShiroJid(cred)) {
            is YixiaoyuanResult.Success -> {
                val freshJid = renewed.data
                campusLife.saveYixiaoyuan(campusLife.yixiaoyuan(), freshJid)
                block(freshJid)
            }
            is YixiaoyuanResult.SessionExpired -> renewed
            is YixiaoyuanResult.Error -> renewed
        }
    }

    private fun fetchOrRenewWaterCode(
        cred: YixiaoyuanCredential,
        initialJid: String,
        forceGenerate: Boolean,
    ): YixiaoyuanResult<WaterCode> = withValidShiroJid(cred, initialJid) { jid ->
        if (forceGenerate) waterClient.insertCode(jid)
        else waterClient.queryCode(jid, autoGenerateIfEmpty = true)
    }

    private fun applyWaterCode(
        code: WaterCode,
        previousCode: String = "",
        forceGenerate: Boolean = false,
    ) {
        val next = campusLife.yixiaoyuan().copy(
            showerCode = code.code,
            showerCodeAt = code.fetchedAt,
        )
        campusLife.saveYixiaoyuan(next)
        val validity = code.validityText()
        val suffix = if (validity.isNotBlank()) "（$validity）" else ""
        val tip = when {
            forceGenerate && previousCode.isNotBlank() && previousCode != code.code -> "已换新码$suffix"
            forceGenerate && previousCode.isNotBlank() && previousCode == code.code -> "当前码仍有效$suffix"
            else -> "用水码已更新$suffix"
        }
        _state.update {
            it.copy(
                yixiaoyuan = next,
                waterCodeInfo = code,
                yixiaoyuanBusy = false,
                statusMessage = tip,
                statusError = false,
                showerFeedback = tip,
                showerFeedbackError = false,
            )
        }
    }

    /** 账号密码直连登录易校园，无需跳转官方 App，成功后自动换取用水码（并按开关决定是否查余额）。 */
    fun loginYixiaoyuanByPassword(mobile: String, password: String) {
        if (_state.value.yixiaoyuanBusy) return
        if (mobile.isBlank() || password.isBlank()) {
            _state.update { it.copy(statusMessage = "请填写手机号与登录密码", statusError = true) }
            return
        }
        _state.update { it.copy(yixiaoyuanBusy = true, statusMessage = null, statusError = false) }
        viewModelScope.launch {
            val deviceId = campusLife.yixiaoyuanDeviceId()
            val r = withContext(Dispatchers.IO) {
                yixiaoyuanClient.loginByPassword(mobile, password, deviceId)
            }
            handleYixiaoyuanLoginResult(r)
        }
    }

    /**
     * 短信验证码登录易校园，换取 token / uuToken 后自动同步用水码。
     *
     * 验证码需在官方 App「获取验证码」后填入；推荐优先使用 [loginYixiaoyuanByPassword]。
     */
    fun loginYixiaoyuan(mobile: String, verificationCode: String) {
        if (_state.value.yixiaoyuanBusy) return
        if (mobile.isBlank() || verificationCode.isBlank()) {
            _state.update { it.copy(statusMessage = "请填写手机号与验证码", statusError = true) }
            return
        }
        _state.update { it.copy(yixiaoyuanBusy = true, statusMessage = null, statusError = false) }
        viewModelScope.launch {
            val deviceId = campusLife.yixiaoyuanDeviceId()
            val r = withContext(Dispatchers.IO) {
                yixiaoyuanClient.loginByVerificationCode(mobile, verificationCode, deviceId)
            }
            handleYixiaoyuanLoginResult(r)
        }
    }

    private suspend fun handleYixiaoyuanLoginResult(r: YixiaoyuanResult<YixiaoyuanCredential>) {
        when (r) {
            is YixiaoyuanResult.Success -> {
                val cred = r.data
                campusLife.saveYixiaoyuanCredential(cred)
                val initialState = campusLife.yixiaoyuan().copy(ymId = cred.ymId)
                campusLife.saveYixiaoyuan(initialState)
                val showBalance = initialState.showBalanceCard
                _state.update {
                    it.copy(
                        yixiaoyuan = initialState,
                        yixiaoyuanSignedIn = cred.signedIn,
                        yixiaoyuanMobile = cred.mobile,
                        yixiaoyuanBusy = true,
                        statusMessage = if (showBalance) {
                            "易校园登录成功，正在同步用水码与余额…"
                        } else {
                            "易校园登录成功，正在同步 8 位洗澡用水码…"
                        },
                        statusError = false,
                    )
                }

                val (waterRes, balanceRes) = withContext(Dispatchers.IO) {
                    val wRes = fetchOrRenewWaterCode(
                        cred = cred,
                        initialJid = "",
                        forceGenerate = false,
                    )
                    if (wRes is YixiaoyuanResult.Success) {
                        val updated = campusLife.yixiaoyuan().copy(
                            showerCode = wRes.data.code,
                            showerCodeAt = wRes.data.fetchedAt,
                        )
                        campusLife.saveYixiaoyuan(updated)
                    }
                    val bRes = if (campusLife.yixiaoyuan().showBalanceCard) {
                        yixiaoyuanClient.getCardMoney(cred).also { moneyRes ->
                            if (moneyRes is YixiaoyuanResult.Success) {
                                val updated = campusLife.yixiaoyuan().copy(cardBalanceYuan = moneyRes.data)
                                campusLife.saveYixiaoyuan(updated)
                            }
                        }
                    } else {
                        null
                    }
                    wRes to bRes
                }

                val finalYxy = campusLife.yixiaoyuan()
                val waterErr = (waterRes as? YixiaoyuanResult.Error)?.message
                    ?: (waterRes as? YixiaoyuanResult.SessionExpired)?.message
                val waterOk = waterRes is YixiaoyuanResult.Success
                val balanceVal = (balanceRes as? YixiaoyuanResult.Success)?.data
                val (finalMsg, finalErr) = if (!finalYxy.showBalanceCard) {
                    if (waterOk) {
                        "已登录易校园，8 位洗澡用水码已就绪" to false
                    } else {
                        "已登录易校园，自动获取用水码失败：${waterErr ?: "请稍后重试"}" to true
                    }
                } else {
                    when {
                        waterOk && balanceVal != null -> {
                            val text = if (finalYxy.isLowBalance) {
                                "已登录易校园，用水码已就绪；余额 ¥%.2f，已低于提醒阈值".format(balanceVal)
                            } else {
                                "已登录易校园，用水码已就绪 · 余额 ¥%.2f".format(balanceVal)
                            }
                            text to false
                        }
                        waterOk -> "已登录易校园，用水码已就绪；余额获取失败（不影响用水码）" to false
                        balanceVal != null ->
                            "已登录易校园（余额 ¥%.2f），自动获取用水码失败：${waterErr ?: "请稍后重试"}".format(balanceVal) to true
                        else -> "已登录易校园，同步失败：${waterErr ?: "请稍后重试"}" to true
                    }
                }

                _state.update {
                    it.copy(
                        yixiaoyuan = finalYxy,
                        waterCodeInfo = (waterRes as? YixiaoyuanResult.Success)?.data ?: it.waterCodeInfo,
                        yixiaoyuanBusy = false,
                        statusMessage = finalMsg,
                        statusError = finalErr,
                    )
                }
            }
            is YixiaoyuanResult.SessionExpired ->
                _state.update { it.copy(yixiaoyuanBusy = false, statusMessage = r.message, statusError = true) }
            is YixiaoyuanResult.Error ->
                _state.update { it.copy(yixiaoyuanBusy = false, statusMessage = r.message, statusError = true) }
        }
    }

    /** 刷新一卡通余额（仅在用户开启生活页余额展示时触发并提示）。 */
    fun refreshCardBalance() {
        if (_state.value.yixiaoyuanBusy) return
        if (!campusLife.yixiaoyuan().showBalanceCard) return
        val credential = campusLife.yixiaoyuanCredential()
        if (!credential.signedIn) {
            _state.update { it.copy(statusMessage = "请先登录易校园账号", statusError = true) }
            return
        }
        _state.update { it.copy(yixiaoyuanBusy = true, statusMessage = null, statusError = false) }
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { yixiaoyuanClient.getCardMoney(credential) }) {
                is YixiaoyuanResult.Success -> {
                    val cur = campusLife.yixiaoyuan()
                    val next = cur.copy(cardBalanceYuan = r.data)
                    campusLife.saveYixiaoyuan(next)
                    val low = next.isLowBalance
                    val msg = if (cur.showBalanceCard) {
                        if (low) "余额 ¥%.2f，已低于提醒阈值".format(r.data)
                        else "余额已更新：¥%.2f".format(r.data)
                    } else {
                        null
                    }
                    _state.update {
                        it.copy(
                            yixiaoyuan = next,
                            yixiaoyuanBusy = false,
                            statusMessage = msg,
                            statusError = false,
                        )
                    }
                }
                is YixiaoyuanResult.SessionExpired ->
                    _state.update {
                        it.copy(
                            yixiaoyuanBusy = false,
                            statusMessage = "易校园登录已失效，请重新登录易校园账号",
                            statusError = true,
                        )
                    }
                is YixiaoyuanResult.Error ->
                    _state.update { it.copy(yixiaoyuanBusy = false, statusMessage = r.message, statusError = true) }
            }
        }
    }

    fun yixiaoyuanSignedIn(): Boolean = campusLife.yixiaoyuanCredential().signedIn

    // ---------------- 吹风机：U净 + 合肥海鸟书签 ----------------

    fun saveUjingJwt(jwt: String) {
        val clean = UjingClient.cleanJwt(jwt)
        campusLife.saveUjingJwt(clean)
        _state.update {
            it.copy(
                ujingConfigured = clean.isNotBlank(),
                ujingMessage = if (clean.isNotBlank()) "U 净 JWT 已加密保存" else "已清除 U 净凭证",
                ujingError = false,
                statusMessage = "U 净凭证已加密保存到本机",
                statusError = false,
            )
        }
    }

    fun logoutUjing() {
        campusLife.saveUjingJwt("")
        _state.update {
            it.copy(
                ujingConfigured = false,
                ujingStores = emptyList(),
                ujingDevices = emptyList(),
                ujingScannedDevice = null,
                ujingMessage = "已退出 U 净账号",
                ujingError = false,
            )
        }
    }

    fun updateUjingKeyword(keyword: String) {
        _state.update { it.copy(ujingKeyword = keyword) }
        campusLife.saveUjingKeyword(keyword)
    }

    fun sendUjingCaptcha(mobile: String, onSent: () -> Unit = {}) {
        val cleanPhone = mobile.filter(Char::isDigit).take(11)
        if (!cleanPhone.matches(Regex("^1\\d{10}$"))) {
            _state.update { it.copy(ujingMessage = "请输入 11 位手机号", ujingError = true) }
            return
        }
        if (_state.value.ujingBusy) return
        _state.update { it.copy(ujingBusy = true, ujingMessage = null, ujingError = false) }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { ujingClient.sendSmsCaptcha(cleanPhone) }
            when (res) {
                is UjingResult.Success -> {
                    _state.update { it.copy(ujingBusy = false, ujingMessage = "短信验证码已发送，请查收", ujingError = false) }
                    onSent()
                }
                is UjingResult.SessionExpired -> {
                    _state.update { it.copy(ujingBusy = false, ujingMessage = res.reason, ujingError = true) }
                }
                is UjingResult.Error -> {
                    _state.update { it.copy(ujingBusy = false, ujingMessage = res.message, ujingError = true) }
                }
            }
        }
    }

    fun loginUjing(mobile: String, captcha: String) {
        val cleanPhone = mobile.filter(Char::isDigit).take(11)
        val cleanCode = captcha.trim()
        if (!cleanPhone.matches(Regex("^1\\d{10}$")) || cleanCode.isBlank()) {
            _state.update { it.copy(ujingMessage = "请填写手机号与短信验证码", ujingError = true) }
            return
        }
        if (_state.value.ujingBusy) return
        _state.update { it.copy(ujingBusy = true, ujingMessage = null, ujingError = false) }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { ujingClient.loginWithSms(cleanPhone, cleanCode) }
            when (res) {
                is UjingResult.Success -> {
                    campusLife.saveUjingJwt(res.data)
                    _state.update {
                        it.copy(
                            ujingBusy = false,
                            ujingConfigured = true,
                            ujingMessage = "U 净登录成功，点击下方按钮可查看龙子湖门店空闲",
                            ujingError = false,
                        )
                    }
                    searchUjingStores()
                }
                is UjingResult.SessionExpired -> {
                    _state.update { it.copy(ujingBusy = false, ujingMessage = res.reason, ujingError = true) }
                }
                is UjingResult.Error -> {
                    _state.update { it.copy(ujingBusy = false, ujingMessage = res.message, ujingError = true) }
                }
            }
        }
    }

    fun searchUjingStores(keyword: String = _state.value.ujingKeyword) {
        val jwt = campusLife.ujingJwt()
        if (jwt.isBlank()) {
            _state.update { it.copy(ujingConfigured = false, ujingMessage = "请先登录 U 净账号", ujingError = true) }
            return
        }
        if (_state.value.ujingBusy) return
        campusLife.saveUjingKeyword(keyword)
        _state.update { it.copy(ujingBusy = true, ujingKeyword = keyword, ujingMessage = null, ujingError = false) }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { ujingClient.nearbyStores(jwt, keyword = keyword) }
            when (res) {
                is UjingResult.Success -> {
                    val selectedId = _state.value.ujingSelectedStore?.id
                    val updatedSelected = res.data.firstOrNull { it.id == selectedId } ?: _state.value.ujingSelectedStore
                    _state.update {
                        it.copy(
                            ujingBusy = false,
                            ujingStores = res.data,
                            ujingSelectedStore = updatedSelected,
                            ujingMessage = if (res.data.isEmpty()) "2 km 内暂未匹配到门店，可清空关键词再查" else "查到 ${res.data.size} 家 U 净门店",
                            ujingError = false,
                        )
                    }
                }
                is UjingResult.SessionExpired -> handleUjingExpired(res.reason)
                is UjingResult.Error -> {
                    _state.update { it.copy(ujingBusy = false, ujingMessage = res.message, ujingError = true) }
                }
            }
        }
    }

    fun selectUjingStore(store: UjingStoreSummary) {
        campusLife.saveUjingStore(store.id, store.name)
        _state.update { it.copy(ujingSelectedStore = store) }
        refreshUjingStore(store.id)
    }

    fun refreshUjingStore(storeId: String = _state.value.ujingSelectedStore?.id.orEmpty()) {
        if (storeId.isBlank()) {
            searchUjingStores()
            return
        }
        val jwt = campusLife.ujingJwt()
        if (jwt.isBlank()) {
            handleUjingExpired("请先登录 U 净账号")
            return
        }
        if (_state.value.ujingBusy) return
        _state.update { it.copy(ujingBusy = true, ujingMessage = null, ujingError = false) }
        viewModelScope.launch {
            val devRes = withContext(Dispatchers.IO) { ujingClient.storeDevices(jwt, storeId) }
            when (devRes) {
                is UjingResult.Success -> {
                    _state.update {
                        it.copy(
                            ujingBusy = false,
                            ujingDevices = devRes.data,
                            ujingMessage = if (devRes.data.isEmpty()) "该门店空闲计数来自门店看板；查看单机状态可扫机身码" else "已更新 ${devRes.data.size} 台设备状态",
                            ujingError = false,
                        )
                    }
                }
                is UjingResult.SessionExpired -> handleUjingExpired(devRes.reason)
                is UjingResult.Error -> {
                    _state.update { it.copy(ujingBusy = false, ujingMessage = devRes.message, ujingError = true) }
                }
            }
        }
    }

    fun scanUjingQr(qrCode: String) {
        val clean = qrCode.trim()
        if (clean.isBlank()) return
        val jwt = campusLife.ujingJwt()
        if (jwt.isBlank()) {
            handleUjingExpired("扫码查机需先登录 U 净账号")
            return
        }
        if (_state.value.ujingBusy) return
        _state.update { it.copy(ujingBusy = true, ujingMessage = null, ujingError = false) }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { ujingClient.scanDeviceQr(jwt, clean) }
            when (res) {
                is UjingResult.Success -> {
                    _state.update {
                        it.copy(
                            ujingBusy = false,
                            ujingScannedDevice = res.data,
                            ujingMessage = "已读取：${res.data.name}（${res.data.statusText}）",
                            ujingError = false,
                        )
                    }
                }
                is UjingResult.SessionExpired -> handleUjingExpired(res.reason)
                is UjingResult.Error -> {
                    _state.update { it.copy(ujingBusy = false, ujingMessage = res.message, ujingError = true) }
                }
            }
        }
    }

    private fun handleUjingExpired(reason: String) {
        campusLife.saveUjingJwt("")
        _state.update {
            it.copy(
                ujingBusy = false,
                ujingConfigured = false,
                ujingMessage = reason,
                ujingError = true,
            )
        }
    }

    fun addHairdryerBookmark(label: String, payload: String) {
        campusLife.addHairdryerBookmark(label, payload)
        _state.update {
            it.copy(
                hairdryerBookmarks = campusLife.hairdryerBookmarks(),
                statusMessage = "已收藏海鸟吹风机码：$label",
                statusError = false,
            )
        }
    }

    fun removeHairdryerBookmark(id: String) {
        campusLife.removeHairdryerBookmark(id)
        _state.update { it.copy(hairdryerBookmarks = campusLife.hairdryerBookmarks()) }
    }

    // ---------------- 快递取件码 ----------------

    fun ingestClipboardOrRawText(rawText: String): Boolean {
        val added = parcels.ingestText(rawText, source = "手动识别")
        if (added != null) {
            _state.update {
                it.copy(
                    statusMessage = "已识别取件码：${added.code}（${added.station}）",
                    statusError = false,
                )
            }
            return true
        }
        _state.update {
            it.copy(
                statusMessage = "未从文本中识别到取件码，可直接手动输入或添加自定义正则",
                statusError = true,
            )
        }
        return false
    }

    fun addManualParcel(code: String, station: String, carrier: String) {
        val added = parcels.addManual(code, station, carrier, source = "手动录入")
        if (added != null) {
            _state.update { it.copy(statusMessage = "已添加待取快递：${added.code}", statusError = false) }
        }
    }

    fun markParcelPickedUp(id: String) {
        parcels.markPickedUp(id, true)
        _state.update { it.copy(statusMessage = "已标记取件并归档", statusError = false) }
    }

    fun scanRecentSms() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                ParcelSmsScanner.scanRecentDays(app, parcels, days = 7)
            }
            _state.update {
                it.copy(
                    statusMessage = if (count > 0) "从近 7 天短信新增 $count 件待取快递" else "近 7 天短信中没有新的未收录取件码",
                    statusError = false,
                )
            }
        }
    }

    fun addCustomParcelRule(stationLabel: String, pattern: String) {
        val ok = parcels.addCustomRule(stationLabel, pattern)
        _state.update {
            it.copy(
                statusMessage = if (ok) "已添加「$stationLabel」正则规则" else "正则表达式格式无效，请检查语法",
                statusError = !ok,
            )
        }
    }

    fun removeCustomParcelRule(id: String) {
        parcels.removeCustomRule(id)
    }

    // ---------------- 快捷入口 ----------------

    fun addQuickShortcut(title: String, subtitle: String, uri: String) {
        campusLife.addShortcut(title, subtitle, uri)
        _state.update {
            it.copy(
                customShortcuts = campusLife.shortcuts(),
                statusMessage = "已添加快捷入口：$title",
                statusError = false,
            )
        }
    }

    fun removeQuickShortcut(id: String) {
        campusLife.removeShortcut(id)
        _state.update { it.copy(customShortcuts = campusLife.shortcuts()) }
    }
}
