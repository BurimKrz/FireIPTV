package com.fireiptv.feature.settings.log

import androidx.lifecycle.ViewModel
import com.fireiptv.core.log.AppLog
import com.fireiptv.core.log.LogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(private val log: AppLog) : ViewModel() {
    val entries: StateFlow<List<LogEntry>> = log.entries

    fun clear() = log.clear()
}
