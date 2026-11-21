package com.example.simplecontroller.ui

import android.content.Context
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.simplecontroller.MainActivity
import com.example.simplecontroller.R
import com.example.simplecontroller.net.NetworkClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunityHelpInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun prepare() = instrumentation.runOnMainSync {
        NetworkClient.close()
        GlobalSettings.editMode = false
        context.getSharedPreferences("layout", Context.MODE_PRIVATE).edit().putBoolean("helpTestExistingData", true).commit()
        context.getSharedPreferences("network", Context.MODE_PRIVATE).edit().putString("outputMode", NetworkClient.OutputMode.PRACTICE.name).commit()
        context.getSharedPreferences(CommunityHelp.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() = instrumentation.runOnMainSync { NetworkClient.close() }
    private fun profileFiles() = context.filesDir.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(context.filesDir).path to it.readBytes().toList()
    }
    private fun click(dialog: AlertDialog, id: Int) { assertTrue(dialog.findViewById<Button>(id)!!.performClick()) }

    @Test fun firstTourTraversesAllIllustratedPagesAndPreservesSavedData() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val oldFiles = profileFiles()
            val network = context.getSharedPreferences("network", Context.MODE_PRIVATE).all
            val layout = context.getSharedPreferences("layout", Context.MODE_PRIVATE).all
            var finished = 0
            val dialog = CommunityHelp.showTour(activity, firstRun = true) { finished++ }
            assertFalse(dialog.findViewById<Button>(R.id.helpBack)!!.isEnabled)
            assertEquals("Page 1 of 8", dialog.findViewById<TextView>(R.id.helpPageCount)!!.text.toString())
            repeat(6) { click(dialog, R.id.helpNext) }
            assertEquals("Connect wirelessly to a PC", dialog.findViewById<TextView>(R.id.helpPageTitle)!!.text.toString())
            assertTrue(dialog.findViewById<TextView>(R.id.helpPageBody)!!.text.contains("IPv4"))
            click(dialog, R.id.helpNext)
            assertTrue(dialog.findViewById<TextView>(R.id.helpPageBody)!!.text.contains("Support tab"))
            click(dialog, R.id.helpBack)
            assertEquals("Page 7 of 8", dialog.findViewById<TextView>(R.id.helpPageCount)!!.text.toString())
            click(dialog, R.id.helpNext)
            assertEquals("Start playing", dialog.findViewById<Button>(R.id.helpNext)!!.text.toString())
            click(dialog, R.id.helpNext)
            assertEquals(1, finished)
            assertFalse(dialog.isShowing)
            assertTrue(context.getSharedPreferences(CommunityHelp.PREFERENCES, Context.MODE_PRIVATE).getBoolean(CommunityHelp.COMPLETED, false))
            assertEquals(oldFiles, profileFiles())
            assertEquals(network, context.getSharedPreferences("network", Context.MODE_PRIVATE).all)
            assertEquals(layout, context.getSharedPreferences("layout", Context.MODE_PRIVATE).all)
        } }
    }

    @Test fun interruptedTourResumesAndManualReplayDoesNotRewriteProgress() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val prefs = context.getSharedPreferences(CommunityHelp.PREFERENCES, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(CommunityHelp.STARTED, true).putInt(CommunityHelp.PAGE, 6).commit()
            val resumed = CommunityHelp.showTour(activity, firstRun = true)
            assertEquals("Page 7 of 8", resumed.findViewById<TextView>(R.id.helpPageCount)!!.text.toString())
            click(resumed, R.id.helpSkip)
            val saved = prefs.all
            val replay = CommunityHelp.showTour(activity)
            assertEquals("Page 1 of 8", replay.findViewById<TextView>(R.id.helpPageCount)!!.text.toString())
            click(replay, R.id.helpNext)
            click(replay, R.id.helpSkip)
            assertEquals(saved, prefs.all)
        } }
    }

    @Test fun supportTabsExplainSoftwareAndHardwareWithoutChangingConnection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val files = profileFiles()
            val network = context.getSharedPreferences("network", Context.MODE_PRIVATE).all
            var settingsOpened = 0
            val dialog = CommunityHelp.showSupport(activity) { settingsOpened++ }
            assertFalse(dialog.findViewById<Button>(R.id.helpTabPC)!!.isEnabled)
            click(dialog, R.id.helpTabConsole)
            assertFalse(dialog.findViewById<Button>(R.id.helpTabConsole)!!.isEnabled)
            click(dialog, R.id.helpTabLearn)
            assertFalse(dialog.findViewById<Button>(R.id.helpTabLearn)!!.isEnabled)
            click(dialog, R.id.helpTabPC)
            assertEquals(0, settingsOpened)
            dialog.dismiss()
            assertEquals(files, profileFiles())
            assertEquals(network, context.getSharedPreferences("network", Context.MODE_PRIVATE).all)
            assertEquals(7, CommunityHelp.links.size)
            assertTrue(CommunityHelp.links.values.all { it.startsWith("https://github.com/") })
            assertTrue(CommunityHelp.links.getValue(R.string.help_faq).endsWith("/FAQ.txt"))
        } }
    }
}
