package de.kalorienkompass.ui.food

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.data.repo.DiaryRepository
import de.kalorienkompass.data.repo.FoodRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

internal sealed interface FoodDetailLoad {
    data object Loading : FoodDetailLoad
    data class Loaded(val food: FoodEntity) : FoodDetailLoad
    data object NotFound : FoodDetailLoad
}

internal class FoodDetailViewModel(
    private val foodId: Long,
    initialDate: LocalDate?,
    initialMeal: MealType?,
    private val foodRepository: FoodRepository,
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    val food: StateFlow<FoodDetailLoad> = foodRepository.observeFood(foodId)
        .map { if (it == null) FoodDetailLoad.NotFound else FoodDetailLoad.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoodDetailLoad.Loading)

    // Form state as Compose state (text field input must never lag behind).
    var amountText: String by mutableStateOf("")
        private set
    var unitKey: String by mutableStateOf(BASE_UNIT_KEY)
        private set
    var meal: MealType by mutableStateOf(initialMeal ?: MealType.forTimeOfDay())
        private set
    var date: LocalDate by mutableStateOf(initialDate ?: LocalDate.now())
        private set
    var saving: Boolean by mutableStateOf(false)
        private set

    /** true once the default amount was applied (or the user typed something). */
    private var amountInitialized = false

    init {
        viewModelScope.launch {
            val loaded = try {
                foodRepository.observeFood(foodId).filterNotNull().first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (!amountInitialized) {
                val (amount, key) = defaultAmount(loaded)
                amountText = amount
                unitKey = key
                amountInitialized = true
            }
        }
    }

    fun onAmountChange(text: String) {
        amountText = text
        amountInitialized = true
    }

    fun onUnitSelected(option: AmountUnitOption, currentGrams: Double?) {
        if (option.key == unitKey) return
        unitKey = option.key
        amountText = amountForUnitChange(currentGrams, option)
        amountInitialized = true
    }

    fun onMealSelected(value: MealType) {
        meal = value
    }

    fun onDateSelected(value: LocalDate) {
        date = value
    }

    fun toggleFavorite(food: FoodEntity) {
        viewModelScope.launch {
            runCatching { foodRepository.setFavorite(food.id, !food.isFavorite) }
        }
    }

    /** Adds [grams] of [food] to the diary; [onDone] gets false on failure. */
    fun addToDiary(food: FoodEntity, amount: Double, option: AmountUnitOption, onDone: (Boolean) -> Unit) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            val ok = try {
                diaryRepository.addFood(
                    date = date,
                    meal = meal,
                    food = food,
                    amount = amount,
                    unitLabel = option.diaryLabel,
                    grams = amount * option.gramsPerUnit,
                )
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            saving = false
            onDone(ok)
        }
    }
}
