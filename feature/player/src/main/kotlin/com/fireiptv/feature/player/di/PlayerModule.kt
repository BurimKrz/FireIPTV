package com.fireiptv.feature.player.di

import com.fireiptv.core.data.settings.SettingsRepository
import com.fireiptv.core.data.settings.StartMode
import com.fireiptv.core.player.BufferSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
object PlayerModule {

    /** Read each time the player opens, so a changed setting applies to the next channel. */
    @Provides
    fun bufferSettings(settings: SettingsRepository): BufferSettings = when (settings.startMode.value) {
        StartMode.FAST -> BufferSettings.FAST_START
        StartMode.STABLE -> BufferSettings.STABLE
    }
}
