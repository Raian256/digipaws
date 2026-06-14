package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autofocus

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.data.models.AutoFocusGroup
import neth.iecal.curbox.utils.DataStoreManager

class AutoFocusViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)
    private val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(application)

    private val _groups = MutableStateFlow<List<AutoFocusGroup>>(emptyList())
    val groups: StateFlow<List<AutoFocusGroup>> = _groups

    /** Ids of the auto-focus groups that have a focus session running right now. */
    val runningGroupIds: StateFlow<Set<String>> =
        db.focusStatsDao().getAllSessionsFlow()
            .map { sessions ->
                sessions.filter { it.wasAutoFocus && it.status == 0 }
                    .map { it.groupId }
                    .toSet()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    var currentDailyIntervals: MutableMap<Int, MutableList<neth.iecal.curbox.data.models.TimeInterval>> = mutableMapOf()

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _groups.value = settings.autoFocusGroups
            }
        }
    }

    fun addGroup(group: AutoFocusGroup) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentGroups = currentSettings.autoFocusGroups.toMutableList()
            currentGroups.add(group)
            updateGroups(currentGroups)
        }
    }

    fun removeGroup(group: AutoFocusGroup) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentGroups = currentSettings.autoFocusGroups.toMutableList()
            // We use removeIf to ensure it matches by id in case of object reference mismatch
            currentGroups.removeIf { it.groupId == group.groupId }
            updateGroups(currentGroups)
        }
    }

    fun updateGroup(group: AutoFocusGroup) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentGroups = currentSettings.autoFocusGroups.toMutableList()
            val index = currentGroups.indexOfFirst { it.groupId == group.groupId }
            if (index != -1) {
                currentGroups[index] = group
                updateGroups(currentGroups)
            }
        }
    }

    private fun updateGroups(newGroups: List<AutoFocusGroup>) {
        viewModelScope.launch {
            dataStoreManager.updateAutoFocusGroups(newGroups)
            requestFocusBlockerRefresh()
        }
    }

    private fun requestFocusBlockerRefresh() {
        val intent = Intent(FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE)
        application.sendBroadcast(intent)
    }
}
