package org.fossify.gallery.helpers

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal object SourceLinkPreferences {
    private const val PREFS_NAME = "source_links"
    private const val INTERNAL_PREFIX = "__source_link_"
    private const val ALIAS_PREFIX = "${INTERNAL_PREFIX}alias:"
    private const val PATH_ALIAS_PREFIX = "${INTERNAL_PREFIX}path_alias:"
    private const val SIZE_MARKER_PREFIX = "${INTERNAL_PREFIX}size:"
    private const val SAMPLE_BYTES = 16 * 1024
    private const val JSON_PATH = "path"

    @Synchronized
    fun get(context: Context, path: String): String {
        if (path.isBlank()) {
            return ""
        }

        val preferences = preferences(context)
        val directUrl = preferences.getString(path, null).orEmpty()
        if (directUrl.isNotBlank()) {
            ensureAlias(preferences, path)
            return directUrl
        }

        val candidate = createCandidate(path) ?: return ""
        if (!preferences.getBoolean(candidate.sizeMarkerKey, false)) {
            return ""
        }

        val identity = resolveIdentity(path, candidate) ?: return ""
        val oldPath = decodeAliasPath(
            preferences.getString(identity.aliasKey, null).orEmpty(),
        ) ?: return ""

        val url = preferences.getString(oldPath, null).orEmpty()
        if (url.isBlank()) {
            preferences.edit().remove(identity.aliasKey).apply()
            return ""
        }

        // Do not steal a link from an existing identical duplicate.
        if (File(oldPath).isFile) {
            return ""
        }

        writeEntry(preferences, oldPath, path, url, identity)
        return url
    }

    @Synchronized
    fun put(context: Context, path: String, url: String) {
        if (path.isBlank()) {
            return
        }

        writeEntry(
            preferences = preferences(context),
            oldPath = null,
            newPath = path,
            url = url,
            identity = createCandidate(path)?.let { resolveIdentity(path, it) },
        )
    }

    @Synchronized
    fun move(context: Context, oldPath: String, newPath: String) {
        if (oldPath == newPath || newPath.isBlank()) {
            return
        }

        val preferences = preferences(context)
        val url = preferences.getString(oldPath, null).orEmpty()
        if (url.isBlank()) {
            return
        }

        writeEntry(
            preferences = preferences,
            oldPath = oldPath,
            newPath = newPath,
            url = url,
            identity = createCandidate(newPath)?.let { resolveIdentity(newPath, it) },
        )
    }

    @Synchronized
    fun remove(context: Context, path: String) {
        if (path.isBlank()) {
            return
        }

        val preferences = preferences(context)
        val editor = preferences.edit()
        removePathMetadata(preferences, editor, path)
        editor.remove(path).apply()
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

    private fun ensureAlias(preferences: SharedPreferences, path: String) {
        val storedAliasKey = preferences.getString(pathAliasKey(path), null)
        if (
            storedAliasKey != null &&
            decodeAliasPath(preferences.getString(storedAliasKey, null).orEmpty()) == path
        ) {
            return
        }

        val identity = createCandidate(path)?.let { resolveIdentity(path, it) } ?: return
        val currentOwner = decodeAliasPath(
            preferences.getString(identity.aliasKey, null).orEmpty(),
        )

        val editor = preferences.edit()
            .putBoolean(identity.sizeMarkerKey, true)

        if (currentOwner == null || currentOwner == path || !File(currentOwner).isFile) {
            editor
                .putString(pathAliasKey(path), identity.aliasKey)
                .putString(identity.aliasKey, encodeAlias(path))
        }

        editor.apply()
    }

    private fun writeEntry(
        preferences: SharedPreferences,
        oldPath: String?,
        newPath: String,
        url: String,
        identity: FileIdentity?,
    ) {
        val editor = preferences.edit()

        if (oldPath != null && oldPath != newPath) {
            removePathMetadata(preferences, editor, oldPath)
            editor.remove(oldPath)
        }

        removePathMetadata(preferences, editor, newPath)
        editor.putString(newPath, url)

        if (identity != null) {
            val currentOwner = decodeAliasPath(
                preferences.getString(identity.aliasKey, null).orEmpty(),
            )

            editor.putBoolean(identity.sizeMarkerKey, true)
            if (
                currentOwner == null ||
                currentOwner == oldPath ||
                currentOwner == newPath ||
                !File(currentOwner).isFile
            ) {
                editor
                    .putString(pathAliasKey(newPath), identity.aliasKey)
                    .putString(identity.aliasKey, encodeAlias(newPath))
            }
        }

        editor.apply()
    }

    private fun removePathMetadata(
        preferences: SharedPreferences,
        editor: SharedPreferences.Editor,
        path: String,
    ) {
        val aliasMetadataKey = pathAliasKey(path)
        val aliasKey = preferences.getString(aliasMetadataKey, null)
        if (
            aliasKey != null &&
            decodeAliasPath(preferences.getString(aliasKey, null).orEmpty()) == path
        ) {
            editor.remove(aliasKey)
        }
        editor.remove(aliasMetadataKey)
    }

    private fun createCandidate(path: String): FileCandidate? {
        if (path.startsWith("content://", ignoreCase = true)) {
            return null
        }

        val file = File(path)
        if (!file.isFile) {
            return null
        }

        val scope = getTrackingScope(file) ?: return null
        return FileCandidate(
            scope = scope,
            size = file.length(),
        )
    }

    private fun getTrackingScope(file: File): String? {
        val directories = file.absolutePath
            .split(File.separatorChar)
            .filter { it.isNotBlank() }
            .dropLast(1)

        if (directories.isEmpty()) {
            return null
        }

        val segmentCount = when {
            directories.size >= 4 &&
                directories[0] == "storage" &&
                directories[1] == "emulated" -> 4

            directories.size >= 3 && directories[0] == "storage" -> 3
            else -> directories.size
        }

        return File.separator + directories.take(segmentCount).joinToString(File.separator)
    }

    private fun resolveIdentity(path: String, candidate: FileCandidate): FileIdentity? {
        val fingerprint = createFingerprint(path, candidate.size) ?: return null
        return FileIdentity(
            aliasKey = "$ALIAS_PREFIX${candidate.scope}:${candidate.size}:$fingerprint",
            sizeMarkerKey = candidate.sizeMarkerKey,
        )
    }

    private fun createFingerprint(path: String, size: Long): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            updateLong(digest, size)

            RandomAccessFile(File(path), "r").use { input ->
                val sampleSize = minOf(SAMPLE_BYTES.toLong(), size).toInt()
                if (sampleSize > 0) {
                    val offsets = linkedSetOf(
                        0L,
                        ((size - sampleSize) / 2L).coerceAtLeast(0L),
                        (size - sampleSize).coerceAtLeast(0L),
                    )
                    val buffer = ByteArray(sampleSize)

                    offsets.forEach { offset ->
                        updateLong(digest, offset)
                        input.seek(offset)
                        val bytesRead = input.read(buffer)
                        if (bytesRead > 0) {
                            digest.update(buffer, 0, bytesRead)
                        }
                    }
                }
            }

            digest.digest().toHex()
        }.getOrNull()
    }

    private fun updateLong(digest: MessageDigest, value: Long) {
        for (shift in 56 downTo 0 step 8) {
            digest.update((value ushr shift).toByte())
        }
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        return buildString(size * 2) {
            this@toHex.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(digits[value ushr 4])
                append(digits[value and 0x0F])
            }
        }
    }

    private fun encodeAlias(path: String): String {
        return JSONObject()
            .put(JSON_PATH, path)
            .toString()
    }

    private fun decodeAliasPath(value: String): String? {
        if (value.isBlank()) {
            return null
        }

        return runCatching {
            JSONObject(value).optString(JSON_PATH).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun pathAliasKey(path: String): String {
        return "$PATH_ALIAS_PREFIX$path"
    }

    private fun preferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private data class FileCandidate(
        val scope: String,
        val size: Long,
    ) {
        val sizeMarkerKey = "$SIZE_MARKER_PREFIX$scope:$size"
    }

    private data class FileIdentity(
        val aliasKey: String,
        val sizeMarkerKey: String,
    )
}
