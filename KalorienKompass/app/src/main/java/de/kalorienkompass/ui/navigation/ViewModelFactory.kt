package de.kalorienkompass.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.kalorienkompass.di.AppContainer
import de.kalorienkompass.di.appContainerFrom

/**
 * Same as [de.kalorienkompass.di.appViewModelFactory] (available in both packages for convenience).
 * Usage: `val vm: DiaryViewModel = viewModel(factory = appViewModelFactory { DiaryViewModel(it.diaryRepository) })`
 */
inline fun <reified VM : ViewModel> appViewModelFactory(
    crossinline create: (AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        create(appContainerFrom(this))
    }
}
