package org.fossify.gallery.helpers

import android.content.Context
import android.content.SharedPreferences
import java.io.File

internal object SourceLinkPreferences {
    @Synchronized
    fun get(context: Context, path: String): String {
        if (path.isBlank()) {
            return ""
        }

        val preferences = sourceLinkPreferences(context)
        val directUrl = preferences.getString(path, null).orEmpty()
        return if (directUrl.isNotBlank()) {
            ensureAlias(preferences, path)
            directUrl
        } else {
            recoverMovedLink(preferences, path)
        }
    }

    @Synchronized
    fun put(context: Context, path: String, url: String) {
        if (path.isBlank()) {
            return
        }

        val identity = SourceLinkIdentity.createCandidate(path)?.let { candidate ->
            SourceLinkIdentity.resolve(path, candidate)
        }
        writeEntry(
            preferences = sourceLinkPreferences(context),
            oldPath = null,
            newPath = path,
            url = url,
            identity = identity,
        )
    }

    @Synchronized
    fun move(context: Context, oldPath: String, newPath: String) {
        if (oldPath == newPath || newPath.isBlank()) {
            return
        }

        val preferences = sourceLinkPreferences(context)
        val url = preferences.getString(oldPath, null).orEmpty()
        if (url.isBlank()) {
            return
        }

        val identity = SourceLinkIdentity.createCandidate(newPath)?.let { candidate ->
            SourceLinkIdentity.resolve(newPath, candidate)
        }
        writeEntry(
            preferences = preferences,
            oldPath = oldPath,
            newPath = newPath,
            url = url,
            identity = identity,
        )
    }

    @Synchronized
    fun remove(context: Context, path: String) {
        if (path.isBlank()) {
            return
        }

        val preferences = sourceLinkPreferences(context)
        val editor = preferences.edit()
        removePathMetadata(preferences, editor, path)
        editor.remove(path).apply()
    }

    fun register(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        sourceLinkPreferences(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        sourceLinkPreferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun recoverMovedLink(preferences: SharedPreferences, path: String): String {
        val identity = SourceLinkIdentity.createCandidate(path)
            ?.takeIf { candidate -> preferences.getBoolean(candidate.sizeMarkerKey, false) }
            ?.let { candidate -> SourceLinkIdentity.resolve(path, candidate) }
            ?: return ""
        val oldPath = SourceLinkAlias.decodePath(
            preferences.getString(identity.aliasKey, null).orEmpty(),
        ) ?: return ""
        val url = preferences.getString(oldPath, null).orEmpty()

        return when {
            url.isBlank() -> {
                preferences.edit().remove(identity.aliasKey).apply()
                ""
            }

            File(oldPath).isFile -> ""
            else -> {
                writeEntry(preferences, oldPath, path, url, identity)
                url
            }
        }
    }

    private fun ensureAlias(preferences: SharedPreferences, path: String) {
        val candidate = SourceLinkIdentity.createCandidate(path) ?: return
        val identity = SourceLinkIdentity.resolve(path, candidate) ?: return
        val metadataKey = SourceLinkAlias.pathMetadataKey(path)
        val storedAliasKey = preferences.getString(metadataKey, null)
        if (
            storedAliasKey == identity.aliasKey &&
            SourceLinkAlias.decodePath(
                preferences.getString(storedAliasKey, null).orEmpty(),
            ) == path
        ) {
            return
        }

        val currentOwner = SourceLinkAlias.decodePath(
            preferences.getString(identity.aliasKey, null).orEmpty(),
        )
        val editor = preferences.edit()
            .putBoolean(identity.sizeMarkerKey, true)

        removePathMetadata(preferences, editor, path)
        if (SourceLinkAlias.canOwn(currentOwner, null, path)) {
            editor
                .putString(metadataKey, identity.aliasKey)
                .putString(identity.aliasKey, SourceLinkAlias.encodePath(path))
        }

        editor.apply()
    }

    private fun writeEntry(
        preferences: SharedPreferences,
        oldPath: String?,
        newPath: String,
        url: String,
        identity: SourceLinkFileIdentity?,
    ) {
        val editor = preferences.edit()

        if (oldPath != null && oldPath != newPath) {
            removePathMetadata(preferences, editor, oldPath)
            editor.remove(oldPath)
        }

        removePathMetadata(preferences, editor, newPath)
        editor.putString(newPath, url)

        if (identity != null) {
            val currentOwner = SourceLinkAlias.decodePath(
                preferences.getString(identity.aliasKey, null).orEmpty(),
            )

            editor.putBoolean(identity.sizeMarkerKey, true)
            if (SourceLinkAlias.canOwn(currentOwner, oldPath, newPath)) {
                editor
                    .putString(SourceLinkAlias.pathMetadataKey(newPath), identity.aliasKey)
                    .putString(identity.aliasKey, SourceLinkAlias.encodePath(newPath))
            }
        }

        editor.apply()
    }

    private fun removePathMetadata(
        preferences: SharedPreferences,
        editor: SharedPreferences.Editor,
        path: String,
    ) {
        val metadataKey = SourceLinkAlias.pathMetadataKey(path)
        val aliasKey = preferences.getString(metadataKey, null)
        val aliasOwner = aliasKey?.let { key ->
            SourceLinkAlias.decodePath(preferences.getString(key, null).orEmpty())
        }
        if (aliasKey != null && aliasOwner == path) {
            editor.remove(aliasKey)
        }
        editor.remove(metadataKey)
    }
}

private const val SOURCE_LINK_INTERNAL_PREFIX = "__source_link_"
private const val SOURCE_LINK_PREFS_NAME = "source_links"

internal fun sourceLinkPreferenceAffectsVisibleLink(key: String?): Boolean {
    return key == null || !key.startsWith(SOURCE_LINK_INTERNAL_PREFIX)
}

private fun sourceLinkPreferences(context: Context): SharedPreferences {
    return context.getSharedPreferences(SOURCE_LINK_PREFS_NAME, Context.MODE_PRIVATE)
}
