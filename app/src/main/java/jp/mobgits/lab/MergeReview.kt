package jp.mobgits.lab

/** A unified patch row. Only numbered rows are valid discussion positions. */
enum class DiffKind { HUNK, CONTEXT, ADDED, REMOVED, META }
data class DiffLine(val text: String, val oldLine: Int?, val newLine: Int?, val kind: DiffKind) {
    val commentable get() = oldLine != null || newLine != null
}

object UnifiedDiff {
    private val hunk = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")
    fun parse(patch: String): List<DiffLine> = buildList {
        var old = 0
        var new = 0
        var oldLeft = 0
        var newLeft = 0
        patch.lineSequence().forEach { raw ->
            val line = raw.removeSuffix("\r")
            val match = hunk.matchEntire(line)
            if (match != null) {
                old = match.groupValues[1].toInt()
                new = match.groupValues[3].toInt()
                oldLeft = match.groupValues[2].ifEmpty { "1" }.toInt()
                newLeft = match.groupValues[4].ifEmpty { "1" }.toInt()
                add(DiffLine(line, null, null, DiffKind.HUNK))
            } else when {
                line.startsWith("+") && newLeft > 0 -> {
                    add(DiffLine(line, null, new++, DiffKind.ADDED)); newLeft--
                }
                line.startsWith("-") && oldLeft > 0 -> {
                    add(DiffLine(line, old++, null, DiffKind.REMOVED)); oldLeft--
                }
                line.startsWith(" ") && oldLeft > 0 && newLeft > 0 -> {
                    add(DiffLine(line, old++, new++, DiffKind.CONTEXT)); oldLeft--; newLeft--
                }
                line.isNotEmpty() -> add(DiffLine(line, null, null, DiffKind.META))
            }
        }
    }
}

data class DiffRefs(val base: String, val start: String, val head: String) {
    val complete get() = base.isNotBlank() && start.isNotBlank() && head.isNotBlank()
}
data class ReviewDetail(
    val iid: Long, val title: String, val description: String, val source: String, val target: String,
    val state: String, val draft: Boolean, val refs: DiffRefs, val mergeStatus: String, val canMerge: Boolean
) {
    val canSubmit get() = state == "opened" && refs.complete
    val mergeable get() = canSubmit && !draft && canMerge && mergeStatus in setOf("mergeable", "can_be_merged")
}
data class ReviewDiff(
    val oldPath: String, val newPath: String, val patch: String,
    val newFile: Boolean, val deletedFile: Boolean, val renamed: Boolean,
    val collapsed: Boolean, val tooLarge: Boolean
) {
    val lines = UnifiedDiff.parse(patch)
    val unavailable get() = collapsed || tooLarge || patch.isBlank()
}
data class ReviewPosition(val refs: DiffRefs, val oldPath: String, val newPath: String, val oldLine: Int?, val newLine: Int?) {
    init {
        require(refs.complete && (oldLine != null || newLine != null))
        require(oldLine == null || oldLine > 0)
        require(newLine == null || newLine > 0)
    }
    fun fields(): Map<String, String> = buildMap {
        put("position[position_type]", "text")
        put("position[base_sha]", refs.base)
        put("position[start_sha]", refs.start)
        put("position[head_sha]", refs.head)
        put("position[old_path]", oldPath)
        put("position[new_path]", newPath)
        oldLine?.let { put("position[old_line]", it.toString()) }
        newLine?.let { put("position[new_line]", it.toString()) }
    }
}
data class ReviewNote(val id: Long, val author: String, val body: String, val position: ReviewPosition?)
data class ReviewDiscussion(val id: String, val notes: List<ReviewNote>)
data class ReviewApproval(val canApprove: Boolean, val hasApproved: Boolean, val approvers: List<String>)
data class ReviewSnapshot(val detail: ReviewDetail, val diffs: List<ReviewDiff>, val discussions: List<ReviewDiscussion>, val approval: ReviewApproval?, val approvalWarning: String?)
enum class ReviewAction(val label: String) { APPROVE("Approve"), MERGE("Merge") }
data class ReviewState(
    val iid: Long, val title: String, val snapshot: ReviewSnapshot? = null,
    val loading: Boolean = true, val submitting: Boolean = false,
    val stale: Boolean = false, val error: String? = null, val notice: String? = null,
    val commentPosition: ReviewPosition? = null, val draft: String = ""
)
