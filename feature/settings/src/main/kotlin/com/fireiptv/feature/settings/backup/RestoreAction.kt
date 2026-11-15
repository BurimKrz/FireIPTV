package com.fireiptv.feature.settings.backup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns the action for a Restore button. A backup image left by an earlier install belongs to
 * no app now, and Android 10+ shows it to the app only with the photos permission, so the action
 * asks for it first when needed, then runs [restore] whatever the answer (the app's own backup
 * needs none).
 */
@Composable
fun rememberRestoreAction(restore: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val askToRead = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { restore() }
    return {
        val permission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.READ_EXTERNAL_STORAGE
            else -> null
        }
        if (permission != null && ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            askToRead.launch(permission)
        } else {
            restore()
        }
    }
}
