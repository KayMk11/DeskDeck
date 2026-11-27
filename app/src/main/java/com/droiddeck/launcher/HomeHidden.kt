package com.droiddeck.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object HomeHidden {
    private const val PREFS = "home_launcher"
    private const val KEY = "hidden"
    private const val SEEN = "linux_seen"

    var keys by mutableStateOf<Set<String>>(emptySet())
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        keys = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toSet()
    }

    // Each Linux app gets its default once, when first listed, so showing it again sticks.
    fun hideNewDefaults(context: Context, apps: List<HomeApp.LaunchableApp>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(SEEN, emptySet()).orEmpty()
        val fresh = apps.filter { it.linux != null && it.key !in seen }
        if (fresh.isEmpty()) return
        load(context)
        keys = keys + fresh.filter { com.droiddeck.launcher.runtime.LinuxApps.hiddenByDefault(it.linux!!) }.map { it.key }
        prefs.edit().putStringSet(KEY, keys).putStringSet(SEEN, seen + fresh.map { it.key }).apply()
    }

    fun set(context: Context, key: String, hidden: Boolean) {
        keys = if (hidden) keys + key else keys - key
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, keys).apply()
    }
}
