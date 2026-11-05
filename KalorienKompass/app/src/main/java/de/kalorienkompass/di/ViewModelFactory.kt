package de.kalorienkompass.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.kalorienkompass.KalorienApp

/**
 * ViewModel factory with access to the [AppContainer].
 *
 * Usage in a screen:
 * `val vm: DiaryViewModel = viewModel(factory = appViewModelFactory { DiaryViewModel(it.diaryRepository) })`
 * Use `viewModel(key = "...")` when the same VM type is created with different args on one back stack.
 */
inline fun <reified VM : ViewModel> appViewModelFactory(
    crossinline create: (AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        create(appContainerFrom(this))
    }
}

/** Resolves the container from the ViewModel creation extras (falls back to the app instance). */
fun appContainerFrom(extras: CreationExtras): AppContainer {
    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as? KalorienApp
    return (app ?: KalorienApp.instance).container
}
