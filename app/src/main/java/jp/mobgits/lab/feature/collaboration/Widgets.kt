package jp.mobgits.lab.feature.collaboration

import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

@Composable fun Markdown(body: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    AndroidView(modifier = modifier.fillMaxWidth(), factory = { context -> TextView(context).apply {
        textSize = 16f; setTextIsSelectable(true)
    } }, update = { view ->
        view.setTextColor(android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()))
        Markwon.builder(view.context).usePlugin(TablePlugin.create(view.context)).build().setMarkdown(view, body)
    })
}
@Composable fun ConfirmAction(label: String, enabled: Boolean = true, message: String = "GitLab上に反映されます。", action: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { confirm = true }, enabled = enabled) { Text(label) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("$label しますか？") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { confirm = false; action() }, enabled = enabled) { Text(label) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("戻る") } })
}
@Composable fun EmptyMessage(message: String = "該当する項目はありません") { Text(message, Modifier.padding(24.dp)) }
