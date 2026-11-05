package de.kalorienkompass.ui.progress

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kalorienkompass.data.db.DailyActivityEntity
import de.kalorienkompass.data.db.DailyNutritionTotals
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.data.model.ThemeMode
import de.kalorienkompass.data.settings.AppSettings
import de.kalorienkompass.data.settings.NutritionGoalSettings
import de.kalorienkompass.data.settings.UserProfile
import de.kalorienkompass.domain.DailyTargets
import de.kalorienkompass.testing.DialogLayoutFlushRule
import de.kalorienkompass.ui.components.charts.BarChart
import de.kalorienkompass.ui.components.charts.ChartBar
import de.kalorienkompass.ui.components.charts.ChartPoint
import de.kalorienkompass.ui.components.charts.LineChart
import de.kalorienkompass.ui.components.charts.Sparkline
import de.kalorienkompass.ui.theme.AppColors
import de.kalorienkompass.ui.theme.KalorienTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import kotlin.math.abs

/**
 * Renders the charts, the progress cards, the history list and the measurement dialog with
 * Robolectric (native graphics). Set the environment variable KK_PREVIEW_DIR to also write PNGs.
 *
 * Images are taken with [capture] (software `View.draw` of the node's window), not `captureToImage()`:
 * under Robolectric's paused looper compose-ui-test 1.7's `captureToImage()` posts a redraw to the main
 * looper and then blocks that thread waiting for it (ComposeTimeoutException after 2 s), see
 * `smoke/SmokeSupport.kt` → `Screenshots`. [DialogLayoutFlushRule] lets the dialogs with text fields idle.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressRenderTest {

    @get:Rule(order = 0)
    val dialogLayout = DialogLayoutFlushRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val today = LocalDate.now()
    private val mint = Color(0xFF5EE6A8)

    @Test
    fun lineChart_drawsEmptySingleAndManyPointsAndReactsToGestures() {
        var points by mutableStateOf(emptyList<ChartPoint>())
        compose.setContent {
            KalorienTheme(ThemeMode.DARK) {
                Box(
                    Modifier
                        .size(360.dp, 220.dp)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    LineChart(
                        points = points,
                        modifier = Modifier.testTag("line"),
                        goalValue = 80.0,
                        valueFormatter = { formatMeasurement(it, MeasurementType.WEIGHT) },
                    )
                }
            }
        }
        compose.onNodeWithTag("line").capture().save("line_empty")

        compose.runOnIdle { points = listOf(ChartPoint(today, 83.4)) }
        compose.onNodeWithTag("line").performTouchInput { click(center) }
        compose.onNodeWithTag("line").capture().save("line_single")

        compose.runOnIdle {
            points = (0 until 90).map { i ->
                ChartPoint(today.minusDays(89L - i), 86.0 - i * 0.05 + listOf(0.0, 0.25, -0.15)[i % 3])
            }
        }
        val many = compose.onNodeWithTag("line").capture()
        many.save("line_many")
        assertTrue("line is drawn in the primary color", many.pixelsNear(mint) > 100)

        compose.onNodeWithTag("line").performTouchInput { swipeRight() }
        compose.onNodeWithTag("line").performTouchInput { click(centerLeft) }
        compose.onNodeWithTag("line").capture().save("line_tooltip")
    }

    @Test
    fun barChart_highlightsBarsOverTheGoalAndShowsTooltip() {
        var bars by mutableStateOf(emptyList<ChartBar>())
        compose.setContent {
            KalorienTheme(ThemeMode.DARK) {
                Box(
                    Modifier
                        .size(360.dp, 200.dp)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    BarChart(
                        bars = bars,
                        modifier = Modifier.testTag("bars"),
                        barColor = AppColors.kcal,
                        goalValue = 2000.0,
                    )
                }
            }
        }
        compose.onNodeWithTag("bars").capture().save("bars_empty")

        compose.runOnIdle {
            bars = listOf(1800.0, 2450.0, 0.0, 1950.0, 2100.0, 1500.0, 900.0)
                .mapIndexed { i, v -> ChartBar(today.minusDays(6L - i), v) }
        }
        val week = compose.onNodeWithTag("bars").capture()
        week.save("bars_week")
        assertTrue("normal bars", week.pixelsNear(AppColors.kcal) > 200)
        assertTrue("bars over the goal", week.pixelsNear(AppColors.over) > 100)

        val tooltipColor = Color(0xFFE8EAED) // inverseSurface (dark)
        val before = week.pixelsNear(tooltipColor)
        compose.onNodeWithTag("bars").performTouchInput { click(center) }
        val withTooltip = compose.onNodeWithTag("bars").capture()
        withTooltip.save("bars_tooltip")
        assertTrue("tooltip box appears", withTooltip.pixelsNear(tooltipColor) > before + 200)

        compose.runOnIdle {
            bars = (0 until 53).map { i -> ChartBar(today.minusWeeks(52L - i), 1500.0 + (i % 9) * 120.0) }
        }
        compose.onNodeWithTag("bars").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("bars").capture().save("bars_year")
    }

    @Test
    fun sparkline_drawsOneAndManyPoints() {
        var points by mutableStateOf(listOf(ChartPoint(today, 90.0)))
        compose.setContent {
            KalorienTheme(ThemeMode.DARK) {
                Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    Sparkline(points, Modifier.size(width = 96.dp, height = 40.dp).testTag("spark"), lineColor = mint)
                }
            }
        }
        compose.onNodeWithTag("spark").capture()
        compose.runOnIdle { points = (0 until 10).map { ChartPoint(today.minusDays(9L - it), 92.0 - it * 0.3) } }
        assertTrue(compose.onNodeWithTag("spark").capture().pixelsNear(mint) > 20)
    }

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xhdpi")
    fun progressList_rendersAllCardsWithData() {
        val content = sampleContent()
        compose.setContent {
            KalorienTheme(ThemeMode.DARK) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    RangeSelector(
                        selected = content.range,
                        onSelect = {},
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    )
                    ProgressList(
                        content = content,
                        onAddMeasurement = { _, _ -> },
                        onOpenHistory = {},
                        onOpenDiary = {},
                        onOpenHealthConnect = {},
                    )
                }
            }
        }
        listOf("Gewicht", "Kalorien", "Makronährstoffe", "Schritte", "Körpermaße", "Bauchumfang", "Körperfett")
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onRoot().capture().save("progress_full")
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xhdpi")
    fun progressList_showsEmptyStatesWithCallsToAction() {
        val content = buildProgressContent(
            range = ProgressRange.WEEK,
            today = today,
            nutrition = emptyList(),
            activity = emptyList(),
            weights = emptyList(),
            body = emptyMap(),
            profile = UserProfile(onboardingDone = true),
            settings = AppSettings(),
            goals = NutritionGoalSettings(),
            targets = DailyTargets(2100, 263, 105, 70, emptyMap()),
        )
        compose.setContent {
            KalorienTheme(ThemeMode.DARK) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    ProgressList(
                        content = content,
                        onAddMeasurement = { _, _ -> },
                        onOpenHistory = {},
                        onOpenDiary = {},
                        onOpenHealthConnect = {},
                    )
                }
            }
        }
        listOf("Gewicht eintragen", "Zum Tagebuch", "Health Connect verbinden", "Körpermaß hinzufügen")
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onRoot().capture().save("progress_empty")
    }

    @Test
    @Config(qualifiers = "w411dp-h1800dp-xhdpi")
    fun historyList_rendersChartAndMonthGroups() {
        val entries = (0 until 12).map { i ->
            MeasurementEntity(
                id = i + 1L,
                type = MeasurementType.WEIGHT,
                date = today.minusDays(3L * (11 - i)),
                value = 85.0 - i * 0.3,
                source = if (i % 4 == 0) DataSource.HEALTH_CONNECT else DataSource.MANUAL,
                createdAt = i.toLong(),
            )
        }
        val content = buildHistoryContent(MeasurementType.WEIGHT, entries, UserProfile(targetWeightKg = 80.0), today)
        compose.setContent {
            KalorienTheme(ThemeMode.DARK) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    HistoryList(content = content, padding = PaddingValues(0.dp), onEntryClick = {}, onAdd = {})
                }
            }
        }
        compose.onNodeWithText("Heute").assertExists()
        compose.onNodeWithText(monthTitle(content.months.first().month)).assertExists()
        compose.onRoot().capture().save("history")
    }

    @Test
    fun measurementDialog_validatesSwitchesTypePicksDateAndSaves() {
        var saved: Triple<MeasurementType, LocalDate, Double>? = null
        var dismissed = false
        compose.setContent {
            KalorienTheme(ThemeMode.DARK) {
                MeasurementDialog(
                    title = "Messwert hinzufügen",
                    initialType = MeasurementType.WEIGHT,
                    typeSelectable = true,
                    initialDate = today,
                    initialValue = 83.4,
                    prefillFor = { if (it == MeasurementType.WAIST) 92.0 else null },
                    existingManualValue = { type, date -> if (type == MeasurementType.WAIST && date == today) 92.0 else null },
                    onSave = { type, date, value -> saved = Triple(type, date, value) },
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNode(hasSetTextAction()).assertTextContains("83,4")
        compose.onNode(isDialog()).capture().save("dialog_add")

        compose.onNodeWithText("Bauchumfang").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("92")
        compose.onNodeWithText("Ersetzt den Eintrag", substring = true).assertExists()

        compose.onNode(hasSetTextAction()).performTextReplacement("5")
        compose.onNodeWithText("Speichern").assertIsNotEnabled()
        compose.onNodeWithText("Bitte einen Wert zwischen", substring = true).assertExists()

        compose.onNode(hasSetTextAction()).performTextReplacement("91,5")
        compose.onNodeWithText("Ändern").performClick()
        compose.onNodeWithText("Übernehmen").assertExists().performClick()
        compose.onNodeWithText("Übernehmen").assertDoesNotExist()

        compose.onNodeWithText("Speichern").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(Triple(MeasurementType.WAIST, today, 91.5), saved)
            assertTrue(dismissed)
        }
    }

    @Test
    fun measurementDialog_readOnlyForHealthConnectValues() {
        var deleted = false
        compose.setContent {
            KalorienTheme(ThemeMode.DARK) {
                MeasurementDialog(
                    title = "Eintrag bearbeiten",
                    initialType = MeasurementType.WEIGHT,
                    typeSelectable = false,
                    initialDate = today.minusDays(2),
                    initialValue = 84.2,
                    isEdit = true,
                    readOnlyHint = "Dieser Wert wurde aus Health Connect übernommen.",
                    onSave = { _, _, _ -> error("must not save") },
                    onDismiss = {},
                    onDelete = { deleted = true },
                )
            }
        }
        compose.onNodeWithText("Schließen").assertExists()
        compose.onNodeWithText("Speichern").assertDoesNotExist()
        compose.onNodeWithText("Dieser Wert wurde aus Health Connect übernommen.").assertExists()
        compose.onNode(isDialog()).capture().save("dialog_readonly")
        compose.onNode(hasContentDescription("Eintrag löschen")).performClick()
        compose.runOnIdle { assertTrue(deleted) }
    }

    // ---------------------------------------------------------------------------------------------

    private fun sampleContent(): ProgressContent {
        val weights = (0 until 40).map { i ->
            MeasurementEntity(
                id = i + 1L,
                type = MeasurementType.WEIGHT,
                date = today.minusDays(39L - i),
                value = 86.0 - i * 0.08 + listOf(0.0, 0.3, -0.2, 0.1)[i % 4],
                createdAt = i.toLong(),
            )
        }
        val nutrition = (0 until 30).mapNotNull { i ->
            val kcal = listOf(1850.0, 2150.0, 1700.0, 2400.0, 1950.0, 0.0)[i % 6]
            if (kcal == 0.0) {
                null
            } else {
                DailyNutritionTotals(
                    date = today.minusDays(29L - i),
                    kcal = kcal,
                    protein = kcal * 0.2 / 4,
                    carbs = kcal * 0.48 / 4,
                    fat = kcal * 0.32 / 9,
                )
            }
        }
        val activity = (0 until 30).map { i ->
            DailyActivityEntity(
                date = today.minusDays(29L - i),
                steps = listOf(6500L, 11200L, 8300L, 4200L, 12800L, 9100L, 7400L)[i % 7],
                source = DataSource.HEALTH_CONNECT,
            )
        }
        val body = mapOf(
            MeasurementType.WAIST to (0 until 5).map { i ->
                MeasurementEntity(
                    id = 100L + i,
                    type = MeasurementType.WAIST,
                    date = today.minusDays(28L - i * 7),
                    value = 94.0 - i * 0.6,
                    createdAt = i.toLong(),
                )
            },
            MeasurementType.BODY_FAT to listOf(
                MeasurementEntity(id = 200L, type = MeasurementType.BODY_FAT, date = today.minusDays(3), value = 24.5),
            ),
        )
        return buildProgressContent(
            range = ProgressRange.MONTH,
            today = today,
            nutrition = nutrition,
            activity = activity,
            weights = weights,
            body = body,
            profile = UserProfile(onboardingDone = true, startWeightKg = 88.0, targetWeightKg = 80.0),
            settings = AppSettings(stepGoal = 10_000, healthConnectEnabled = true),
            goals = NutritionGoalSettings(),
            targets = DailyTargets(2100, 263, 105, 70, emptyMap()),
        )
    }

    /**
     * Image of this node: its window (activity or dialog) drawn in software by Robolectric's native
     * graphics, cropped to the node's bounds.
     */
    private fun SemanticsNodeInteraction.capture(): ImageBitmap {
        compose.waitForIdle()
        val node = fetchSemanticsNode()
        val nodeRoot = node.layoutInfo.topmost()
        val window = windowRoots().firstOrNull { window ->
            composeRoots(window).any { it.semanticsOwner.unmergedRootSemanticsNode.layoutInfo.topmost() === nodeRoot }
        } ?: throw AssertionError("No window shows the node $node")
        val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { window.draw(Canvas(bitmap)) }
        val bounds = node.boundsInWindow
        val left = bounds.left.toInt().coerceIn(0, window.width - 1)
        val top = bounds.top.toInt().coerceIn(0, window.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, window.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, window.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top).asImageBitmap()
    }

    private fun LayoutInfo.topmost(): LayoutInfo = generateSequence(this) { it.parentInfo }.last()

    /** Root views of all app windows (WindowManagerGlobal), like `Screenshots` in the smoke tests. */
    private fun windowRoots(): List<View> {
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = globalClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<View>
        return views.filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
    }

    private fun composeRoots(view: View): List<ViewRootForTest> = when (view) {
        is ViewRootForTest -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { composeRoots(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun ImageBitmap.pixelsNear(color: Color, tolerance: Float = 0.06f): Int {
        val map = toPixelMap()
        var count = 0
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                val c = map[x, y]
                if (abs(c.red - color.red) <= tolerance &&
                    abs(c.green - color.green) <= tolerance &&
                    abs(c.blue - color.blue) <= tolerance
                ) {
                    count++
                }
            }
        }
        return count
    }

    private fun ImageBitmap.save(name: String) {
        val dir = System.getenv("KK_PREVIEW_DIR")?.takeIf { it.isNotBlank() } ?: return
        File(dir).mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { out ->
            asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
