package neth.iecal.curbox.ui.fragments.main.reducers.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.db.BlockedAppLogDao
import neth.iecal.curbox.data.db.BlockedAppLogEntity

class BlockedAppsLogViewModel(private val dao: BlockedAppLogDao) : ViewModel() {

    val logs: StateFlow<List<BlockedAppLogEntity>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun deleteLog(id: Int) {
        viewModelScope.launch { dao.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch { dao.clear() }
    }
}

class BlockedAppsLogViewModelFactory(private val dao: BlockedAppLogDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BlockedAppsLogViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BlockedAppsLogViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
