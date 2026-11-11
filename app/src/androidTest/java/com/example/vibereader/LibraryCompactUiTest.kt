package com.example.vibereader

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class LibraryCompactUiTest {
    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun tabsAndToggles() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(15_000)
            saveScreenshot("library_grid.png")

            tap(360f, 1_490f) // Quotes
            Thread.sleep(2_000)
            saveScreenshot("quotes.png")

            tap(600f, 1_490f) // Settings
            Thread.sleep(2_000)
            saveScreenshot("settings.png")

            tap(120f, 1_490f) // Library
            Thread.sleep(2_000)

            tap(648f, 100f) // LIST pill
            Thread.sleep(1_500)
            saveScreenshot("library_list.png")

            tap(554f, 100f) // GRID pill
            Thread.sleep(1_500)
            saveScreenshot("library_grid_after_toggle.png")

            tap(467f, 100f) // Search
            Thread.sleep(1_500)
            saveScreenshot("library_search.png")
        }
    }

    private fun tap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 40, MotionEvent.ACTION_UP, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.uiAutomation.injectInputEvent(down, true)
        instrumentation.uiAutomation.injectInputEvent(up, true)
        down.recycle()
        up.recycle()
    }

    private fun saveScreenshot(name: String) {
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = instrumentation.targetContext.getExternalFilesDir(null) ?: return
        FileOutputStream(File(directory, name)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
