package org.fossify.gallery.helpers

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal data class SourceLinkFileCandidate(
    val scope: String,
    val size: Long,
    val sizeMarkerKey: String,
)

internal data class SourceLinkFileIdentity(
    val aliasKey: String,
    val sizeMarkerKey: String,
)

internal object SourceLinkIdentity {
    private const val INTERNAL_PREFIX = "__source_link_"
    private const val ALIAS_PREFIX = "${INTERNAL_PREFIX}alias:"
    private const val SIZE_MARKER_PREFIX = "${INTERNAL_PREFIX}size:"
    private const val SAMPLE_BYTES = 16_384
    private const val EMULATED_STORAGE_SCOPE_SEGMENTS = 4
    private const val REMOVABLE_STORAGE_SCOPE_SEGMENTS = 3
    private const val MIDDLE_OFFSET_DIVISOR = 2L
    private const val LONG_HIGHEST_SHIFT = 56
    private const val BITS_PER_BYTE = 8
    private const val HEX_HIGH_SHIFT = 4
    private const val BYTE_MASK = 0xFF
    private const val HEX_LOW_MASK = 0x0F
    private const val HEX_CHARS_PER_BYTE = 2
    private const val HEX_DIGITS = "0123456789abcdef"

    fun createCandidate(path: String): SourceLinkFileCandidate? {
        if (path.startsWith("content://", ignoreCase = true)) {
            return null
        }

        val file = File(path)
        if (!file.isFile) {
            return null
        }

        val scope = getTrackingScope(file) ?: return null
        val size = file.length()
        return SourceLinkFileCandidate(
            scope = scope,
            size = size,
            sizeMarkerKey = "$SIZE_MARKER_PREFIX$scope:$size",
        )
    }

    fun resolve(path: String, candidate: SourceLinkFileCandidate): SourceLinkFileIdentity? {
        val fingerprint = createFingerprint(path, candidate.size) ?: return null
        return SourceLinkFileIdentity(
            aliasKey = "$ALIAS_PREFIX${candidate.scope}:${candidate.size}:$fingerprint",
            sizeMarkerKey = candidate.sizeMarkerKey,
        )
    }

    private fun getTrackingScope(file: File): String? {
        val directories = file.absolutePath
            .split(File.separatorChar)
            .filter(String::isNotBlank)
            .dropLast(1)

        if (directories.isEmpty()) {
            return null
        }

        val segmentCount = when {
            isEmulatedStorage(directories) -> EMULATED_STORAGE_SCOPE_SEGMENTS
            isRemovableStorage(directories) -> REMOVABLE_STORAGE_SCOPE_SEGMENTS
            else -> directories.size
        }

        return File.separator + directories.take(segmentCount).joinToString(File.separator)
    }

    private fun isEmulatedStorage(directories: List<String>): Boolean {
        return directories.size >= EMULATED_STORAGE_SCOPE_SEGMENTS &&
            directories[0] == "storage" &&
            directories[1] == "emulated"
    }

    private fun isRemovableStorage(directories: List<String>): Boolean {
        return directories.size >= REMOVABLE_STORAGE_SCOPE_SEGMENTS &&
            directories[0] == "storage"
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
                        ((size - sampleSize) / MIDDLE_OFFSET_DIVISOR).coerceAtLeast(0L),
                        (size - sampleSize).coerceAtLeast(0L),
                    )
                    val buffer = ByteArray(sampleSize)

                    offsets.forEach { offset ->
                        updateLong(digest, offset)
                        input.seek(offset)
                        input.readFully(buffer)
                        digest.update(buffer)
                    }
                }
            }

            digest.digest().toHex()
        }.getOrNull()
    }

    private fun updateLong(digest: MessageDigest, value: Long) {
        for (shift in LONG_HIGHEST_SHIFT downTo 0 step BITS_PER_BYTE) {
            digest.update((value ushr shift).toByte())
        }
    }

    private fun ByteArray.toHex(): String {
        return buildString(size * HEX_CHARS_PER_BYTE) {
            this@toHex.forEach { byte ->
                val value = byte.toInt() and BYTE_MASK
                append(HEX_DIGITS[value ushr HEX_HIGH_SHIFT])
                append(HEX_DIGITS[value and HEX_LOW_MASK])
            }
        }
    }
}
