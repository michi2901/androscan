package com.androscan.app.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androscan.app.data.ScanEntry
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(viewModel: ScanViewModel) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val selectedColdRoom by viewModel.selectedColdRoom.collectAsStateWithLifecycle()
    val selectedArticle by viewModel.selectedArticle.collectAsStateWithLifecycle()
    val scanReady by viewModel.scanReady.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }

    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val canScan = selectedColdRoom != null && selectedArticle != null

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 2 && !cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        TabCaption(
                            title = "Kühlraum",
                            selection = selectedColdRoom
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        TabCaption(
                            title = "Artikel",
                            selection = selectedArticle
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        TabCaption(
                            title = "Scannen",
                            selection = when {
                                canScan -> "${selectedArticle}/${selectedColdRoom}"
                                else -> null
                            }
                        )
                    }
                )
            }

            when (selectedTab) {
                0 -> SelectionTab(
                    title = "Kühlraum wählen",
                    options = COLD_ROOMS,
                    selected = selectedColdRoom,
                    onSelect = viewModel::selectColdRoom,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                )
                1 -> SelectionTab(
                    title = "Artikel wählen",
                    options = ARTICLE_CODES,
                    selected = selectedArticle,
                    onSelect = viewModel::selectArticle,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                )
                else -> ScanTab(
                    canScan = canScan,
                    scanReady = scanReady,
                    selectedColdRoom = selectedColdRoom,
                    selectedArticle = selectedArticle,
                    entries = entries,
                    isSending = isSending,
                    cameraGranted = cameraPermission.status.isGranted,
                    showRationale = cameraPermission.status.shouldShowRationale,
                    onRequestCamera = { cameraPermission.launchPermissionRequest() },
                    onBarcodeDetected = viewModel::onBarcodeDetected,
                    onScanError = viewModel::onScanError,
                    onManualSubmit = viewModel::submitManualBarcode,
                    onSendMail = viewModel::sendMail,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun TabCaption(title: String, selection: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = selection ?: "—",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selection != null) FontWeight.Bold else FontWeight.Normal,
            color = if (selection != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun SelectionTab(
    title: String,
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        SelectionGrid(
            options = options,
            selected = selected,
            onSelect = onSelect
        )
    }
}

@Composable
private fun SelectionGrid(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { code ->
                    val isSelected = code == selected
                    Button(
                        onClick = { onSelect(code) },
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        contentPadding = PaddingValues(4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = if (isSelected) {
                                MaterialTheme.colorScheme.onSecondary
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    ) {
                        Text(
                            text = code,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ScanTab(
    canScan: Boolean,
    scanReady: Boolean,
    selectedColdRoom: String?,
    selectedArticle: String?,
    entries: List<ScanEntry>,
    isSending: Boolean,
    cameraGranted: Boolean,
    showRationale: Boolean,
    onRequestCamera: () -> Unit,
    onBarcodeDetected: (String) -> Unit,
    onScanError: (String) -> Unit,
    onManualSubmit: (String) -> Unit,
    onSendMail: () -> Unit,
    modifier: Modifier = Modifier
) {
    var manualInput by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier) {
        if (!canScan) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Bitte zuerst Kühlraum und Artikel wählen.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(10.dp))
        } else {
            Text(
                text = "Stapel: $selectedArticle · $selectedColdRoom",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
        }

        when {
            !canScan -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Scannen gesperrt",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            cameraGranted -> {
                CameraPreview(
                    enabled = scanReady && canScan,
                    onBarcodeDetected = onBarcodeDetected,
                    onScanError = onScanError,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            showRationale -> {
                PermissionCard(
                    text = "Kamerazugriff wird für das Barcode-Scannen benötigt.",
                    onRequest = onRequestCamera
                )
            }
            else -> {
                PermissionCard(
                    text = "Bitte Kameraberechtigung in den Systemeinstellungen erlauben.",
                    onRequest = onRequestCamera
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = manualInput,
            onValueChange = { manualInput = it.uppercase() },
            modifier = Modifier.fillMaxWidth(),
            enabled = canScan && scanReady,
            label = { Text("Ohrmarke manuell") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    val value = manualInput.trim()
                    if (value.isNotEmpty()) {
                        onManualSubmit(value)
                        manualInput = ""
                        focusManager.clearFocus()
                    }
                }
            )
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val value = manualInput.trim()
                if (value.isNotEmpty()) {
                    onManualSubmit(value)
                    manualInput = ""
                    focusManager.clearFocus()
                }
            },
            enabled = canScan && scanReady && manualInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Manuell erfassen")
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onSendMail,
            enabled = entries.isNotEmpty() && !isSending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Email, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (isSending) "Sende per SMTP…" else "Per SMTP senden")
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Erfasste Einträge (${entries.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(entries, key = { it.id }) { entry ->
                EntryRow(entry)
            }
        }
    }
}

@Composable
private fun PermissionCard(text: String, onRequest: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onRequest) {
                Text("Berechtigung erteilen")
            }
        }
    }
}

@Composable
private fun EntryRow(entry: ScanEntry) {
    val time = remember(entry.capturedAt) {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date(entry.capturedAt))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.articleCode,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = entry.coldRoom,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (entry.sentByMail) {
                        Text(
                            text = "Gesendet",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = entry.barcode,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = entry.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
