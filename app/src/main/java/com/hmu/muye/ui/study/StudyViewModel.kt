package com.hmu.muye.ui.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fengkao.timetable.TimetableApp
import com.hmu.muye.data.jw.ChaoxingJwClient
import com.hmu.muye.data.jw.EmptyRoomSlot
import com.hmu.muye.data.jw.ExamItem
import com.hmu.muye.data.jw.ExamNote
import com.hmu.muye.data.jw.GpaSummary
import com.hmu.muye.data.jw.GradeItem
import com.hmu.muye.data.jw.JwFetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

enum class StudySubTab(val label: String) {
    XUEXITONG("学习通"),
    GRADES("成绩"),
    EXAMS("考试"),
    EMPTY_ROOMS("空教室"),
    LIBRARY("图书馆"),
}

data class StudyUiState(
    val subTab: StudySubTab = StudySubTab.XUEXITONG,
    val jwConnected: Boolean = false,
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val statusIsError: Boolean = false,
    val grades: List<GradeItem> = emptyList(),
    val gradesUpdatedAt: Long = 0L,
    val selectedGradeTerm: String = "全部",
    val gpaSummary: GpaSummary = GpaSummary(0.0, 0.0, 0.0, 0, 0),
    val exams: List<ExamItem> = emptyList(),
    val examsUpdatedAt: Long = 0L,
    val examNotes: List<ExamNote> = emptyList(),
    val buildingQuery: String = "1号教学楼",
    val periodQuery: String = "3-4节",
    val selectedDayOfWeek: Int = LocalDate.now().dayOfWeek.value,
    val emptyRooms: List<EmptyRoomSlot> = emptyList(),
    val libraryUrl: String = "",
)

class StudyViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as TimetableApp).container
    private val jwStore = container.jwStore
    private val campusLife = container.campusLife

    private val _state = MutableStateFlow(loadInitial())
    val state = _state.asStateFlow()

    private fun loadInitial(): StudyUiState {
        val grades = jwStore.grades()
        return StudyUiState(
            jwConnected = jwStore.hasSession(),
            grades = grades,
            gradesUpdatedAt = jwStore.gradesUpdatedAt(),
            gpaSummary = ChaoxingJwClient.computeGpaSummary(grades),
            exams = jwStore.exams(),
            examsUpdatedAt = jwStore.examsUpdatedAt(),
            examNotes = jwStore.examNotes(),
            libraryUrl = campusLife.libraryUrl(),
        )
    }

    fun refreshSessionState() {
        _state.update {
            it.copy(
                jwConnected = jwStore.hasSession(),
                libraryUrl = campusLife.libraryUrl(),
            )
        }
    }

    fun selectSubTab(tab: StudySubTab) {
        _state.update { it.copy(subTab = tab, statusMessage = null, statusIsError = false) }
    }

    fun selectGradeTerm(term: String) {
        _state.update {
            val scoped = if (term == "全部") it.grades else it.grades.filter { g -> g.termLabel == term }
            it.copy(
                selectedGradeTerm = term,
                gpaSummary = ChaoxingJwClient.computeGpaSummary(scoped),
            )
        }
    }

    fun onWebLoginCaptured(cookies: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                jwStore.saveSession(cookies, container.jwStore.baseUrl())
            }
            _state.update {
                it.copy(
                    jwConnected = true,
                    statusMessage = "超星教务登录已保存（加密存储在本地）",
                    statusIsError = false,
                )
            }
        }
    }

    fun refreshGrades() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, statusMessage = null, statusIsError = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ChaoxingJwClient(jwStore.baseUrl()).fetchGrades(jwStore.cookies())
            }
            when (result) {
                is JwFetchResult.Success -> {
                    val tagged = ChaoxingJwClient.diffNewGrades(_state.value.grades, result.data)
                    withContext(Dispatchers.IO) { jwStore.saveGrades(tagged) }
                    val newCount = tagged.count { it.isNew }
                    val term = _state.value.selectedGradeTerm
                    val scoped = if (term == "全部") tagged else tagged.filter { it.termLabel == term }
                    _state.update {
                        it.copy(
                            busy = false,
                            grades = tagged,
                            gradesUpdatedAt = jwStore.gradesUpdatedAt(),
                            gpaSummary = ChaoxingJwClient.computeGpaSummary(scoped),
                            statusMessage = if (newCount > 0) "发现 $newCount 门新出/更新成绩" else "成绩已更新",
                            statusIsError = false,
                        )
                    }
                }
                is JwFetchResult.NeedWebLogin -> {
                    _state.update {
                        it.copy(busy = false, jwConnected = false, statusMessage = result.reason, statusIsError = true)
                    }
                }
                is JwFetchResult.PendingPhase2Route -> {
                    _state.update { it.copy(busy = false, statusMessage = result.message, statusIsError = false) }
                }
                is JwFetchResult.Error -> {
                    _state.update { it.copy(busy = false, statusMessage = result.message, statusIsError = true) }
                }
            }
        }
    }

    fun refreshExams() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, statusMessage = null, statusIsError = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ChaoxingJwClient(jwStore.baseUrl()).fetchExams(jwStore.cookies())
            }
            when (result) {
                is JwFetchResult.Success -> {
                    withContext(Dispatchers.IO) { jwStore.saveExams(result.data) }
                    _state.update {
                        it.copy(
                            busy = false,
                            exams = result.data,
                            examsUpdatedAt = jwStore.examsUpdatedAt(),
                            statusMessage = if (result.data.isEmpty()) "当前学期教务暂未公布考试安排" else "已更新 ${result.data.size} 场考试安排",
                            statusIsError = false,
                        )
                    }
                }
                is JwFetchResult.NeedWebLogin -> {
                    _state.update {
                        it.copy(busy = false, jwConnected = false, statusMessage = result.reason, statusIsError = true)
                    }
                }
                is JwFetchResult.PendingPhase2Route -> {
                    _state.update { it.copy(busy = false, statusMessage = result.message, statusIsError = false) }
                }
                is JwFetchResult.Error -> {
                    _state.update { it.copy(busy = false, statusMessage = result.message, statusIsError = true) }
                }
            }
        }
    }

    fun addExamNote(content: String) {
        val clean = content.trim()
        if (clean.isBlank()) return
        val note = ExamNote(id = UUID.randomUUID().toString(), content = clean, createdAt = System.currentTimeMillis())
        val next = listOf(note) + _state.value.examNotes
        jwStore.saveExamNotes(next)
        _state.update { it.copy(examNotes = next) }
    }

    fun removeExamNote(id: String) {
        val next = _state.value.examNotes.filterNot { it.id == id }
        jwStore.saveExamNotes(next)
        _state.update { it.copy(examNotes = next) }
    }

    fun updateBuildingQuery(value: String) {
        _state.update { it.copy(buildingQuery = value) }
    }

    fun updatePeriodQuery(value: String) {
        _state.update { it.copy(periodQuery = value) }
    }

    fun updateDayOfWeek(day: Int) {
        _state.update { it.copy(selectedDayOfWeek = day.coerceIn(1, 7)) }
    }

    fun queryEmptyRooms() {
        if (_state.value.busy) return
        val s = _state.value
        _state.update { it.copy(busy = true, statusMessage = null, statusIsError = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ChaoxingJwClient(jwStore.baseUrl()).fetchEmptyRooms(
                    cookies = jwStore.cookies(),
                    building = s.buildingQuery,
                    periodRange = s.periodQuery,
                    dayOfWeek = s.selectedDayOfWeek,
                )
            }
            when (result) {
                is JwFetchResult.Success -> {
                    _state.update {
                        it.copy(busy = false, emptyRooms = result.data, statusMessage = "查到 ${result.data.size} 间空教室")
                    }
                }
                is JwFetchResult.NeedWebLogin -> {
                    _state.update {
                        it.copy(busy = false, jwConnected = false, statusMessage = result.reason, statusIsError = true)
                    }
                }
                is JwFetchResult.PendingPhase2Route -> {
                    _state.update { it.copy(busy = false, statusMessage = result.message, statusIsError = false) }
                }
                is JwFetchResult.Error -> {
                    _state.update { it.copy(busy = false, statusMessage = result.message, statusIsError = true) }
                }
            }
        }
    }

    fun saveLibraryUrl(url: String) {
        campusLife.setLibraryUrl(url)
        _state.update { it.copy(libraryUrl = url.trim(), statusMessage = "已保存图书馆网页链接", statusIsError = false) }
    }
}
