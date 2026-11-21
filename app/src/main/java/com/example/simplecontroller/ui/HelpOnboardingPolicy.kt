package com.example.simplecontroller.ui

/** Separate help state; never reads or rewrites controller profiles or network settings. */
internal object HelpOnboardingPolicy {
    const val PAGE_COUNT = 8
    fun shouldOffer(freshInstall: Boolean, started: Boolean, completed: Boolean): Boolean =
        !completed && (freshInstall || started)
    fun page(index: Int): Int = index.coerceIn(0, PAGE_COUNT - 1)
    fun next(index: Int): Int = page(index + 1)
    fun previous(index: Int): Int = page(index - 1)
}
