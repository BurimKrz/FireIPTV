package com.fireiptv.feature.settings.account

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.fireiptv.core.data.account.AccountInfo
import com.fireiptv.core.data.account.AccountResult
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** The provider account: what the provider's "Account Information" channel showed, without the video. */
@Composable
fun AccountScreen(viewModel: AccountViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val refreshFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { refreshFocus.requestFocus() }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 48.dp),
    ) {
        Text("Account", style = MaterialTheme.typography.headlineMedium)
        when (val result = state.result) {
            null -> Text("Asking your provider…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            is AccountResult.Loaded -> Details(result.info)
            is AccountResult.Failed -> Text(result.message, color = MaterialTheme.colorScheme.error)
            AccountResult.NotAvailable -> Text(
                "Your playlist is a plain M3U link, which carries no account details. They are shown for Xtream logins (get.php links).",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = viewModel::reload,
            enabled = state.result != null,
            modifier = Modifier.focusRequester(refreshFocus),
        ) {
            Text("Refresh")
        }
    }
}

@Composable
private fun Details(info: AccountInfo) {
    val context = LocalContext.current
    val dateFormat = remember(context) { DateFormat.getLongDateFormat(context) }
    fun date(epochSeconds: Long) = dateFormat.format(Date(TimeUnit.SECONDS.toMillis(epochSeconds)))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        info.username?.let { Field("Username", it) }
        info.status?.let { status ->
            val active = status.equals("Active", ignoreCase = true)
            Field("Status", status, valueColor = if (active) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
        }
        Field(
            "Expires",
            info.expiresAt?.let { expires ->
                val days = ceil((expires * 1000.0 - System.currentTimeMillis()) / TimeUnit.DAYS.toMillis(1)).toLong()
                when {
                    days < 0 -> "${date(expires)} (expired)"
                    days == 1L -> "${date(expires)} (1 day left)"
                    else -> "${date(expires)} ($days days left)"
                }
            } ?: "Never",
        )
        if (info.maxConnections != null) {
            val active = info.activeConnections?.let { "$it in use of " }.orEmpty()
            Field("Connections", "$active${info.maxConnections}")
        }
        if (info.isTrial) Field("Trial", "Yes")
        info.createdAt?.let { Field("Created", date(it)) }
    }
}

@Composable
private fun Field(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(220.dp))
        Text(value, color = valueColor)
    }
}
