package com.example.simplecontroller.io

import android.content.Context
import android.content.ContextWrapper
import com.example.simplecontroller.model.page
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GameStartersInstrumentedTest {
    private fun withFiles(body: (Context) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, UUID.randomUUID().toString()).also { check(it.mkdir()) }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory
            override fun getFileStreamPath(name: String): File = File(directory, name)
        }
        try { body(context) } finally { directory.deleteRecursively() }
    }

    @Test fun bundledLayoutsLoadAsPlayableEditableProfilesAndSurviveRestart() = withFiles { context ->
        assertEquals(4, GameStarter.entries.size)
        for (starter in GameStarter.entries) {
            val title = context.getString(starter.title)
            val template = GameStarters.read(context, starter)
            assertTrue(template.warnings.toString(), template.warnings.isEmpty())
            assertTrue(template.profile.safeControlArea)
            val page = template.profile.page(template.profile.homePageId)!!
            assertTrue(page.controls.isNotEmpty())
            assertNotNull(page.portraitGeometry)
            assertNotNull(page.landscapeGeometry)
            val (name, copy) = GameStarters.saveCopy(context, title, template, "default")
            assertEquals(title, name)
            assertEquals(template, copy)
            val readback = readControllerProfile(context, name) as StoredControllerProfileResult.Loaded
            assertEquals(template.profile, readback.result.profile)
        }
    }

    @Test fun choosingAgainKeepsChangesToPersonalCopyAndRestoresOriginalControls() = withFiles { context ->
        val starter = GameStarter.SHOOTER
        val original = GameStarters.read(context, starter)
        val (first, copy) = GameStarters.saveCopy(context, "Shooter", original, "default")
        val edited = copy.profile.copy(pages = copy.profile.pages.map { it.copy(name = it.name + " edited") })
        saveControllerProfile(context, first, edited).getOrThrow()
        val preserved = context.filesDir.listFiles()!!.associate { it.name to it.readBytes().toList() }
        val (second, fresh) = GameStarters.saveCopy(context, "Shooter", GameStarters.read(context, starter), first)
        assertEquals("Shooter 2", second)
        assertEquals(original.profile, fresh.profile)
        assertEquals(edited, (readControllerProfile(context, first) as StoredControllerProfileResult.Loaded).result.profile)
        for ((name, bytes) in preserved) assertEquals(bytes, context.getFileStreamPath(name).readBytes().toList())
        assertEquals("Shooter 4", GameStarters.copyName("Shooter", listLayouts(context) + "SHOOTER 3"))
    }
}
