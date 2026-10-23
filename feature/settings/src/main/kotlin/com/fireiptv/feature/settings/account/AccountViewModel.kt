package com.fireiptv.feature.settings.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireiptv.core.data.account.AccountRepository
import com.fireiptv.core.data.account.AccountResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Null while loading. */
data class AccountUiState(val result: AccountResult? = null)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val repository: AccountRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    /** Asks the provider again; the screen opening is the only other time it is asked. */
    fun reload() {
        _uiState.value = AccountUiState(result = null)
        viewModelScope.launch { _uiState.value = AccountUiState(repository.load()) }
    }
}
