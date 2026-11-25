package com.hmu.muye.data.campus

import android.content.Context
import com.fengkao.timetable.data.local.Crypto
import com.hmu.muye.data.yixiaoyuan.YixiaoyuanClient
import com.hmu.muye.data.yixiaoyuan.YixiaoyuanCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 校园生活本地配置与加密凭据仓库（校区 / 校园网 / 易校园用水码与余额 / 海鸟书签 / 快捷入口 / 上课提醒）。
 */
class CampusLifeStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _campus = MutableStateFlow(loadCampus())
    val campusFlow: Flow<HmuCampus> = _campus.asStateFlow()

    private val _portal = MutableStateFlow(loadPortal())
    val portalFlow: Flow<PortalConfig> = _portal.asStateFlow()

    private val _yixiaoyuan = MutableStateFlow(loadYixiaoyuan())
    val yixiaoyuanFlow: Flow<YixiaoyuanState> = _yixiaoyuan.asStateFlow()

    private val _bookmarks = MutableStateFlow(loadBookmarks())
    val bookmarksFlow: Flow<List<HairdryerBookmark>> = _bookmarks.asStateFlow()

    private val _shortcuts = MutableStateFlow(loadShortcuts())
    val shortcutsFlow: Flow<List<QuickShortcut>> = _shortcuts.asStateFlow()

    private val _reminders = MutableStateFlow(loadReminders())
    val remindersFlow: Flow<ClassReminderPrefs> = _reminders.asStateFlow()

    fun campus(): HmuCampus = _campus.value
    fun portal(): PortalConfig = _portal.value
    fun yixiaoyuan(): YixiaoyuanState = _yixiaoyuan.value
    fun hairdryerBookmarks(): List<HairdryerBookmark> = _bookmarks.value
    fun shortcuts(): List<QuickShortcut> = _shortcuts.value
    fun classReminders(): ClassReminderPrefs = _reminders.value

    fun portalPassword(): String = prefs.getString(KEY_PORTAL_PWD_ENC, null)
        ?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }
        .orEmpty()

    fun yixiaoyuanShiroJid(): String = prefs.getString(KEY_YXY_SHIRO_ENC, null)
        ?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }
        .orEmpty()

    fun ujingJwt(): String = prefs.getString(KEY_UJING_JWT_ENC, null)
        ?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }
        .orEmpty()

    fun libraryUrl(): String = prefs.getString(KEY_LIBRARY_URL, "").orEmpty()

    fun setLibraryUrl(url: String) {
        prefs.edit().putString(KEY_LIBRARY_URL, url.trim()).apply()
    }

    fun setCampus(campus: HmuCampus) {
        prefs.edit().putString(KEY_CAMPUS, campus.name).apply()
        _campus.value = campus
    }

    fun savePortalConfig(config: PortalConfig, password: String?) {
        val editor = prefs.edit()
            .putString(KEY_PORTAL_JSON, json.encodeToString(PortalConfig.serializer(), config))
        if (password != null) {
            if (password.isBlank()) editor.remove(KEY_PORTAL_PWD_ENC)
            else editor.putString(KEY_PORTAL_PWD_ENC, runCatching { Crypto.encrypt(password) }.getOrDefault(""))
        }
        editor.apply()
        _portal.value = config
    }

    fun updatePortalStatus(success: Boolean, at: Long = System.currentTimeMillis()) {
        val next = _portal.value.copy(lastAuthAt = at, lastSuccess = success)
        prefs.edit().putString(KEY_PORTAL_JSON, json.encodeToString(PortalConfig.serializer(), next)).apply()
        _portal.value = next
    }

    fun saveYixiaoyuan(state: YixiaoyuanState, shiroJid: String? = null) {
        val editor = prefs.edit()
        var next = state
        if (shiroJid != null) {
            if (shiroJid.isBlank()) {
                editor.remove(KEY_YXY_SHIRO_ENC)
                next = state.copy(hasCredential = false)
            } else {
                editor.putString(KEY_YXY_SHIRO_ENC, runCatching { Crypto.encrypt(shiroJid.trim()) }.getOrDefault(""))
                next = state.copy(hasCredential = true)
            }
        }
        editor.putString(KEY_YXY_JSON, json.encodeToString(YixiaoyuanState.serializer(), next)).apply()
        _yixiaoyuan.value = next
    }

    /**
     * 读取易校园登录凭据（token / uuToken / ymId / deviceId）。
     * 凭据整体加密存储；[yyDeviceId] 在首次使用时生成并长期固定。
     */
    fun yixiaoyuanCredential(): YixiaoyuanCredential {
        val raw = prefs.getString(KEY_YXY_CRED_ENC, null)
            ?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }
            .orEmpty()
        val deviceId = yixiaoyuanDeviceId()
        if (raw.isBlank()) return YixiaoyuanCredential(deviceId = deviceId)
        return runCatching {
            json.decodeFromString(YixiaoyuanCredential.serializer(), raw)
        }.getOrNull()?.copy(deviceId = deviceId) ?: YixiaoyuanCredential(deviceId = deviceId)
    }

    /** 保存登录凭据；传空 token 等价于退出登录。 */
    fun saveYixiaoyuanCredential(credential: YixiaoyuanCredential) {
        val editor = prefs.edit()
        if (credential.token.isBlank() || credential.uuToken.isBlank()) {
            editor.remove(KEY_YXY_CRED_ENC)
        } else {
            val encrypted = runCatching { Crypto.encrypt(json.encodeToString(YixiaoyuanCredential.serializer(), credential)) }
                .getOrDefault("")
            if (encrypted.isBlank()) editor.remove(KEY_YXY_CRED_ENC) else editor.putString(KEY_YXY_CRED_ENC, encrypted)
        }
        editor.apply()
    }

    /** 稳定的设备标识，首次调用时生成 `ym-` + 32 位十六进制。 */
    fun yixiaoyuanDeviceId(): String {
        prefs.getString(KEY_YXY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = YixiaoyuanClient.newDeviceId()
        prefs.edit().putString(KEY_YXY_DEVICE_ID, fresh).apply()
        return fresh
    }

    fun saveUjingJwt(jwt: String) {
        val clean = jwt.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        val editor = prefs.edit()
        if (clean.isBlank()) editor.remove(KEY_UJING_JWT_ENC)
        else editor.putString(KEY_UJING_JWT_ENC, runCatching { Crypto.encrypt(clean) }.getOrDefault(""))
        editor.apply()
    }

    fun ujingStoreId(): String = prefs.getString(KEY_UJING_STORE_ID, "").orEmpty()
    fun ujingStoreName(): String = prefs.getString(KEY_UJING_STORE_NAME, "").orEmpty()
    fun ujingKeyword(): String = prefs.getString(KEY_UJING_KEYWORD, "河南牧业").orEmpty()

    fun saveUjingStore(id: String, name: String) {
        prefs.edit()
            .putString(KEY_UJING_STORE_ID, id.trim())
            .putString(KEY_UJING_STORE_NAME, name.trim())
            .apply()
    }

    fun saveUjingKeyword(keyword: String) {
        prefs.edit().putString(KEY_UJING_KEYWORD, keyword.trim()).apply()
    }

    fun addHairdryerBookmark(label: String, qrPayload: String) {
        val cleanLabel = label.trim().ifBlank { "海鸟吹风机" }
        val cleanPayload = qrPayload.trim()
        if (cleanPayload.isBlank()) return
        val next = (listOf(HairdryerBookmark(UUID.randomUUID().toString(), cleanLabel, cleanPayload)) +
            _bookmarks.value.filterNot { it.qrPayload == cleanPayload }).take(20)
        prefs.edit().putString(KEY_BOOKMARKS, json.encodeToString(ListSerializer(HairdryerBookmark.serializer()), next)).apply()
        _bookmarks.value = next
    }

    fun removeHairdryerBookmark(id: String) {
        val next = _bookmarks.value.filterNot { it.id == id }
        prefs.edit().putString(KEY_BOOKMARKS, json.encodeToString(ListSerializer(HairdryerBookmark.serializer()), next)).apply()
        _bookmarks.value = next
    }

    fun addShortcut(title: String, subtitle: String, targetUri: String) {
        if (title.isBlank() || targetUri.isBlank()) return
        val next = _shortcuts.value + QuickShortcut(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            subtitle = subtitle.trim().ifBlank { targetUri.trim() },
            targetUri = targetUri.trim(),
            builtIn = false,
        )
        prefs.edit().putString(KEY_SHORTCUTS, json.encodeToString(ListSerializer(QuickShortcut.serializer()), next)).apply()
        _shortcuts.value = next
    }

    fun removeShortcut(id: String) {
        val next = _shortcuts.value.filterNot { it.id == id && !it.builtIn }
        prefs.edit().putString(KEY_SHORTCUTS, json.encodeToString(ListSerializer(QuickShortcut.serializer()), next)).apply()
        _shortcuts.value = next
    }

    fun saveClassReminders(next: ClassReminderPrefs) {
        prefs.edit().putString(KEY_REMINDERS, json.encodeToString(ClassReminderPrefs.serializer(), next)).apply()
        _reminders.value = next
    }

    private fun loadCampus(): HmuCampus = runCatching {
        HmuCampus.valueOf(prefs.getString(KEY_CAMPUS, HmuCampus.LONGZIHU.name) ?: HmuCampus.LONGZIHU.name)
    }.getOrDefault(HmuCampus.LONGZIHU)

    private fun loadPortal(): PortalConfig {
        val raw = prefs.getString(KEY_PORTAL_JSON, null) ?: return PortalConfig()
        return runCatching { json.decodeFromString(PortalConfig.serializer(), raw) }.getOrDefault(PortalConfig())
    }

    private fun loadYixiaoyuan(): YixiaoyuanState {
        val raw = prefs.getString(KEY_YXY_JSON, null) ?: return YixiaoyuanState()
        return runCatching { json.decodeFromString(YixiaoyuanState.serializer(), raw) }.getOrDefault(YixiaoyuanState())
    }

    private fun loadBookmarks(): List<HairdryerBookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(HairdryerBookmark.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    private fun loadShortcuts(): List<QuickShortcut> {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(QuickShortcut.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    private fun loadReminders(): ClassReminderPrefs {
        val raw = prefs.getString(KEY_REMINDERS, null) ?: return ClassReminderPrefs()
        return runCatching { json.decodeFromString(ClassReminderPrefs.serializer(), raw) }
            .getOrDefault(ClassReminderPrefs())
    }

    companion object {
        private const val PREFS = "hmu_campus_life"
        private const val KEY_CAMPUS = "campus"
        private const val KEY_PORTAL_JSON = "portal_json"
        private const val KEY_PORTAL_PWD_ENC = "portal_pwd_enc"
        private const val KEY_YXY_JSON = "yxy_json"
        private const val KEY_YXY_SHIRO_ENC = "yxy_shiro_enc"
        private const val KEY_YXY_CRED_ENC = "yxy_cred_enc"
        private const val KEY_YXY_DEVICE_ID = "yxy_device_id"
        private const val KEY_UJING_JWT_ENC = "ujing_jwt_enc"
        private const val KEY_UJING_STORE_ID = "ujing_store_id"
        private const val KEY_UJING_STORE_NAME = "ujing_store_name"
        private const val KEY_UJING_KEYWORD = "ujing_keyword"
        private const val KEY_LIBRARY_URL = "library_url"
        private const val KEY_BOOKMARKS = "hairdryer_bookmarks"
        private const val KEY_SHORTCUTS = "quick_shortcuts"
        private const val KEY_REMINDERS = "class_reminders"
    }
}
