package de.kalorienkompass.smoke

import android.view.View
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import de.kalorienkompass.MainActivity
import de.kalorienkompass.health.HealthWorkScheduler
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Starts the real [MainActivity] (AppRoot decides the start destination from the stored profile):
 * fresh install → onboarding; seeded data → diary, then every bottom-bar tab. See [ScreensSmokeTest]
 * for how to run the suite and where the screenshots go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SmokeDevice.SDK], qualifiers = SmokeDevice.QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowSmokeTest {

    @get:Rule(order = 0)
    val guard = SmokeGuardRule()

    /** Empty rule: data must be seeded BEFORE the activity starts (AppRoot reads the profile once). */
    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule().withEffectsResumingOnMainThread()

    private var decorView: View? = null

    @Test
    fun freshInstall_showsOnboarding() {
        SmokeOutput.report("\n== AppFlow fresh install (MainActivity)")
        TestData.seedFreshInstall()
        launchMainActivity().use {
            val shown = composeRule.pumpUntil {
                composeRule.onAllNodes(hasText("willkommen", substring = true, ignoreCase = true))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            composeRule.settle()
            assertTrue(
                "Fresh install must start with the onboarding ('Willkommen …'); visible: ${composeRule.visibleTexts()}",
                shown,
            )
            assertTrue(
                "The bottom navigation must not be shown during onboarding",
                composeRule.onAllNodes(isTab).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty(),
            )
            composeRule.assertHasDisplayedContent("Onboarding (MainActivity)")
            screenshot("AppFlow-onboarding-dark")
            SmokeOutput.report("  OK – onboarding shown; visible: ${composeRule.visibleTexts(15)}")
        }
    }

    @Test
    fun seededData_showsDiaryAndEveryTabRenders() {
        SmokeOutput.report("\n== AppFlow seeded data (MainActivity)")
        val data = TestData.seedRealistic()
        launchMainActivity().use {
            val diaryShown = composeRule.pumpUntil { tabSelected("Tagebuch") }
            composeRule.settle()
            assertTrue(
                "With onboardingDone the app must start on the diary tab; visible: ${composeRule.visibleTexts()}",
                diaryShown,
            )
            composeRule.assertHasDisplayedContent("Tagebuch (MainActivity)")
            screenshot("AppFlow-tab-Tagebuch-dark")

            for (tab in listOf("Lebensmittel", "Fortschritt", "Profil", "Tagebuch")) {
                composeRule.onNode(isTab and hasText(tab)).performClick()
                val selected = composeRule.pumpUntil { tabSelected(tab) }
                composeRule.settle()
                assertTrue("Tab '$tab' was not selected after clicking it", selected)
                composeRule.assertHasDisplayedContent("Tab $tab (MainActivity)")
                screenshot("AppFlow-tab-$tab-dark")
            }
            SmokeOutput.report(
                "  OK – started on the diary, all 4 tabs rendered (${data.diaryEntryCount} diary entries, " +
                    "${data.weightCount} weights seeded)",
            )
        }
    }

    /**
     * KalorienApp.onCreate schedules the health sync before any test code can initialize WorkManager
     * (Robolectric does not run androidx.startup). With the test WorkManager installed by [SmokeGuardRule]
     * the scheduler itself must work.
     */
    @Test
    fun healthWorkScheduler_worksWithInitializedWorkManager() {
        val app = TestData.app()
        HealthWorkScheduler.schedule(app)
        HealthWorkScheduler.schedule(app) // re-scheduling (app restart) must be fine as well
        val work = WorkManager.getInstance(app)
            .getWorkInfos(WorkQuery.fromStates(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING))
            .get()
        val note = "HealthWorkScheduler.schedule(): ${work.size} enqueued work request(s) " +
            work.joinToString(prefix = "[", postfix = "]") { it.tags.joinToString("/") }
        println("SMOKE: $note")
        SmokeOutput.report("\n== AppFlow health work scheduling\n  OK – $note")
    }

    // ---------------------------------------------------------------------------------------------

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun tabSelected(label: String): Boolean =
        composeRule.onAllNodes(isTab and hasText(label) and isSelected())
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun launchMainActivity(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { decorView = it.window.decorView }
        return scenario
    }

    private fun screenshot(name: String) {
        Screenshots.capture(composeRule, name) { decorView }
    }
}
