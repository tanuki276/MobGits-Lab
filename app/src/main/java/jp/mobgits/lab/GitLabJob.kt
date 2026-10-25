package jp.mobgits.lab

enum class JobAction(val path: String, val label: String) {
    RETRY("retry", "再実行"), CANCEL("cancel", "キャンセル")
}

data class GitLabJob(val id: Long, val name: String, val stage: String, val status: String) {
    fun allows(action: JobAction): Boolean = when (action) {
        JobAction.RETRY -> status in setOf("success", "failed", "canceled")
        JobAction.CANCEL -> status in setOf("created", "pending", "preparing", "running", "waiting_for_resource", "scheduled")
    }
}
