package io.github.ioannes78.voica.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.BuildConfig
import io.github.ioannes78.voica.ModelUpdateController
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.ui.ai.ProviderSettingsCard
import io.github.ioannes78.voica.ui.ai.ProviderSettingsViewModel
import io.github.ioannes78.voica.ui.model.ModelManagerCard
import io.github.ioannes78.voica.ui.theme.ThemeSettingsCard
import io.github.ioannes78.voica.ui.theme.ThemeSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsPage {
    HOME,
    AI,
    MODELS,
    THEME,
    ADVANCED,
    ABOUT,
}

@Composable
fun ProductSettingsScreen(
    padding: PaddingValues,
    modelManager: ModelManager,
    modelUpdateController: ModelUpdateController,
    providerSettingsViewModel: ProviderSettingsViewModel,
    themeSettingsStore: ThemeSettingsStore,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }

    if (page != SettingsPage.HOME) {
        BackHandler { page = SettingsPage.HOME }
    }

    when (page) {
        SettingsPage.HOME ->
            SettingsHome(
                padding = padding,
                onOpen = { page = it },
            )

        SettingsPage.AI ->
            SettingsSubpage(
                padding = padding,
                title = "AI 智能",
                onBack = { page = SettingsPage.HOME },
            ) {
                item {
                    ProviderSettingsCard(viewModel = providerSettingsViewModel)
                }
            }

        SettingsPage.MODELS ->
            SettingsSubpage(
                padding = padding,
                title = "本地模型",
                onBack = { page = SettingsPage.HOME },
            ) {
                item {
                    ModelManagerCard(
                        modelManager = modelManager,
                        modelUpdateController = modelUpdateController,
                    )
                }
            }

        SettingsPage.THEME ->
            SettingsSubpage(
                padding = padding,
                title = "主题与显示",
                onBack = { page = SettingsPage.HOME },
            ) {
                item {
                    ThemeSettingsCard(store = themeSettingsStore)
                }
            }

        SettingsPage.ADVANCED ->
            AdvancedSettingsPage(
                padding = padding,
                onBack = { page = SettingsPage.HOME },
            )

        SettingsPage.ABOUT ->
            SettingsSubpage(
                padding = padding,
                title = "关于 Voica",
                onBack = { page = SettingsPage.HOME },
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Voica",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "QS668 / CB08 录音、转写与内容理解工具",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
    }
}

@Composable
private fun SettingsHome(
    padding: PaddingValues,
    onOpen: (SettingsPage) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        title = "AI 智能",
                        subtitle = "Provider、模型与智能总结",
                        icon = { Icon(Icons.Outlined.SmartToy, contentDescription = null) },
                        onClick = { onOpen(SettingsPage.AI) },
                    )
                    HorizontalDivider()
                    SettingsRow(
                        title = "本地模型",
                        subtitle = "ASR、VAD、标点、说话人模型",
                        icon = { Icon(Icons.Outlined.Psychology, contentDescription = null) },
                        onClick = { onOpen(SettingsPage.MODELS) },
                    )
                    HorizontalDivider()
                    SettingsRow(
                        title = "主题与显示",
                        subtitle = "Voica 青绿 · 跟随系统",
                        icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                        onClick = { onOpen(SettingsPage.THEME) },
                    )
                    HorizontalDivider()
                    SettingsRow(
                        title = "高级设置",
                        subtitle = "运行库、语言与诊断",
                        icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                        onClick = { onOpen(SettingsPage.ADVANCED) },
                    )
                    HorizontalDivider()
                    SettingsRow(
                        title = "关于 Voica",
                        subtitle = BuildConfig.VERSION_NAME,
                        icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        onClick = { onOpen(SettingsPage.ABOUT) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                subtitle,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = icon,
        trailingContent = {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
            )
        },
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    )
    androidx.compose.foundation.clickable
}

@Composable
private fun SettingsSubpage(
    padding: PaddingValues,
    title: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            androidx.compose.foundation.layout.Row {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        content()
    }
}

@Composable
private fun AdvancedSettingsPage(
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var runtimeProbeRunning by remember { mutableStateOf(false) }
    var runtimeProbeResult by remember { mutableStateOf<String?>(null) }
    val runtimeAvailableText = stringResource(R.string.settings_sherpa_runtime_available)
    val runtimeUnavailableText = stringResource(R.string.settings_sherpa_runtime_unavailable)

    SettingsSubpage(
        padding = padding,
        title = "高级设置",
        onBack = onBack,
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_language)) },
                        supportingContent = {
                            Text(stringResource(R.string.settings_language_value))
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.Build, contentDescription = null)
                        },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_scope)) },
                        supportingContent = {
                            Text(stringResource(R.string.settings_scope_value))
                        },
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_local_ai_runtime),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            R.string.settings_sherpa_runtime_version,
                            SherpaRuntime.RUNTIME_VERSION,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = !runtimeProbeRunning,
                        onClick = {
                            runtimeProbeRunning = true
                            scope.launch {
                                val result = withContext(Dispatchers.Default) {
                                    SherpaRuntime.probeNativeLoad()
                                }
                                runtimeProbeResult =
                                    if (result.available) {
                                        runtimeAvailableText
                                    } else {
                                        runtimeUnavailableText +
                                            (result.error?.let { ": $it" } ?: "")
                                    }
                                runtimeProbeRunning = false
                            }
                        },
                    ) {
                        Text(
                            if (runtimeProbeRunning) {
                                stringResource(R.string.settings_sherpa_runtime_testing)
                            } else {
                                stringResource(R.string.settings_sherpa_runtime_test)
                            },
                        )
                    }
                    runtimeProbeResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
