package de.kalorienkompass.ui.progress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import de.kalorienkompass.data.db.MeasurementEntity
import de.kalorienkompass.data.model.DataSource
import de.kalorienkompass.data.model.MeasurementType
import de.kalorienkompass.di.appViewModelFactory
import de.kalorienkompass.ui.components.AppCard
import de.kalorienkompass.ui.components.ConfirmDialog
import de.kalorienkompass.ui.components.EmptyState
import de.kalorienkompass.ui.components.LoadingBox
import de.kalorienkompass.ui.components.charts.LineChart
import de.kalorienkompass.util.dayLabel
import de.kalorienkompass.util.formatDate
import java.time.LocalDate

private sealed interface HistoryDialog {
    data object Add : HistoryDialog
    data class Edit(val measurement: MeasurementEntity) : HistoryDialog
}

private const val HEALTH_CONNECT_EDIT_HINT =
    "Dieser Wert wurde aus Health Connect übernommen und wird dort aktuell gehalten. " +
        "Ändern kannst du ihn in Honor Health bzw. Health Connect."

/** All values of one body measurement: chart (all data) + list grouped by month, newest first. */
@Composable
fun MeasurementHistoryScreen(navController: NavController, type: MeasurementType) {
    val viewModel: MeasurementHistoryViewModel = viewModel(
        key = "measurement_history_${type.name}",
        factory = appViewModelFactory { c ->
            MeasurementHistoryViewModel(type, c.measurementRepository, c.settingsRepository)
        },
    )
    MeasurementHistoryScreen(navController = navController, type = type, viewModel = viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeasurementHistoryScreen(
    navController: NavController,
    type: MeasurementType,
    viewModel: MeasurementHistoryViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<HistoryDialog?>(null) }
    var pendingDelete by remember { mutableStateOf<MeasurementEntity?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(type.label, fontWeight = FontWeight.Bold)
                        Text("Verlauf", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    scrolledContainerColor = colors.background,
                ),
            )
        },
        floatingActionButton = {
            if (state is HistoryUiState.Ready) {
                ExtendedFloatingActionButton(
                    onClick = { dialog = HistoryDialog.Add },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Eintrag") },
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val current = state) {
            HistoryUiState.Loading -> LoadingBox(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            is HistoryUiState.Error -> ErrorContent(
                message = current.message,
                onRetry = viewModel::retry,
                modifier = Modifier.padding(padding),
            )
            is HistoryUiState.Ready -> HistoryList(
                content = current.content,
                padding = padding,
                onEntryClick = { dialog = HistoryDialog.Edit(it) },
                onAdd = { dialog = HistoryDialog.Add },
            )
        }
    }

    val content = (state as? HistoryUiState.Ready)?.content
    when (val current = dialog) {
        null -> Unit
        HistoryDialog.Add -> MeasurementDialog(
            title = "${type.label} eintragen",
            initialType = type,
            typeSelectable = false,
            initialDate = LocalDate.now(),
            initialValue = content?.latest?.value,
            existingManualValue = { _, date -> content?.manualValues?.get(date) },
            onSave = { _, date, value -> viewModel.add(date, value) },
            onDismiss = { dialog = null },
        )
        is HistoryDialog.Edit -> key(current.measurement.id) {
            val entry = current.measurement
            MeasurementDialog(
                title = "Eintrag bearbeiten",
                initialType = type,
                typeSelectable = false,
                initialDate = entry.date,
                initialValue = entry.value,
                isEdit = true,
                readOnlyHint = if (entry.source == DataSource.HEALTH_CONNECT) HEALTH_CONNECT_EDIT_HINT else null,
                onSave = { _, date, value -> viewModel.update(entry, date, value) },
                onDelete = {
                    dialog = null
                    pendingDelete = entry
                },
                onDismiss = { dialog = null },
            )
        }
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = "Eintrag löschen?",
            text = buildString {
                append("${formatMeasurement(entry.value, type)} vom ${formatDate(entry.date)} wird gelöscht.")
                if (entry.source == DataSource.HEALTH_CONNECT) {
                    append(" Ist der Wert in Health Connect noch vorhanden, kann er beim nächsten Abgleich wieder erscheinen.")
                }
            },
            confirmLabel = "Löschen",
            onConfirm = { viewModel.delete(entry) },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
}

@Composable
internal fun HistoryList(
    content: HistoryContent,
    padding: PaddingValues,
    onEntryClick: (MeasurementEntity) -> Unit,
    onAdd: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") {
            HistorySummaryCard(content)
        }
        if (content.months.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = measurementIcon(content.type),
                    title = "Noch keine Einträge",
                    text = "Trage deinen ersten Wert ein – danach siehst du hier deinen Verlauf.",
                    actionLabel = "Eintrag hinzufügen",
                    onAction = onAdd,
                )
            }
        } else {
            items(content.months, key = { "month_${it.month}" }) { month ->
                MonthGroup(month = month, content = content, onEntryClick = onEntryClick)
            }
        }
    }
}

@Composable
private fun HistorySummaryCard(content: HistoryContent) {
    val colors = MaterialTheme.colorScheme
    val type = content.type
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            StatItem(
                label = "Aktuell",
                value = content.latest?.let { formatMeasurement(it.value, type) } ?: "–",
                modifier = Modifier.weight(1f),
            )
            val change = content.totalChange
            StatItem(
                label = "Veränderung",
                value = change?.let { "${formatSigned(it)} ${type.unit}" } ?: "–",
                modifier = Modifier.weight(1f),
                valueColor = if (change != null && type == MeasurementType.WEIGHT) {
                    trendColor(weightTrend(change, content.goalType))
                } else {
                    Color.Unspecified
                },
            )
            val goal = content.goal
            if (goal != null) {
                StatItem(label = "Ziel", value = formatMeasurement(goal, type), modifier = Modifier.weight(1f))
            }
            StatItem(label = "Einträge", value = content.count.toString(), modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        LineChart(
            points = content.chart.points,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            lineColor = if (type == MeasurementType.WEIGHT) colors.primary else colors.tertiary,
            goalValue = content.goal,
            valueFormatter = { formatMeasurement(it, type) },
            showDots = content.chart.points.size <= 40,
            tooltipDateFormatter = { date ->
                if (content.chart.granularity == Granularity.DAY) {
                    dayLabel(date, content.today)
                } else {
                    "Ø " + bucketTitle(date, content.chart.granularity, content.today)
                }
            },
            emptyText = "Noch keine Werte",
        )
        val first = content.first
        if (first != null) {
            Spacer(Modifier.height(8.dp))
            val averaged = when (content.chart.granularity) {
                Granularity.DAY -> ""
                Granularity.WEEK -> " · Wochendurchschnitte"
                Granularity.MONTH -> " · Monatsdurchschnitte"
            }
            Text(
                text = "Seit ${formatDate(first.date)}$averaged",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthGroup(month: HistoryMonth, content: HistoryContent, onEntryClick: (MeasurementEntity) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = monthTitle(month.month),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline),
        ) {
            Column {
                month.entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = colors.outline.copy(alpha = 0.6f),
                        )
                    }
                    HistoryRow(entry = entry, content = content, onClick = { onEntryClick(entry.measurement) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, content: HistoryContent, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val measurement = entry.measurement
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Eintrag bearbeiten", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = dayLabel(measurement.date, content.today),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            SourceBadge(measurement.source)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatMeasurement(measurement.value, content.type),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            val delta = entry.delta
            if (delta != null) {
                Text(
                    text = "${formatSigned(delta)} ${content.type.unit}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (content.type == MeasurementType.WEIGHT) {
                        trendColor(weightTrend(delta, content.goalType))
                    } else {
                        colors.onSurfaceVariant
                    },
                )
            }
        }
    }
}
