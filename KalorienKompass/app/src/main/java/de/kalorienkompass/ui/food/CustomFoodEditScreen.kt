package de.kalorienkompass.ui.food

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import de.kalorienkompass.data.model.MealType
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.ConfirmDialog
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.components.isAcceptableDecimalInput
import de.kalorienkompass.ui.navigation.Routes
import de.kalorienkompass.util.formatKcal
import de.kalorienkompass.util.parseDecimal
import java.time.LocalDate

/**
 * Create ([foodId] == null) or edit an own food. Opened with [barcode] for products that are unknown
 * locally and at Open Food Facts. After saving a NEW food with date/meal, in pick mode or with a
 * scanned barcode, the food detail screen of the saved food replaces this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFoodEditScreen(
    navController: NavController,
    foodId: Long?,
    barcode: String?,
    date: LocalDate?,
    meal: MealType?,
    pickMode: Boolean,
) {
    val vm: CustomFoodEditViewModel = viewModel(
        key = "custom_food_${foodId}_$barcode",
        factory = foodViewModelFactory { container, handle ->
            CustomFoodEditViewModel(foodId, barcode, container.foodRepository, handle)
        },
    )
    val state = vm.state
    val validation = vm.validation
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    // Search text handed over by FoodSearch ("Eigenes Lebensmittel anlegen" → name).
    val owner = LocalViewModelStoreOwner.current
    LaunchedEffect(Unit) {
        val handle = (owner as? NavBackStackEntry)?.savedStateHandle ?: return@LaunchedEffect
        handle.remove<String>(CUSTOM_FOOD_PREFILL_NAME)?.let { vm.applySearchName(it) }
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        vm.messageShown()
    }

    BackHandler(enabled = state.isDirty && !state.saving) { confirmDiscard = true }

    val onSaved: (Long) -> Unit = { id ->
        val continueToDetail = foodId == null && (pickMode || (date != null && meal != null) || barcode != null)
        if (continueToDetail) {
            val scannerBelow = barcode != null &&
                runCatching { navController.getBackStackEntry(Routes.BARCODE) }.isSuccess
            navController.navigate(Routes.foodDetail(id, date, meal, pickMode)) {
                popUpTo(if (scannerBelow) Routes.BARCODE else Routes.CUSTOM_FOOD) { inclusive = true }
            }
        } else {
            navController.popBackStack()
        }
    }
    val onDeleted: () -> Unit = {
        val previous = navController.previousBackStackEntry?.destination?.route
        if (previous == Routes.FOOD_DETAIL) {
            navController.popBackStack(Routes.FOOD_DETAIL, inclusive = true)
        } else {
            navController.popBackStack()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (foodId != null) "Lebensmittel bearbeiten" else "Eigenes Lebensmittel") },
                navigationIcon = {
                    IconButton(onClick = { if (state.isDirty) confirmDiscard = true else navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },

                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!state.loading && !state.notFound) {
                BottomActionBar(
                    label = "Speichern",
                    enabled = !state.saving,
                    loading = state.saving,
                    icon = Icons.Filled.Check,
                    onClick = { vm.save(onSaved) },
                )
            }
        },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
        when {
            state.loading -> LoadingBox(modifier = contentModifier)
            state.notFound -> Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "Lebensmittel nicht gefunden",
                    text = "Es wurde vielleicht bereits gelöscht.",
                    actionLabel = "Zurück",
                    onAction = { navController.popBackStack() },
                )
            }
            else -> Column(
                modifier = contentModifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---------------------------------------------------------------------------------
                // Top section (spec §13): explanation, notices – and the place for the
                // "Nährwerttabelle fotografieren" / "Im Web suchen (KI)" buttons.
                // ---------------------------------------------------------------------------------
                CustomFoodTopSection(
                    state = state,
                    onDismissNotice = vm::dismissAssistNotice,
                    // Integration point: pass `actions = { ... }` with the AI buttons (e.g. a Row of two
                    // OutlinedButtons). They call vm.prefill(...) / vm.showAssistNotice(...); saving stays
                    // a deliberate tap on "Speichern".
                    actions = null,
                )

                GeneralCard(state = state, validation = validation, vm = vm)
                NutritionCard(state = state, validation = validation, vm = vm)
                PortionsCard(state = state, validation = validation, vm = vm)

                if (state.isEditing) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = !state.deleting,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Lebensmittel löschen")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Lebensmittel löschen?",
            text = "„${state.form.name.ifBlank { "Dieses Lebensmittel" }}“ wird gelöscht. Bereits eingetragene Mahlzeiten bleiben erhalten.",
            confirmLabel = "Löschen",
            destructive = true,
            onConfirm = { vm.delete(onDeleted) },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Änderungen verwerfen?",
            text = "Deine Eingaben wurden noch nicht gespeichert.",
            confirmLabel = "Verwerfen",
            destructive = true,
            onConfirm = { navController.popBackStack() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/**
 * Clearly separated top section: what to enter (or that the barcode is unknown), a notice after
 * values were filled in automatically (disclaimer + sources) and an [actions] slot for assist buttons.
 */
@Composable
private fun CustomFoodTopSection(
    state: CustomFoodUiState,
    onDismissNotice: () -> Unit,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val barcode = state.form.barcode.trim()
    val (icon, title, text) = when {
        state.isEditing -> Triple(
            Icons.Filled.EditNote,
            "Werte anpassen",
            "Änderungen gelten für künftige Einträge – bereits eingetragene Mahlzeiten bleiben unverändert.",
        )
        barcode.isNotEmpty() -> Triple(
            Icons.Filled.Inventory2,
            "Neues Produkt",
            "Zum Barcode $barcode gibt es noch keinen Eintrag – weder bei dir noch bei Open Food Facts. " +
                "Übertrage die Angaben aus der Nährwerttabelle der Verpackung.",
        )
        else -> Triple(
            Icons.Filled.Inventory2,
            "Nährwerte von der Verpackung",
            "Trage die Werte aus der Nährwerttabelle ein (pro 100 g bzw. 100 ml). Pflichtfelder sind mit * markiert.",
        )
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val notice = state.assistNotice
        if (notice != null) {
            Spacer(Modifier.height(12.dp))
            AssistNotice(notice = notice, sources = state.assistSources, onDismiss = onDismissNotice)
        }

        if (actions != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    }
}

@Composable
private fun AssistNotice(notice: String, sources: List<String>, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f))
            .padding(start = 12.dp, top = 8.dp, bottom = 10.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 4.dp),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Hinweis ausblenden", modifier = Modifier.size(16.dp))
            }
        }
        if (sources.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Quellen",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp),
            )
            sources.take(5).forEach { url ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { runCatching { uriHandler.openUri(url) } }
                        .padding(start = 28.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
                ) {
                    Text(
                        text = url.removePrefix("https://").removePrefix("http://").removePrefix("www."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneralCard(state: CustomFoodUiState, validation: FormValidation, vm: CustomFoodEditViewModel) {
    val form = state.form
    val errors = if (state.showErrors) validation.fieldErrors else emptyMap()
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("Allgemein", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        FormTextField(
            value = form.name,
            onValueChange = { v -> vm.update { it.copy(name = v.take(120)) } },
            label = "Name *",
            placeholder = "z. B. Vollkornbrötchen",
            error = errors[FormField.NAME],
            capitalization = KeyboardCapitalization.Sentences,
        )
        Spacer(Modifier.height(8.dp))
        FormTextField(
            value = form.brand,
            onValueChange = { v -> vm.update { it.copy(brand = v.take(120)) } },
            label = "Marke (optional)",
            placeholder = "z. B. Bäckerei Müller",
            error = errors[FormField.BRAND],
            capitalization = KeyboardCapitalization.Words,
        )
        Spacer(Modifier.height(8.dp))
        FormTextField(
            value = form.barcode,
            onValueChange = { v -> if (v.length <= 14 && v.all { it in '0'..'9' }) vm.update { it.copy(barcode = v) } },
            label = "Barcode (optional)",
            placeholder = "EAN, z. B. 4001234567890",
            error = state.barcodeConflict ?: errors[FormField.BARCODE],
            keyboardType = KeyboardType.Number,
        )
        Spacer(Modifier.height(12.dp))
        Text("Einheit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        val units = listOf("g" to "Gramm (fest)", "ml" to "Milliliter (flüssig)")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            units.forEachIndexed { index, (unit, label) ->
                SegmentedButton(
                    selected = form.unit == unit,
                    onClick = { vm.update { it.copy(unit = unit) } },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = units.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun NutritionCard(state: CustomFoodUiState, validation: FormValidation, vm: CustomFoodEditViewModel) {
    val form = state.form
    val unit = if (form.unit == "ml") "ml" else "g"
    val errors = if (state.showErrors) validation.fieldErrors else emptyMap()

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("Nährwerte pro 100 $unit", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "In der Reihenfolge der Nährwerttabelle. Kohlenhydrate ohne Ballaststoffe (EU-Angabe).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        NumberField(
            value = form.kcal,
            onValueChange = { v -> vm.update { it.copy(kcal = v) } },
            label = "Energie *",
            suffix = "kcal",
            error = errors[FormField.KCAL],
            modifier = Modifier.fillMaxWidth(),
        )
        val fat = parseDecimal(form.fat)
        val carbs = parseDecimal(form.carbs)
        val protein = parseDecimal(form.protein)
        if (form.kcal.isBlank() && fat != null && carbs != null && protein != null) {
            val estimate = estimatedKcal(fat, carbs, protein, parseDecimal(form.fiber) ?: 0.0)
            TextButton(onClick = { vm.applyEstimatedKcal(estimate) }) {
                Text("Aus den Nährwerten berechnen: ≈ ${formatKcal(estimate)} kcal")
            }
        } else {
            Text(
                text = "Steht nur kJ auf der Packung? kcal = kJ ÷ 4,184",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                value = form.fat,
                onValueChange = { v -> vm.update { it.copy(fat = v) } },
                label = "Fett *",
                suffix = "g",
                error = errors[FormField.FAT],
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = form.carbs,
                onValueChange = { v -> vm.update { it.copy(carbs = v) } },
                label = "Kohlenhydrate *",
                suffix = "g",
                error = errors[FormField.CARBS],
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                value = form.sugar,
                onValueChange = { v -> vm.update { it.copy(sugar = v) } },
                label = "davon Zucker",
                suffix = "g",
                error = errors[FormField.SUGAR],
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = form.fiber,
                onValueChange = { v -> vm.update { it.copy(fiber = v) } },
                label = "Ballaststoffe",
                suffix = "g",
                error = errors[FormField.FIBER],
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                value = form.protein,
                onValueChange = { v -> vm.update { it.copy(protein = v) } },
                label = "Eiweiß *",
                suffix = "g",
                error = errors[FormField.PROTEIN],
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = form.salt,
                onValueChange = { v -> vm.update { it.copy(salt = v) } },
                label = "Salz",
                suffix = "g",
                error = errors[FormField.SALT],
                imeAction = ImeAction.Done,
                modifier = Modifier.weight(1f),
            )
        }

        val general = if (state.showErrors) validation.generalError else null
        val warning = validation.warning
        if (general != null || warning != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        (if (general != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                            .copy(alpha = 0.12f),
                    )
                    .padding(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = if (general != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = general ?: warning.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PortionsCard(state: CustomFoodUiState, validation: FormValidation, vm: CustomFoodEditViewModel) {
    val form = state.form
    val unit = if (form.unit == "ml") "ml" else "g"
    val portionErrors = if (state.showErrors) validation.portionErrors else emptyMap()

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("Portionen (optional)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Z. B. „1 Scheibe“ = 40 $unit – dann kannst du später einfach Portionen statt $unit eintragen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        form.portions.forEachIndexed { index, portion ->
            Row(verticalAlignment = Alignment.Top) {
                FormTextField(
                    value = portion.name,
                    onValueChange = { v -> vm.updatePortion(index) { it.copy(name = v.take(60)) } },
                    label = "Bezeichnung",
                    placeholder = "z. B. 1 Scheibe",
                    error = portionErrors[index],
                    capitalization = KeyboardCapitalization.Sentences,
                    modifier = Modifier.weight(1.5f),
                )
                Spacer(Modifier.width(8.dp))
                NumberField(
                    value = portion.grams,
                    onValueChange = { v -> vm.updatePortion(index) { it.copy(grams = v) } },
                    label = "Menge",
                    suffix = unit,
                    error = null,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { vm.removePortion(index) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Portion entfernen")
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (form.portions.size < MAX_PORTIONS) {
            TextButton(onClick = vm::addPortion) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Weitere Portion")
            }
        }
    }
}

private const val MAX_PORTIONS = 6

@Composable
private fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
        ),
        modifier = modifier,
    )
}

/** Decimal input ("12,5") with error text; accepts digits and one "," or ".". */
@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    error: String?,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> if (isAcceptableDecimalInput(input)) onValueChange(input) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        suffix = { Text(suffix) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = modifier,
    )
}
