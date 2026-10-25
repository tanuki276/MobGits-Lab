package jp.mobgits.lab.feature.inbox

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import jp.mobgits.lab.MainActivity
import jp.mobgits.lab.core.AppGraph
import jp.mobgits.lab.core.network.*
import jp.mobgits.lab.feature.collaboration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object Notifications {
    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) { work.cancelUniqueWork("gitlab-inbox"); return }
        work.enqueueUniquePeriodicWork("gitlab-inbox", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<InboxWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun show(context: Context, account: String, target: Destination, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("gitlab-inbox", "GitLab Inbox", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).setData(Uri.parse(DeepLinks.create(account, target)))
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, NotificationCompat.Builder(context, "gitlab-inbox").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("GitLabに新しい通知があります").setContentText("タップしてMobGitsで確認")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build())
    }
}
class InboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val settings = graph.preferences.settings.first()
        if (!settings.enabled) return Result.success()
        val ledger = applicationContext.getSharedPreferences("notification_seen_v1", Context.MODE_PRIVATE)
        var failed = false
        for (account in graph.preferences.snapshot().accounts) {
            try {
                val value = graph.resources.read(account.id, "/api/v4/todos?state=pending&per_page=100", cached = false)
                val todos = JSONArray(value.json).objects()
                val seen = ledger.getStringSet(account.id, null)
                val now = todos.map { it.getLong("id").toString() }.toSet()
                if (seen != null) todos.filter { it.getLong("id").toString() !in seen }.forEach { todo ->
                    val target = todoDestination(todo)
                    if (target != null && when (target.kind) { "mr" -> settings.mr; "pipeline" -> settings.pipeline; else -> settings.todos }) {
                        Notifications.show(applicationContext, account.id, target, "${account.id}:${todo.getLong("id")}".hashCode())
                    }
                }
                ledger.edit().putStringSet(account.id, now).apply()
                if (settings.pipeline) {
                    val projects = JSONArray(graph.resources.read(account.id, "/api/v4/projects?membership=true&simple=true&order_by=last_activity_at&per_page=5", cached = false).json).objects()
                    for (project in projects) {
                        val projectId = project.getLong("id")
                        val key = "pipeline:" + account.id + ":" + projectId
                        val previous = ledger.getString(key, null)?.let { org.json.JSONObject(it) }
                        val current = org.json.JSONObject()
                        val pipelines = JSONArray(graph.resources.read(account.id, "/api/v4/projects/$projectId/pipelines?per_page=5", cached = false).json).objects()
                        pipelines.forEach { pipeline ->
                            val pipelineId = pipeline.getLong("id"); val status = pipeline.text("status")
                            current.put(pipelineId.toString(), status)
                            if (previous != null && previous.optString(pipelineId.toString()) != status && status in setOf("success", "failed", "canceled")) {
                                Notifications.show(applicationContext, account.id, Destination("pipeline", projectId, pipelineId), (account.id + ":pipeline:" + pipelineId).hashCode())
                            }
                        }
                        ledger.edit().putString(key, current.toString()).apply()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        return if (failed) Result.retry() else Result.success()
    }
}
/** Optional relay push. A Firebase-configured build and an authenticated GitLab webhook relay are required. */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val link = message.data["deep_link"]?.let(DeepLinks::parse) ?: return
        runBlocking(Dispatchers.IO) {
            val graph = AppGraph.get(applicationContext); val settings = graph.preferences.settings.first()
            if (settings.enabled && graph.preferences.snapshot().accounts.any { it.id == link.first } &&
                (link.second.kind != "mr" || settings.mr) && (link.second.kind != "pipeline" || settings.pipeline)) {
                Notifications.show(applicationContext, link.first, link.second, message.messageId.orEmpty().hashCode())
            }
        }
    }
}
