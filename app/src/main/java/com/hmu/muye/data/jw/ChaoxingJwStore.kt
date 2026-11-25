package com.hmu.muye.data.jw

import android.content.Context
import com.fengkao.timetable.data.local.Crypto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 超星综合教学管理系统（hnuahe.jw.chaoxing.com）本地加密凭据与教务缓存。
 */
class ChaoxingJwStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun cookies(): String = prefs.getString(KEY_COOKIES_ENC, null)
        ?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }
        .orEmpty()

    fun hasSession(): Boolean = cookies().isNotBlank()

    fun username(): String = prefs.getString(KEY_USERNAME, null).orEmpty()

    fun password(): String = prefs.getString(KEY_PASSWORD_ENC, null)
        ?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }
        .orEmpty()

    fun saveCredentials(username: String, password: String?) {
        val cleanUser = username.trim()
        prefs.edit().apply {
            if (cleanUser.isNotBlank()) putString(KEY_USERNAME, cleanUser)
            if (!password.isNullOrBlank()) {
                val enc = runCatching { Crypto.encrypt(password) }.getOrDefault("")
                if (enc.isNotBlank()) putString(KEY_PASSWORD_ENC, enc)
            } else {
                remove(KEY_PASSWORD_ENC)
            }
        }.apply()
    }

    fun clearCredentials() {
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD_ENC).apply()
    }

    fun baseUrl(): String = prefs.getString(KEY_BASE_URL, DEFAULT_JW_BASE)
        ?.takeIf { it.isNotBlank() } ?: DEFAULT_JW_BASE

    fun saveSession(rawCookies: String, baseUrl: String = DEFAULT_JW_BASE) {
        val enc = runCatching { Crypto.encrypt(rawCookies) }.getOrDefault("")
        prefs.edit()
            .putString(KEY_COOKIES_ENC, enc)
            .putString(KEY_BASE_URL, baseUrl.trimEnd('/'))
            .putLong(KEY_LOGIN_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearSession() {
        prefs.edit().remove(KEY_COOKIES_ENC).remove(KEY_LOGIN_AT).apply()
    }

    fun lastLoginAt(): Long = prefs.getLong(KEY_LOGIN_AT, 0L)

    fun grades(): List<GradeItem> = decodeList(KEY_GRADES, ListSerializer(GradeItem.serializer()))

    fun saveGrades(items: List<GradeItem>) {
        encodeList(KEY_GRADES, ListSerializer(GradeItem.serializer()), items)
        prefs.edit().putLong(KEY_GRADES_AT, System.currentTimeMillis()).apply()
    }

    fun gradesUpdatedAt(): Long = prefs.getLong(KEY_GRADES_AT, 0L)

    fun exams(): List<ExamItem> = decodeList(KEY_EXAMS, ListSerializer(ExamItem.serializer()))

    fun saveExams(items: List<ExamItem>) {
        encodeList(KEY_EXAMS, ListSerializer(ExamItem.serializer()), items)
        prefs.edit().putLong(KEY_EXAMS_AT, System.currentTimeMillis()).apply()
    }

    fun examsUpdatedAt(): Long = prefs.getLong(KEY_EXAMS_AT, 0L)

    fun examNotes(): List<ExamNote> = decodeList(KEY_EXAM_NOTES, ListSerializer(ExamNote.serializer()))

    fun saveExamNotes(notes: List<ExamNote>) {
        encodeList(KEY_EXAM_NOTES, ListSerializer(ExamNote.serializer()), notes)
    }

    private fun <T> decodeList(key: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun <T> encodeList(
        key: String,
        serializer: kotlinx.serialization.KSerializer<List<T>>,
        items: List<T>,
    ) {
        prefs.edit().putString(key, json.encodeToString(serializer, items)).apply()
    }

    companion object {
        const val DEFAULT_JW_BASE = "https://hnuahe.jw.chaoxing.com"
        private const val PREFS = "hmu_chaoxing_jw"
        private const val KEY_COOKIES_ENC = "cookies_enc"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD_ENC = "password_enc"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_LOGIN_AT = "login_at"
        private const val KEY_GRADES = "grades_json"
        private const val KEY_GRADES_AT = "grades_at"
        private const val KEY_EXAMS = "exams_json"
        private const val KEY_EXAMS_AT = "exams_at"
        private const val KEY_EXAM_NOTES = "exam_notes_json"
    }
}
