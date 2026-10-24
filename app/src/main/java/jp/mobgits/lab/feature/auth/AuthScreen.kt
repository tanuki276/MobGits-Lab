package jp.mobgits.lab.feature.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import jp.mobgits.lab.*
import jp.mobgits.lab.R

@Composable
fun AuthScreen(vm: AppViewModel, open: (String) -> Unit) {
    val state = vm.state
    var url by rememberSaveable { mutableStateOf("https://gitlab.com") }
    var client by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(48.dp))
            Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(24.dp))
            Text("開発を、手のひらに。", style = MaterialTheme.typography.headlineLarge)
            Text("MobGits Lab", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("レビュー、Issue、CIの確認をひとつに。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(state.directory.accounts, key = { it.id }) { account ->
            OutlinedButton(onClick = { vm.switch(account.id) }) { Text("${account.displayName} / @${account.username} に切替") }
        }
        items(state.directory.servers, key = { it.id }) { server ->
            Button(onClick = { vm.login(server.id, open) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = server.oauthConfigured) { Text("${server.displayName}でログイン") }
            if (!server.oauthConfigured) Text("この接続先のOAuth登録が必要です。管理者からClient IDを取得し、接続先設定へ登録してください。")
        }
        item {
            TextButton(onClick = { advanced = !advanced }) { Text("会社・自分のGitLabに接続") }
            if (advanced) {
            Text("接続先を追加", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(url, { url = it }, label = { Text("GitLabのURL") }, singleLine = true)
            OutlinedTextField(name, { name = it }, label = { Text("接続先の表示名（任意）") })

            if (advanced) {
                OutlinedTextField(client, { client = it }, label = { Text("OAuth Client ID") }, singleLine = true)
                Text("管理者が用意したClient IDを入力してください。リダイレクトURI: mobgits://oauth/callback")
            }
            OutlinedButton(onClick = { vm.addServer(url, client, name) }, enabled = url.isNotBlank()) { Text("接続先を登録") }
            }
        }
    }
}
@Composable fun LockScreen(error: String?, unlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
        Text("MobGitsはロックされています", style = MaterialTheme.typography.headlineSmall)
        error?.let { Text(it) }; Button(onClick = unlock) { Text("ロック解除") }
    }
}
