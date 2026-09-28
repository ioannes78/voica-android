package io.github.ioannes78.voica.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.protocol.Crc16Xmodem
import io.github.ioannes78.voica.protocol.FrameParser
import io.github.ioannes78.voica.protocol.ProtocolCodec

@Composable
fun VoicaApp() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("录") },
                    label = { Text(stringResource(R.string.tab_recordings)) },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("设") },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { padding ->
        if (selectedTab == 0) RecordingHome(padding) else SettingsScreen(padding)
    }
}

@Composable
private fun RecordingHome(padding: PaddingValues) {
    val diagnostics = remember {
        val crcOk = Crc16Xmodem.compute("123456789".encodeToByteArray()) == 0x31C3
        val frame = ProtocolCodec.buildCommand(sequence = 1, type = 0, command = 3)
        val parserOk = FrameParser("diagnostic").feed(frame).size == 1
        val importSize = ProtocolCodec.buildImportRequest(
            sequence = 0,
            filename = "a.opus",
            offset = 0,
        ).size
        Triple(crcOk, parserOk, importSize)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.version_label), style = MaterialTheme.typography.bodyMedium)

        InfoCard(
            title = stringResource(R.string.device_title),
            lines = listOf(stringResource(R.string.device_not_connected)),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.protocol_diagnostics),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.protocol_core_loaded))
                HorizontalDivider()
                DiagnosticRow(stringResource(R.string.diagnostic_crc), diagnostics.first)
                DiagnosticRow(stringResource(R.string.diagnostic_parser), diagnostics.second)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.diagnostic_import_frame),
                        modifier = Modifier.weight(1f),
                    )
                    Text("${diagnostics.third} bytes")
                }
                Text(
                    stringResource(R.string.protocol_version),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        InfoCard(
            title = stringResource(R.string.settings_language),
            lines = listOf(stringResource(R.string.settings_language_value)),
        )
        InfoCard(
            title = stringResource(R.string.settings_scope),
            lines = listOf(stringResource(R.string.settings_scope_value)),
        )
    }
}

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            lines.forEach { Text(it) }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, passed: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(stringResource(if (passed) R.string.status_pass else R.string.status_fail))
    }
}
