package de.kalorienkompass

import android.app.Application
import android.util.Log
import de.kalorienkompass.di.AppContainer
import de.kalorienkompass.health.HealthWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KalorienApp : Application() {

    lateinit var container: AppContainer
        private set

    /** Process-wide scope for fire-and-forget work (seeding etc.). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)

        appScope.launch(Dispatchers.IO) {
            container.builtinFoodSeeder.seedIfNeeded()
        }

        try {
            HealthWorkScheduler.schedule(this)
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule health sync", e)
        }
    }

    companion object {
        private const val TAG = "KalorienApp"

        /** Set in [onCreate]; prefer `context.appContainer`. */
        lateinit var instance: KalorienApp
            private set
    }
}
