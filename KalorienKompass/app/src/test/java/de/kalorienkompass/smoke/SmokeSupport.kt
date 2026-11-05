package de.kalorienkompass.smoke

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import de.kalorienkompass.KalorienApp
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Job

/** Robolectric device used by all smoke tests: German locale, Pixel-7-like phone (411 × 891 dp, xxhdpi). */
object SmokeDevice {
    const val SDK = 35
    const val QUALIFIERS = "de-rDE-w411dp-h891dp-xxhdpi"
}

/** Output folder `app/build/smoke-screenshots` (override with -Dsmoke.screenshotDir=… on the test JVM). */
object SmokeOutput {
    val dir: File by lazy {
        val override = System.getProperty("smoke.screenshotDir")
        val result = if (!override.isNullOrBlank()) {
            File(override)
        } else {
            // Gradle runs unit tests with the module directory (app/) as working directory.
            val userDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
            val module = if (File(userDir, "src/main/AndroidManifest.xml").exists()) userDir else File(userDir, "app")
            File(module, "build/smoke-screenshots")
        }
        result.mkdirs()
        result
    }

    private var reportStarted = false

    /** Appends to `smoke-report.txt` (truncated once per test JVM). */
    @Synchronized
    fun report(line: String) {
        val file = File(dir, "smoke-report.txt")
        if (!reportStarted) {
            file.writeText("KalorienKompass UI smoke test report (Robolectric ${SmokeDevice.QUALIFIERS}, SDK ${SmokeDevice.SDK})\n\n")
            reportStarted = true
        }
        file.appendText(line + "\n")
    }
}

/** Facts about the environment that the suite reports once per JVM (startup/WorkManager behaviour). */
object SmokeEnvironment {
    @Volatile var startupNotesPrinted = false
}

/**
 * Outer rule for every smoke test:
 * - records uncaught exceptions of ANY thread (main-thread ViewModel coroutines, Dispatchers.IO, …)
 *   and fails the test with them – Compose only rethrows exceptions from its own effect scopes;
 * - simulates "offline": HTTP(S) traffic goes to a closed local port, so Open Food Facts / Anthropic calls
 *   fail fast and deterministically with an IOException (the app must handle that without crashing);
 * - initializes a test WorkManager: Robolectric does not run androidx.startup's InitializationProvider, so
 *   `WorkManager.getInstance()` would throw (KalorienApp.onCreate catches that – see [startupNotes]).
 */
class SmokeGuardRule : TestRule {

    val uncaught: MutableList<Pair<String, Throwable>> = Collections.synchronizedList(mutableListOf())

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val mainThread = Thread.currentThread()
            val previousDefault = Thread.getDefaultUncaughtExceptionHandler()
            val previousMain = mainThread.uncaughtExceptionHandler
            val recorder = Thread.UncaughtExceptionHandler { thread, error ->
                uncaught += thread.name to error
                System.err.println("SMOKE: uncaught exception on thread '${thread.name}': $error")
                error.printStackTrace()
            }
            Thread.setDefaultUncaughtExceptionHandler(recorder)
            mainThread.uncaughtExceptionHandler = recorder

            val savedProps = PROXY_PROPS.associateWith { System.getProperty(it) }
            val deadPort = ServerSocket(0).use { it.localPort } // closed again → connection refused
            System.setProperty("http.proxyHost", "127.0.0.1")
            System.setProperty("http.proxyPort", deadPort.toString())
            System.setProperty("https.proxyHost", "127.0.0.1")
            System.setProperty("https.proxyPort", deadPort.toString())
            System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1|[::1]")
            System.clearProperty("socksProxyHost")

            val finished = AtomicBoolean(false)
            val watchdog = startWatchdog(description, mainThread, finished)
            var failure: Throwable? = null
            try {
                startupNotes()
                initTestWorkManager()
                base.evaluate()
            } catch (t: Throwable) {
                failure = t
            } finally {
                finished.set(true)
                watchdog.interrupt()
                Thread.interrupted() // clear a watchdog interrupt so it cannot leak into the next test
                Thread.setDefaultUncaughtExceptionHandler(previousDefault)
                mainThread.uncaughtExceptionHandler = previousMain
                savedProps.forEach { (key, value) ->
                    if (value == null) System.clearProperty(key) else System.setProperty(key, value)
                }
                resetWorkManager()
            }
            val recorded = synchronized(uncaught) { uncaught.toList() }
            if (recorded.isNotEmpty()) {
                val (thread, first) = recorded.first()
                val error = AssertionError(
                    "App threw ${recorded.size} uncaught exception(s) during ${description.methodName}; " +
                        "first on thread '$thread': $first",
                    first,
                )
                failure?.let { error.addSuppressed(it) }
                recorded.drop(1).forEach { error.addSuppressed(it.second) }
                throw error
            }
            failure?.let { throw it }
        }
    }

    /**
     * JUnit timeouts move the test off Robolectric's main thread, so a dead-locked screen would hang CI
     * forever. Instead: after [INTERRUPT_AFTER_MS] dump all threads and interrupt the test thread (fails the
     * test if it waits in sleep/park/runBlocking); if that does not help, stop the test JVM.
     */
    private fun startWatchdog(description: Description, testThread: Thread, finished: AtomicBoolean): Thread =
        Thread({
            try {
                Thread.sleep(INTERRUPT_AFTER_MS)
                if (finished.get()) return@Thread
                System.err.println(
                    "SMOKE WATCHDOG: ${description.displayName} still running after ${INTERRUPT_AFTER_MS / 1000} s " +
                        "– interrupting it. Thread dump:\n${threadDump()}",
                )
                testThread.interrupt()
                Thread.sleep(HALT_AFTER_MS - INTERRUPT_AFTER_MS)
                if (finished.get()) return@Thread
                System.err.println("SMOKE WATCHDOG: ${description.displayName} is hanging – stopping the test JVM.\n${threadDump()}")
                System.err.flush()
                Runtime.getRuntime().halt(3)
            } catch (e: InterruptedException) {
                // test finished in time
            }
        }, "smoke-watchdog").apply {
            isDaemon = true
            start()
        }

    private fun threadDump(): String = Thread.getAllStackTraces().entries.joinToString("\n") { (thread, stack) ->
        "\"${thread.name}\" ${thread.state}\n" + stack.take(40).joinToString("\n") { "    at $it" }
    }

    /** Reports (once per JVM) what KalorienApp.onCreate logged while Robolectric created the app. */
    private fun startupNotes() {
        if (SmokeEnvironment.startupNotesPrinted) return
        SmokeEnvironment.startupNotesPrinted = true
        val initializedByStartup = try {
            WorkManager.isInitialized()
        } catch (t: Throwable) {
            false
        }
        val warnings = ShadowLog.getLogsForTag("KalorienApp").filter { it.type >= Log.WARN }
        val note = buildString {
            append("App startup under Robolectric: WorkManager initialized before test = $initializedByStartup")
            if (warnings.isEmpty()) {
                append("; KalorienApp logged no warnings.")
            } else {
                warnings.forEach { append("; KalorienApp ${it.msg}: ${it.throwable}") }
            }
        }
        println("SMOKE: $note")
        SmokeOutput.report("NOTE: $note")
    }

    /**
     * WorkManager keeps its instance in static fields, which survive from test to test in a Robolectric
     * sandbox. Reset them so that every test's KalorienApp.onCreate sees the same state as a real start.
     */
    @SuppressLint("RestrictedApi")
    private fun resetWorkManager() {
        try {
            WorkManagerTestInitHelper.closeWorkDatabase()
        } catch (_: Throwable) {
        }
        try {
            WorkManagerImpl.setDelegate(null)
        } catch (_: Throwable) {
        }
    }

    private fun initTestWorkManager() {
        val context = ApplicationProvider.getApplicationContext<KalorienApp>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.INFO)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }

    private companion object {
        const val INTERRUPT_AFTER_MS = 180_000L
        const val HALT_AFTER_MS = 240_000L
        val PROXY_PROPS = listOf(
            "http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort",
            "http.nonProxyHosts", "socksProxyHost",
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Device-like coroutine resumption for Compose effects
// ------------------------------------------------------------------------------------------------

/**
 * Makes Compose effect coroutines resume like on a device.
 *
 * compose-ui-test 1.7 runs effect coroutines (`rememberCoroutineScope`, `LaunchedEffect`, the recomposer) on an
 * `UnconfinedTestDispatcher`, i.e. they continue *inline* wherever they are resumed:
 * - after a suspension that completes on another thread (DataStore, Room, `withContext(Dispatchers.IO)`) the
 *   coroutine continues on THAT thread – correct app code such as
 *   `scope.launch { settings.updateProfile { … }; navController.navigate(…) }` then fails only in tests
 *   ("Method setCurrentState must be called on the main thread");
 * - an effect started during a measure pass that is not a Compose test frame (Robolectric runs View
 *   traversals when its clock advances) runs before the layout is attached – e.g.
 *   `LaunchedEffect(Unit) { focusRequester.requestFocus() }` fails with "FocusRequester is not initialized".
 *
 * On a device the AndroidUiDispatcher always *dispatches* to the main thread. This wraps the test
 * environment's continuation interceptor accordingly: inside a Compose test frame the framework's own
 * trampolining is kept, otherwise resumptions are posted to the main looper (and a coroutine whose Job was
 * cancelled meanwhile is not started any more). Must be applied right after the rule is created, before the
 * recomposer starts. If compose-ui-test internals change, it logs a warning and leaves the rule unchanged.
 */
fun <R : ComposeTestRule> R.withEffectsResumingOnMainThread(): R {
    try {
        val environment = declaredField(javaClass, "environment").get(this)!!
        val applying = declaredField(environment.javaClass, "recomposerContinuationInterceptor").get(environment)!!
        val delegateField = declaredField(applying.javaClass, "delegate")
        val original = delegateField.get(applying) as ContinuationInterceptor
        if (original !is MainThreadResumingInterceptor) {
            val deferringField = try {
                declaredField(original.javaClass, "isDeferringContinuations")
            } catch (e: NoSuchFieldException) {
                System.err.println("SMOKE: WARNING – Compose frame state not found ($e); effects resume inline on the main thread.")
                null
            }
            val inFrame: () -> Boolean = { deferringField?.getBoolean(original) ?: true }
            delegateField.set(applying, MainThreadResumingInterceptor(original, inFrame))
        }
    } catch (e: Exception) {
        System.err.println(
            "SMOKE: WARNING – could not make Compose effects resume like on a device ($e); coroutines in " +
                "rememberCoroutineScope/LaunchedEffect may continue on background threads in these tests.",
        )
    }
    return this
}

private fun declaredField(type: Class<*>, name: String): java.lang.reflect.Field {
    var current: Class<*>? = type
    while (current != null) {
        try {
            return current.getDeclaredField(name).apply { isAccessible = true }
        } catch (e: NoSuchFieldException) {
            current = current.superclass
        }
    }
    throw NoSuchFieldException("$name in ${type.name}")
}

private class MainThreadResumingInterceptor(
    private val inner: ContinuationInterceptor,
    private val inComposeFrame: () -> Boolean,
) : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
        MainThreadHop(inner.interceptContinuation(continuation), inComposeFrame)

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
        inner.releaseInterceptedContinuation((continuation as? MainThreadHop<*>)?.inner ?: continuation)
    }
}

private class MainThreadHop<T>(val inner: Continuation<T>, private val inComposeFrame: () -> Boolean) : Continuation<T> {
    override val context: CoroutineContext get() = inner.context

    override fun resumeWith(result: Result<T>) {
        val mainLooper = Looper.getMainLooper()
        if (mainLooper.isCurrentThread && inComposeFrame()) {
            inner.resumeWith(result) // the test frame clock defers it to the end of the frame
            return
        }
        Handler(mainLooper).post {
            val job = inner.context[Job]
            if (job != null && job.isCancelled) {
                inner.resumeWith(Result.failure(CancellationException("Coroutine was cancelled before it was resumed")))
            } else {
                inner.resumeWith(result)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Waiting
// ------------------------------------------------------------------------------------------------

/**
 * Advances both clocks a little and runs pending work: the Compose test clock (animations, LaunchedEffect
 * delays) and Robolectric's paused main looper (Handler/Dispatchers.Main delays such as debounce).
 */
fun ComposeTestRule.pump(stepMillis: Long = 50) {
    mainClock.advanceTimeBy(stepMillis)
    ShadowLooper.idleMainLooper(stepMillis, TimeUnit.MILLISECONDS)
    waitForIdle()
}

/**
 * Waits until the semantics tree stopped changing for [quietMillis] (real time, so that Room queries
 * and other background work can finish), at most [maxMillis].
 */
fun ComposeTestRule.settle(maxMillis: Long = 6_000, quietMillis: Long = 400): Boolean {
    val start = System.currentTimeMillis()
    var last: String? = null
    var stableSince = start
    while (true) {
        pump()
        val fingerprint = uiFingerprint()
        val now = System.currentTimeMillis()
        if (fingerprint != last) {
            last = fingerprint
            stableSince = now
        } else if (now - stableSince >= quietMillis) {
            return true
        }
        if (now - start >= maxMillis) {
            println("SMOKE: UI still changing after $maxMillis ms – continuing anyway")
            return false
        }
        Thread.sleep(10)
    }
}

/** Waits (pumping both clocks) until [condition] is true; false on timeout. */
fun ComposeTestRule.pumpUntil(timeoutMillis: Long = 8_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        pump()
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

// ------------------------------------------------------------------------------------------------
// Semantics helpers
// ------------------------------------------------------------------------------------------------

fun ComposeTestRule.roots(): List<SemanticsNode> =
    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

private fun SemanticsNode.walk(action: (SemanticsNode, Int) -> Unit, depth: Int = 0) {
    action(this, depth)
    children.forEach { it.walk(action, depth + 1) }
}

private fun SemanticsNode.label(): String {
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    val editable = config.getOrNull(SemanticsProperties.EditableText)?.text
    return listOfNotNull(text, description, editable?.let { "[$it]" }).joinToString(" | ")
}

/** Compact description of all roots: texts, descriptions, states and rounded bounds. */
fun ComposeTestRule.uiFingerprint(): String {
    val sb = StringBuilder()
    roots().forEach { root ->
        root.walk({ node, depth ->
            val b = node.boundsInRoot
            sb.append(depth).append(':').append(node.label())
            node.config.getOrNull(SemanticsProperties.ToggleableState)?.let { sb.append(" t=").append(it) }
            node.config.getOrNull(SemanticsProperties.Selected)?.let { sb.append(" s=").append(it) }
            node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.let { sb.append(" p=").append(it.current) }
            sb.append(" @").append(b.left.roundToInt()).append(',').append(b.top.roundToInt())
                .append(',').append(b.width.roundToInt()).append('x').append(b.height.roundToInt()).append('\n')
        })
    }
    return sb.toString()
}

/** All visible texts / content descriptions (for logs and failure messages). */
fun ComposeTestRule.visibleTexts(limit: Int = 60): List<String> {
    val result = mutableListOf<String>()
    roots().forEach { root ->
        root.walk({ node, _ ->
            val label = node.label()
            if (label.isNotBlank() && node.size.width > 0 && node.size.height > 0) result += label
        })
    }
    return result.distinct().take(limit)
}

/** Fails unless at least one node with text / content description is displayed. */
fun ComposeTestRule.assertHasDisplayedContent(context: String) {
    val roots = roots()
    if (roots.isEmpty()) throw AssertionError("$context: no Compose root found")
    val matcher = SemanticsMatcher("has text, content description or editable text") {
        it.config.contains(SemanticsProperties.Text) ||
            it.config.contains(SemanticsProperties.ContentDescription) ||
            it.config.contains(SemanticsProperties.EditableText)
    }
    val nodes = onAllNodes(matcher, useUnmergedTree = true)
    val count = nodes.fetchSemanticsNodes(atLeastOneRootRequired = false).size
    val displayed = (0 until minOf(count, 40)).any { nodes[it].isDisplayedSafe() }
    if (!displayed) {
        throw AssertionError("$context: the screen shows no displayed content (nodes with text: $count). Tree:\n${uiFingerprint()}")
    }
}

fun SemanticsNodeInteraction.isDisplayedSafe(): Boolean = try {
    assertIsDisplayed()
    true
} catch (e: AssertionError) {
    false
}

/** Matcher for a clickable, enabled node whose text or content description contains [label]. */
fun actionMatcher(label: String): SemanticsMatcher =
    hasClickAction() and isEnabled() and
        (hasText(label, substring = true, ignoreCase = true) or hasContentDescription(label, substring = true, ignoreCase = true))

/**
 * Clicks the first displayed, enabled, clickable node whose text or content description contains
 * [label] (scrolling it into view if needed). Returns a description of the clicked node, or null if
 * there is none. Only test-framework assertion failures (node missing / vanished) are swallowed –
 * exceptions thrown by the app propagate and fail the test.
 */
fun ComposeTestRule.clickFirst(label: String): String? {
    val nodes = onAllNodes(actionMatcher(label))
    val found = nodes.fetchSemanticsNodes(atLeastOneRootRequired = false)
    for (i in found.indices) {
        val node = nodes[i]
        if (!node.isDisplayedSafe()) {
            try {
                node.performScrollTo()
            } catch (e: AssertionError) {
                // not in a scrollable container
            }
            if (!node.isDisplayedSafe()) continue
        }
        val description = found[i].label().ifBlank { label }
        try {
            node.performClick()
        } catch (e: AssertionError) {
            continue
        }
        return description
    }
    return null
}

/** Types [text] into the first enabled editable text field. Returns false if there is none. */
fun ComposeTestRule.typeIntoFirstTextField(text: String): Boolean {
    val nodes = onAllNodes(hasSetTextAction() and isEnabled())
    if (nodes.fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()) return false
    return try {
        nodes[0].performTextInput(text)
        true
    } catch (e: AssertionError) {
        false
    }
}

/** If a dialog / bottom sheet / popup is open, tries to close it via a cancel-like button. */
fun ComposeTestRule.dismissDialogs(log: (String) -> Unit) {
    repeat(2) {
        if (roots().size <= 1) return
        val clicked = DISMISS_LABELS.firstNotNullOfOrNull { label -> clickFirst(label)?.let { label } } ?: return
        log("closed dialog via '$clicked'")
        settle()
    }
}

private val DISMISS_LABELS = listOf("Abbrechen", "Schließen", "Später", "Nicht jetzt")

// ------------------------------------------------------------------------------------------------
// Screenshots
// ------------------------------------------------------------------------------------------------

data class PixelStats(val width: Int, val height: Int, val distinctColors: Int, val lumaStdDev: Double) {
    /** A real rendering has text/icons on a background: anti-aliasing alone yields several colours. */
    val looksRendered: Boolean get() = width > 0 && height > 0 && distinctColors >= 3 && lumaStdDev > 0.2
    override fun toString(): String =
        "${width}x$height px, $distinctColors colours, luma σ=${"%.1f".format(java.util.Locale.ROOT, lumaStdDev)}"
}

object Screenshots {
    /** Set when `captureToImage()` failed once; later captures go straight to the window drawing. */
    @Volatile private var captureToImageBroken: Throwable? = null

    /**
     * Writes `app/build/smoke-screenshots/<name>.png` and fails if the image is blank.
     *
     * With a single window, `onRoot().captureToImage()` is tried first. Under Robolectric's PAUSED looper it
     * cannot work with compose-ui-test 1.7: its `forceRedraw()` posts the redraw to the main looper and then
     * spins (`Thread.sleep`) on that same – paused – main thread until a draw callback fires, which never
     * happens (ComposeTimeoutException after 2 s). It is therefore tried once per JVM; afterwards (and
     * whenever a dialog, bottom sheet or popup is open) every window of the app is drawn in software by
     * Robolectric's native graphics (`View.draw`) at its window position – including dialog dimming –
     * which also captures dialogs that `captureToImage()` of the activity root would miss.
     */
    fun capture(rule: ComposeTestRule, name: String, baseView: () -> View?): File {
        rule.waitForIdle()
        val base = baseView()?.rootView
        val others = base?.let { otherWindows(it) }.orEmpty()
        var method = "captureToImage"
        val bitmap: Bitmap = if (captureToImageBroken == null && others.isEmpty()) {
            try {
                rule.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                System.err.println(
                    "SMOKE: captureToImage() is not usable here ($e) – using Robolectric native View.draw() " +
                        "of all windows instead (see Screenshots.capture KDoc).",
                )
                captureToImageBroken = e
                method = "View.draw (captureToImage: ${e.javaClass.simpleName})"
                drawWindows(base ?: throw AssertionError("No view to draw for the screenshot", e), others)
            }
        } else {
            method = "View.draw of ${1 + others.size} window(s)"
            drawWindows(base ?: throw AssertionError("No view to draw for the screenshot $name"), others)
        }
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val stats = stats(pixels, width, height)
        val file = File(SmokeOutput.dir, "$name.png")
        writePng(pixels, width, height, file)
        println("SMOKE: screenshot ${file.name}: $stats via $method")
        SmokeOutput.report("    screenshot ${file.name}: $stats")
        if (!stats.looksRendered) {
            throw AssertionError(
                "Screenshot $name is blank ($stats, $method). Robolectric native graphics did not render the UI " +
                    "– check @GraphicsMode(NATIVE) and the native runtime for this OS/arch.",
            )
        }
        return file
    }

    /** Draws [base] and then the other app windows (popups, dialogs, sheets) in z-order. */
    private fun drawWindows(base: View, others: List<Pair<View, WindowManager.LayoutParams>>): Bitmap {
        require(base.width > 0 && base.height > 0) { "Window for screenshot has no size" }
        val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        base.draw(canvas)
        val screen = Rect(0, 0, base.width, base.height)
        for ((view, params) in others) {
            if (params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                canvas.drawColor(Color.argb((params.dimAmount.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0))
            }
            // Robolectric does not position windows; apply the window gravity like the window manager would.
            val frame = Rect()
            val gravity = if (params.gravity == 0) Gravity.TOP or Gravity.START else params.gravity
            Gravity.apply(gravity, view.width, view.height, screen, params.x, params.y, frame, base.layoutDirection)
            canvas.save()
            canvas.translate(frame.left.toFloat(), frame.top.toFloat())
            view.draw(canvas)
            canvas.restore()
        }
        return bitmap
    }

    /** Visible root views of all other windows (WindowManagerGlobal, in the order they were added). */
    private fun otherWindows(base: View): List<Pair<View, WindowManager.LayoutParams>> = try {
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = globalClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<View>
        @Suppress("UNCHECKED_CAST")
        val params = globalClass.getDeclaredField("mParams").apply { isAccessible = true }
            .get(global) as List<WindowManager.LayoutParams>
        views.zip(params).filter { (view, _) ->
            view !== base && view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width > 0 && view.height > 0
        }
    } catch (e: ReflectiveOperationException) {
        println("SMOKE: cannot list windows ($e) – screenshot shows the activity window only")
        emptyList()
    }

    /**
     * Minimal PNG encoder (8-bit RGB, no filtering). `java.awt`/`javax.imageio` are not on the Android
     * unit-test compile classpath and `Bitmap.compress` depends on the native graphics build.
     */
    private fun writePng(pixels: IntArray, width: Int, height: Int, file: File) {
        val raw = ByteArrayOutputStream(width * height)
        DeflaterOutputStream(raw, Deflater(6)).use { z ->
            val row = ByteArray(1 + width * 3)
            for (y in 0 until height) {
                row[0] = 0 // filter type: none
                var o = 1
                for (x in 0 until width) {
                    val p = pixels[y * width + x]
                    row[o++] = (p shr 16).toByte()
                    row[o++] = (p shr 8).toByte()
                    row[o++] = p.toByte()
                }
                z.write(row)
            }
        }
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            val header = ByteArrayOutputStream().also { h ->
                DataOutputStream(h).apply {
                    writeInt(width)
                    writeInt(height)
                    writeByte(8) // bit depth
                    writeByte(2) // colour type: truecolour RGB
                    writeByte(0) // compression
                    writeByte(0) // filter
                    writeByte(0) // no interlace
                }
            }.toByteArray()
            writeChunk(out, "IHDR", header)
            writeChunk(out, "IDAT", raw.toByteArray())
            writeChunk(out, "IEND", ByteArray(0))
        }
        check(file.length() > 0) { "PNG $file is empty" }
    }

    private fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.writeInt(crc.value.toInt())
    }

    private fun stats(pixels: IntArray, width: Int, height: Int): PixelStats {
        if (width == 0 || height == 0) return PixelStats(width, height, 0, 0.0)
        val colors = HashSet<Int>()
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val p = pixels[y * width + x]
                val luma = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                sum += luma
                sumSq += luma * luma
                n++
                if (colors.size < 5000) colors += p and 0xFFFFFF
                x += 3
            }
            y += 3
        }
        val mean = sum / n
        return PixelStats(width, height, colors.size, sqrt(max(0.0, sumSq / n - mean * mean)))
    }
}
