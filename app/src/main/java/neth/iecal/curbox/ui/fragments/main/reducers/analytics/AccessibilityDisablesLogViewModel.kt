package neth.iecal.curbox.ui.fragments.main.reducers.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.db.AccessibilityDisableLogDao
import neth.iecal.curbox.data.db.AccessibilityDisableLogEntity

class AccessibilityDisablesLogViewModel(private val dao: AccessibilityDisableLogDao) : ViewModel() {

    val logs: StateFlow<List<AccessibilityDisableLogEntity>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Number of deliberate disables in the last 7 days (rolling). */
    val weeklyCount: StateFlow<Int> =
        dao.countSince(System.currentTimeMillis() - WEEK_MS)
            .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    fun deleteLog(id: Int) {
        viewModelScope.launch { dao.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch { dao.clear() }
    }

    companion object {
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}

class AccessibilityDisablesLogViewModelFactory(
    private val dao: AccessibilityDisableLogDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AccessibilityDisablesLogViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AccessibilityDisablesLogViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
