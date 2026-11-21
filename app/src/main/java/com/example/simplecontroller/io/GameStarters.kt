package com.example.simplecontroller.io

import android.content.Context
import com.example.simplecontroller.R
import java.util.Locale

internal enum class GameStarter(val asset: String, val title: Int, val description: Int) {
    SHOOTER("shooter", R.string.starter_shooter, R.string.starter_shooter_description),
    RACING("racing", R.string.starter_racing, R.string.starter_racing_description),
    SPORTS("sports", R.string.starter_sports, R.string.starter_sports_description),
    OPEN_WORLD("open_world", R.string.starter_open_world, R.string.starter_open_world_description)
}

internal object GameStarters {
    fun read(context: Context, starter: GameStarter): ControllerProfileLoadResult {
        val text = context.assets.open("starters/${starter.asset}.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return decodeControllerProfileTransfer(text, context.getString(starter.title)).result
    }

    /** Profiles are stored under case-insensitive filenames. Reserve the current unsaved name too. */
    fun copyName(title: String, existing: Collection<String>): String {
        val used = existing.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        var candidate = title
        var number = 2
        while (candidate.lowercase(Locale.ROOT) in used) {
            candidate = "$title ${number++}"
        }
        return candidate
    }

    @Synchronized
    fun saveCopy(context: Context, title: String, template: ControllerProfileLoadResult,
                 currentName: String): Pair<String, ControllerProfileLoadResult> {
        val name = copyName(title, listLayouts(context) + currentName)
        saveControllerProfile(context, name, template.profile).getOrThrow()
        val stored = readControllerProfile(context, name) as? StoredControllerProfileResult.Loaded
        if (stored == null || stored.result.profile != template.profile) {
            deleteControllerProfile(context, name)
            error("The starter layout could not be verified.")
        }
        return name to stored.result
    }
}
