package jp.mobgits.lab.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import jp.mobgits.lab.*
import jp.mobgits.lab.feature.auth.AuthScreen

@Composable
fun SettingsScreen(vm: AppViewModel, open: (String) -> Unit, biometric: (Boolean) -> Unit, notifications: (Boolean) -> Unit) {
    val state = vm.state
    var add by rememberSaveable { mutableStateOf(false) }
    var developer by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    if (add) { Column { TextButton(onClick = { add = false }) { Text("設定へ戻る") }; Box(Modifier.weight(1f)) { AuthScreen(vm, open) } }; return }
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (action == "cache") "キャッシュを削除しますか？" else "このアカウントを削除しますか？") },
        confirmButton = { TextButton(onClick = { confirm = null; if (action == "cache") vm.clearCache() else vm.logout(action) }) { Text("削除") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("戻る") } }) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("アカウント", style = MaterialTheme.typography.titleLarge) }
        items(state.directory.accounts, key = { it.id }) { account ->
            val server = state.directory.servers.single { it.id == account.serverId }
            ListItem(headlineContent = { Text("${server.displayName} / @${account.username}") }, supportingContent = { Text(if (account.isActive) "使用中" else "保存済み") })
            Row { TextButton(onClick = { vm.switch(account.id) }, enabled = !account.isActive) { Text("切替") }
                TextButton(onClick = { vm.login(server.id, open) }) { Text("再認証") }
                TextButton(onClick = { confirm = account.id }) { Text("削除") } }
        }
        item { OutlinedButton(onClick = { add = true }) { Text("＋ GitLabアカウントを追加") } }
        item {
            Text("GitLab", style = MaterialTheme.typography.titleLarge)
            val server = state.directory.activeAccount?.let { a -> state.directory.servers.find { it.id == a.serverId } }
            Text(server?.baseUrl.orEmpty()); Text("OAuth: ${when(state.session) { is jp.mobgits.lab.core.auth.SessionStatus.Authenticated -> "認証済み"; is jp.mobgits.lab.core.auth.SessionStatus.ReauthenticationRequired -> "再認証が必要"; is jp.mobgits.lab.core.auth.SessionStatus.TemporarilyUnavailable -> "通信待ち"; else -> "未認証" }}"); Text("API: ${state.apiStatus}")
            TextButton(onClick = vm::checkApi, enabled = state.apiStatus != "確認中") { Text("接続を確認") }
            Text("通知", style = MaterialTheme.typography.titleLarge)
            SettingToggle("Android通知", state.settings.enabled, notifications)
            SettingToggle("To-Do（Issue・その他）", state.settings.todos) { vm.flag("todos", it) }
            SettingToggle("MR通知", state.settings.mr) { vm.flag("mr", it) }
            SettingToggle("Pipeline通知", state.settings.pipeline) { vm.flag("pipeline", it) }
            Text("表示", style = MaterialTheme.typography.titleLarge)
            Row { listOf("system" to "端末設定", "light" to "ライト", "dark" to "ダーク").forEach { (value, label) ->
                FilterChip(state.settings.theme == value, { vm.theme(value) }, label = { Text(label) })
            } }
            SettingToggle("Dynamic Color", state.settings.dynamic) { vm.flag("dynamic", it) }
            Text("セキュリティ", style = MaterialTheme.typography.titleLarge)
            SettingToggle("生体認証ロック", state.settings.biometric, biometric)
            TextButton(onClick = { confirm = "cache" }) { Text("ローカルキャッシュを削除") }
            TextButton(onClick = { developer = !developer }) { Text("開発者設定") }
            if (developer) {
                Text("API endpoint: ${server?.apiBaseUrl.orEmpty()}")
                Text("Debug logging: 無効（認証情報・repository本文を記録しません）")
                TextButton(onClick = { open("https://github.com/tanuki276/MobGits-Lab/blob/cr/1-foundation/docs/reference-repositories.yaml") }) { Text("Reference repositories") }
            }
            Text("MobGits Lab ${BuildConfig.VERSION_NAME}")
            Text("アプリのライセンス: 未設定（リリース前に確定）")
            Text("GitLab Inc.とは関係のない非公式クライアントです。")
            TextButton(onClick = { open("https://github.com/tanuki276/MobGits-Lab") }) { Text("GitHub") }
        }
    }
}
@Composable private fun SettingToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(checked, change, modifier = Modifier.semantics { contentDescription = label }) })
}
