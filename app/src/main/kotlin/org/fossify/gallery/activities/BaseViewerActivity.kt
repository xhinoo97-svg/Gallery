package org.fossify.gallery.activities

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.updateMarginWithBase
import org.fossify.commons.extensions.updatePaddingWithBase
import org.fossify.gallery.R
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.SourceLinkManager
import org.fossify.gallery.helpers.SourceLinkPreferences
import org.fossify.gallery.helpers.sourceLinkPreferenceAffectsVisibleLink

abstract class BaseViewerActivity : SimpleActivity() {
    override val padCutout: Boolean = false
    abstract val contentHolder: View
    abstract val appBarLayout: AppBarLayout

    private var sourceRefreshJob: Job? = null
    private val sourceRefreshRunnable = Runnable { refreshSourceLinkButton() }

    private val sourcePreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (sourceLinkPreferenceAffectsVisibleLink(key)) {
                scheduleSourceLinkButtonRefresh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contentRoot = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { _, insets ->
            setupEdgeToEdge(insets)
            insets
        }
        registerShowNotchCollector(contentRoot)
    }

    override fun onResume() {
        super.onResume()
        SourceLinkPreferences.register(this, sourcePreferencesListener)

        findViewById<MaterialButton>(R.id.source_link_button)?.apply {
            setOnClickListener {
                val path = getCurrentSourcePath()
                if (tag == true) {
                    SourceLinkManager.openStoredSource(this@BaseViewerActivity, path)
                } else {
                    SourceLinkManager.handle(this@BaseViewerActivity, path)
                }
            }
            scheduleSourceLinkButtonRefresh()
        }
    }

    override fun onPause() {
        SourceLinkPreferences.unregister(this, sourcePreferencesListener)
        sourceRefreshJob?.cancel()
        findViewById<View>(R.id.source_link_button)?.removeCallbacks(sourceRefreshRunnable)
        super.onPause()
    }

    private fun registerShowNotchCollector(view: View) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                config.showNotchFlow.collect {
                    view.requestApplyInsets()
                }
            }
        }
    }

    private fun setupEdgeToEdge(insets: WindowInsetsCompat) {
        if (config.showNotch) {
            val systemAndCutout =
                insets.getInsetsIgnoringVisibility(Type.systemBars() or Type.displayCutout())
            appBarLayout.updatePaddingWithBase(
                top = systemAndCutout.top,
                left = systemAndCutout.left,
                right = systemAndCutout.right,
            )

            contentHolder.updatePaddingWithBase(left = 0, top = 0, right = 0, bottom = 0)
        } else {
            val system = insets.getInsetsIgnoringVisibility(Type.systemBars())
            val cutout = insets.getInsetsIgnoringVisibility(Type.displayCutout())
            appBarLayout.updatePaddingWithBase(
                top = if (cutout.top > 0) 0 else system.top,
                left = if (cutout.left > 0) 0 else system.left,
                right = if (cutout.right > 0) 0 else system.right,
            )

            contentHolder.updatePaddingWithBase(
                left = cutout.left,
                top = cutout.top,
                right = cutout.right,
                bottom = cutout.bottom,
            )
        }
    }

    fun applyProperHorizontalInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            if (config.showNotch) {
                val systemAndCutout =
                    insets.getInsetsIgnoringVisibility(Type.systemBars() or Type.displayCutout())
                view.updateMarginWithBase(
                    left = systemAndCutout.left,
                    right = systemAndCutout.right,
                )
            } else {
                val system = insets.getInsetsIgnoringVisibility(Type.systemBars())
                val cutout = insets.getInsetsIgnoringVisibility(Type.displayCutout())
                view.updateMarginWithBase(
                    left = if (cutout.left > 0) 0 else system.left,
                    right = if (cutout.right > 0) 0 else system.right,
                )
            }
            insets
        }
    }

    private fun scheduleSourceLinkButtonRefresh() {
        findViewById<View>(R.id.source_link_button)?.apply {
            removeCallbacks(sourceRefreshRunnable)
            post(sourceRefreshRunnable)
        }
    }

    protected fun refreshSourceLinkButton() {
        val button = findViewById<MaterialButton>(R.id.source_link_button) ?: return
        val path = getCurrentSourcePath()
        sourceRefreshJob?.cancel()

        if (path.isBlank()) {
            button.visibility = View.GONE
            button.isEnabled = false
            button.tag = false
            return
        }

        button.isEnabled = false
        sourceRefreshJob = lifecycleScope.launch {
            val hasSource = withContext(Dispatchers.IO) {
                SourceLinkManager.hasSource(applicationContext, path)
            }

            if (getCurrentSourcePath() != path) {
                return@launch
            }

            button.visibility = View.VISIBLE
            button.setText(if (hasSource) R.string.go_to_source else R.string.add_link)
            button.tag = hasSource
            button.isEnabled = true
        }
    }

    protected open fun getCurrentSourcePath(): String = ""
}
