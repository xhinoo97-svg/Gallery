package org.fossify.gallery.activities

import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager.widget.ViewPager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.updateMarginWithBase
import org.fossify.commons.extensions.updatePaddingWithBase
import org.fossify.gallery.R
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.SourceLinkManager
import org.fossify.gallery.helpers.SourceLinkPreferences

abstract class BaseViewerActivity : SimpleActivity() {
    override val padCutout: Boolean = false
    abstract val contentHolder: View
    abstract val appBarLayout: AppBarLayout

    private var sourcePager: ViewPager? = null

    private val sourcePageChangeListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            refreshSourceLinkButton()
        }
    }

    private val sourcePreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            refreshSourceLinkButton()
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
        attachSourcePagerListener()

        findViewById<MaterialButton>(R.id.source_link_button)?.apply {
            setOnClickListener {
                val path = getCurrentSourcePath()
                if (SourceLinkManager.hasSource(this@BaseViewerActivity, path)) {
                    SourceLinkManager.openStoredSource(this@BaseViewerActivity, path)
                } else {
                    SourceLinkManager.handle(this@BaseViewerActivity, path)
                }
            }
            post { refreshSourceLinkButton() }
        }
    }

    override fun onPause() {
        SourceLinkPreferences.unregister(this, sourcePreferencesListener)
        sourcePager?.removeOnPageChangeListener(sourcePageChangeListener)
        sourcePager = null
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

    @Suppress("UNUSED_PARAMETER")
    fun onSourceLinkMenuClick(item: MenuItem): Boolean {
        SourceLinkManager.handle(this, getCurrentSourcePath())
        return true
    }

    private fun attachSourcePagerListener() {
        val pager = findViewById<ViewPager>(R.id.view_pager)
        if (pager !== sourcePager) {
            sourcePager?.removeOnPageChangeListener(sourcePageChangeListener)
            sourcePager = pager
            sourcePager?.addOnPageChangeListener(sourcePageChangeListener)
        }
    }

    private fun refreshSourceLinkButton() {
        val button = findViewById<MaterialButton>(R.id.source_link_button) ?: return
        val path = getCurrentSourcePath()

        if (path.isBlank()) {
            button.visibility = View.GONE
            return
        }

        val hasSource = SourceLinkManager.hasSource(this, path)
        button.visibility = View.VISIBLE
        button.setText(if (hasSource) R.string.go_to_source else R.string.add_link)
    }

    private fun getCurrentSourcePath(): String {
        return runCatching {
            javaClass.getDeclaredMethod("getCurrentPath").apply {
                isAccessible = true
            }.invoke(this) as? String
        }.getOrNull().orEmpty()
    }
}
