package de.kalorienkompass.ui.food

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.kalorienkompass.di.AppContainer
import de.kalorienkompass.di.appContainerFrom

/**
 * Like [de.kalorienkompass.di.appViewModelFactory], but also hands the ViewModel its
 * [SavedStateHandle] (so form input survives process death, e.g. while the camera app is open).
 */
internal inline fun <reified VM : ViewModel> foodViewModelFactory(
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        create(appContainerFrom(this), createSavedStateHandle())
    }
}

/**
 * Key of a one-shot value in the CustomFood back stack entry's `savedStateHandle`: the search text the
 * user typed before tapping "Eigenes Lebensmittel anlegen" (used as the initial name).
 */
internal const val CUSTOM_FOOD_PREFILL_NAME = "custom_food_prefill_name"
