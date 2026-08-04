package org.fossify.gallery.helpers

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fossify.gallery.R

object SourceLinkManager {
    private const val CHROME_PACKAGE = "com.android.chrome"
    private const val HORIZONTAL_PADDING_DP = 24
    private const val VERTICAL_PADDING_DP = 8

    fun handle(activity: Activity, path: String) {
        if (path.isBlank()) {
            Toast.makeText(activity, R.string.source_photo_not_available, Toast.LENGTH_SHORT).show()
            return
        }

        val storedUrl = SourceLinkPreferences.get(activity, path)
        if (storedUrl.isBlank()) {
            showLinkDialog(activity, path, existingUrl = "")
        } else {
            showSourceActions(activity, path, storedUrl)
        }
    }

    fun hasSource(context: Context, path: String): Boolean {
        return path.isNotBlank() && SourceLinkPreferences.get(context, path).isNotBlank()
    }

    fun openStoredSource(activity: Activity, path: String) {
        val storedUrl = SourceLinkPreferences.get(activity, path)
        if (storedUrl.isBlank()) {
            handle(activity, path)
        } else {
            openUrl(activity, storedUrl)
        }
    }

    private fun showSourceActions(activity: Activity, path: String, storedUrl: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.source)
            .setMessage(storedUrl)
            .setNegativeButton(R.string.delete_source) { _, _ ->
                confirmDelete(activity, path)
            }
            .setNeutralButton(R.string.edit_source) { _, _ ->
                showLinkDialog(activity, path, storedUrl)
            }
            .setPositiveButton(R.string.open_source) { _, _ ->
                openUrl(activity, storedUrl)
            }
            .show()
    }

    private fun confirmDelete(activity: Activity, path: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_source)
            .setMessage(R.string.delete_source_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_source) { _, _ ->
                SourceLinkPreferences.remove(activity, path)
                Toast.makeText(activity, R.string.source_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showLinkDialog(activity: Activity, path: String, existingUrl: String) {
        val density = activity.resources.displayMetrics.density
        val horizontalPadding = (HORIZONTAL_PADDING_DP * density).toInt()
        val verticalPadding = (VERTICAL_PADDING_DP * density).toInt()

        val input = EditText(activity).apply {
            hint = activity.getString(R.string.source_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(existingUrl)
            setSelection(text.length)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val title = if (existingUrl.isBlank()) R.string.add_source else R.string.edit_source
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.paste, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                pasteClipboard(activity, input)
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalizedUrl = normalizeUrl(input.text?.toString().orEmpty())
                if (normalizedUrl == null) {
                    input.error = activity.getString(R.string.invalid_source_url)
                    return@setOnClickListener
                }

                SourceLinkPreferences.put(activity, path, normalizedUrl)
                dialog.dismiss()
                Toast.makeText(activity, R.string.source_saved, Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun pasteClipboard(activity: Activity, input: EditText) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val pastedText = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(activity)
            ?.toString()
            .orEmpty()

        if (pastedText.isNotBlank()) {
            input.setText(pastedText)
            input.setSelection(input.text.length)
        }
    }

    private fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val candidate = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val uri = Uri.parse(candidate)
        val schemeIsValid = uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)

        return candidate.takeIf { schemeIsValid && !uri.host.isNullOrBlank() }
    }

    private fun openUrl(activity: Activity, url: String) {
        val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(CHROME_PACKAGE)
        }

        val opened = runCatching {
            activity.startActivity(chromeIntent)
            true
        }.getOrDefault(false)

        if (!opened) {
            Toast.makeText(activity, R.string.chrome_secure_folder_required, Toast.LENGTH_LONG).show()
        }
    }
}
