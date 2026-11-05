package de.kalorienkompass.testing

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Lets Compose dialogs that contain a text field become idle under Robolectric.
 *
 * The problem (Robolectric only, not the app): a default-width dialog window with a text field on a screen
 * wider than 320 dp ends every view traversal with a Compose measure/layout still pending. On a device
 * the frame that follows flushes it – `AndroidComposeView.dispatchDraw()` runs `measureAndLayout()` first –
 * but Robolectric never draws dialog windows (draw modifiers inside them never run). compose-ui-test's
 * Robolectric idling strategy sees `hasPendingMeasureOrLayout`, requests another layout, the traversal
 * leaves the next pending pass, and so on until `AppNotIdleException: Compose did not get idle after …
 * 60 SECONDS`. A bare `BasicTextField` in a default `Dialog` reproduces it; the same dialog on a 320 dp
 * screen, with `usePlatformDefaultWidth = false` or with a `Text` instead of the field becomes idle.
 *
 * The fix: after each layout traversal of an app window, this rule posts what the device's draw would do –
 * running the pending Compose measure/layout of that window. Roots without pending work are untouched;
 * nothing in the app is changed.
 *
 * compose-ui-test owns [ViewRootForTest.onViewCreatedCallback] (and checks that it is its own), so new
 * windows are detected where Android registers them: `WindowManagerGlobal.mViews`. Windows that exist
 * before the rule starts are not hooked – apply it outside the compose rule (lower `order`).
 */
class DialogLayoutFlushRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val restore = hookWindowRegistration()
            try {
                base.evaluate()
            } finally {
                restore()
            }
        }
    }

    /** Replaces `WindowManagerGlobal.mViews` by a list that reports added windows; returns the undo action. */
    private fun hookWindowRegistration(): () -> Unit {
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        val field = globalClass.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val original = field.get(global) as ArrayList<View>
        val hooked = object : ArrayList<View>(original) {
            override fun add(element: View): Boolean = super.add(element).also { flushAfterLayout(element) }
        }
        field.set(global, hooked)
        return {
            original.clear()
            original.addAll(hooked)
            field.set(global, original)
        }
    }

    private fun flushAfterLayout(windowRoot: View) {
        // Not attached yet: the floating ViewTreeObserver is merged into the window's one on attach.
        // Posted: while the global-layout listeners run nothing is pending yet – the work appears after them
        // (e.g. Compose's position update). The post runs in the same looper drain, like the device's draw.
        windowRoot.viewTreeObserver.addOnGlobalLayoutListener(
            ViewTreeObserver.OnGlobalLayoutListener { windowRoot.post { flushPendingComposeLayout(windowRoot) } },
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun flushPendingComposeLayout(view: View) {
        if (view is ViewRootForTest) {
            if (view.hasPendingMeasureOrLayout) view.measureAndLayoutForTest()
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) flushPendingComposeLayout(view.getChildAt(i))
        }
    }
}
