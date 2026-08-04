package org.fossify.gallery.helpers

import org.json.JSONObject
import java.io.File

internal object SourceLinkAlias {
    private const val INTERNAL_PREFIX = "__source_link_"
    private const val PATH_ALIAS_PREFIX = "${INTERNAL_PREFIX}path_alias:"
    private const val JSON_PATH = "path"

    fun pathMetadataKey(path: String): String {
        return "$PATH_ALIAS_PREFIX$path"
    }

    fun encodePath(path: String): String {
        return JSONObject()
            .put(JSON_PATH, path)
            .toString()
    }

    fun decodePath(value: String): String? {
        if (value.isBlank()) {
            return null
        }

        return runCatching {
            JSONObject(value).optString(JSON_PATH).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun canOwn(currentOwner: String?, oldPath: String?, newPath: String): Boolean {
        return when {
            currentOwner == null -> true
            currentOwner == oldPath -> true
            currentOwner == newPath -> true
            else -> !File(currentOwner).isFile
        }
    }
}
