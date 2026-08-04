package org.fossify.gallery.helpers

import android.content.Context
import android.content.SharedPreferences

internal object SourceLinkPreferences {
    private const val PREFS_NAME = "source_links"

    fun get(context: Context, path: String): String {
        return preferences(context).getString(path, null).orEmpty()
    }

    fun put(context: Context, path: String, url: String) {
        preferences(context)
            .edit()
            .putString(path, url)
            .apply()
    }

    fun move(context: Context, oldPath: String, newPath: String) {
        if (oldPath == newPath) {
            return
        }

        val url = get(context, oldPath)
        if (url.isBlank()) {
            return
        }

        preferences(context)
            .edit()
            .remove(oldPath)
            .putString(newPath, url)
            .apply()
    }

    fun remove(context: Context, path: String) {
        preferences(context)
            .edit()
            .remove(path)
            .apply()
    }

    fun register(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        preferences(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun preferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
