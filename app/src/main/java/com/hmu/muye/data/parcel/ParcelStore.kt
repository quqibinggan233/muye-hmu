package com.hmu.muye.data.parcel

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 快递取件码与自定义正则本地仓库（纯本地 SharedPreferences + 内存流）。
 */
class ParcelStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _items = MutableStateFlow(loadItems())
    val itemsFlow: Flow<List<ParcelItem>> = _items.asStateFlow()

    private val _rules = MutableStateFlow(loadRules())
    val rulesFlow: Flow<List<CustomParcelRule>> = _rules.asStateFlow()

    fun allItems(): List<ParcelItem> = _items.value

    fun pendingItems(): List<ParcelItem> = _items.value.filter { !it.pickedUp }

    fun customRules(): List<CustomParcelRule> = _rules.value

    /**
     * 尝试解析文本并新增取件码（按「取件码 + 驿站」去重，避免通知重复抓取）。
     */
    @Synchronized
    fun ingestText(rawText: String, source: String): ParcelItem? {
        val parsed = ParcelParser.parse(rawText, _rules.value) ?: return null
        return addManual(
            code = parsed.code,
            station = parsed.station,
            carrier = parsed.carrier,
            rawText = rawText.trim(),
            source = source,
        )
    }

    @Synchronized
    fun addManual(
        code: String,
        station: String,
        carrier: String = "",
        rawText: String = "",
        source: String = "手动录入",
    ): ParcelItem? {
        val cleanCode = code.trim()
        if (cleanCode.isBlank()) return null
        val cleanStation = station.trim().ifBlank { "校园驿站" }
        val existing = _items.value.firstOrNull {
            !it.pickedUp && it.code.equals(cleanCode, ignoreCase = true) && it.station == cleanStation
        }
        if (existing != null) return null
        val item = ParcelItem(
            id = UUID.randomUUID().toString(),
            code = cleanCode,
            station = cleanStation,
            carrier = carrier.trim(),
            rawText = rawText,
            source = source,
            receivedAt = System.currentTimeMillis(),
            pickedUp = false,
        )
        // 保留最近 100 条记录，防止长期积累过大
        val next = (listOf(item) + _items.value).take(100)
        saveItems(next)
        return item
    }

    @Synchronized
    fun markPickedUp(id: String, pickedUp: Boolean = true) {
        val next = _items.value.map { if (it.id == id) it.copy(pickedUp = pickedUp) else it }
        saveItems(next)
    }

    @Synchronized
    fun remove(id: String) {
        saveItems(_items.value.filterNot { it.id == id })
    }

    @Synchronized
    fun clearArchived() {
        saveItems(_items.value.filterNot { it.pickedUp })
    }

    @Synchronized
    fun addCustomRule(stationLabel: String, pattern: String): Boolean {
        if (stationLabel.isBlank() || pattern.isBlank()) return false
        if (runCatching { Regex(pattern) }.isFailure) return false
        val rule = CustomParcelRule(
            id = UUID.randomUUID().toString(),
            stationLabel = stationLabel.trim(),
            pattern = pattern.trim(),
        )
        saveRules(_rules.value + rule)
        return true
    }

    @Synchronized
    fun removeCustomRule(id: String) {
        saveRules(_rules.value.filterNot { it.id == id })
    }

    private fun loadItems(): List<ParcelItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ParcelItem.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun saveItems(list: List<ParcelItem>) {
        prefs.edit().putString(KEY_ITEMS, json.encodeToString(ListSerializer(ParcelItem.serializer()), list)).apply()
        _items.value = list
    }

    private fun loadRules(): List<CustomParcelRule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(CustomParcelRule.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun saveRules(list: List<CustomParcelRule>) {
        prefs.edit().putString(KEY_RULES, json.encodeToString(ListSerializer(CustomParcelRule.serializer()), list)).apply()
        _rules.value = list
    }

    companion object {
        private const val PREFS = "hmu_parcel_store"
        private const val KEY_ITEMS = "parcel_items_json"
        private const val KEY_RULES = "parcel_rules_json"
    }
}
