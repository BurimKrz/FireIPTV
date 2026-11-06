package com.fireiptv.feature.browse.options

import android.os.SystemClock
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyPress
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fireiptv.core.data.channel.ChannelList
import com.fireiptv.feature.browse.GroupRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.view.KeyEvent as NativeKeyEvent

/** A long press opens the menu while OK is still held; letting go must not pick the first row. */
@RunWith(AndroidJUnit4::class)
class OptionsDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var pins = 0
    private var dismissed = false
    private val pressedAt = SystemClock.uptimeMillis()

    private fun showMenu() {
        compose.setContent {
            GroupOptionsDialog(
                row = GroupRow(ChannelList.Custom(1), "qqqqq", channelCount = 1),
                onTogglePinned = { pins++ },
                onRename = {},
                onDelete = {},
                onDismiss = { dismissed = true },
            )
        }
        compose.onNodeWithText("Pin to the top").assert(isFocused())
    }

    private fun ok(action: Int, repeat: Int = 0) {
        val event = NativeKeyEvent(pressedAt, SystemClock.uptimeMillis(), action, KEYCODE_DPAD_CENTER, repeat)
        compose.onNodeWithText("Pin to the top").performKeyPress(KeyEvent(event))
        compose.waitForIdle()
    }

    @Test
    fun restOfTheLongPressIsIgnored() {
        showMenu()

        // What reaches the menu from the press that opened it: held-key repeats, then the release.
        ok(ACTION_DOWN, repeat = 5)
        ok(ACTION_DOWN, repeat = 6)
        ok(ACTION_UP)

        assertEquals(0, pins)
        assertFalse(dismissed)
        compose.onNodeWithText("Delete category").assertExists()
    }

    @Test
    fun freshPressInTheMenuWorks() {
        showMenu()
        ok(ACTION_UP) // the release of the long press

        ok(ACTION_DOWN)
        ok(ACTION_UP)

        assertEquals(1, pins)
    }
}
