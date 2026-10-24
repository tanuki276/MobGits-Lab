package jp.mobgits.lab.core.network

import java.io.IOException

enum class FailureKind { AUTHENTICATION, FORBIDDEN, NOT_FOUND, CONFLICT, RATE_LIMIT, SERVER, NETWORK, INVALID }
class ApiFailure(val kind: FailureKind, val status: Int = 0, val retryAfterSeconds: Long? = null) : IOException(
    when (kind) {
        FailureKind.AUTHENTICATION -> "再認証が必要です"
        FailureKind.FORBIDDEN -> "この操作を行う権限がありません"
        FailureKind.NOT_FOUND -> "対象が見つかりません。削除または権限を確認してください"
        FailureKind.CONFLICT -> "更新が競合しています。最新の内容を読み直してください"
        FailureKind.RATE_LIMIT -> "アクセス上限です。${retryAfterSeconds ?: 60}秒以上待って再試行してください"
        FailureKind.SERVER -> "GitLabでエラーが発生しました。時間をおいて再試行してください"
        FailureKind.NETWORK -> "接続できません。通信状態を確認してください"
        FailureKind.INVALID -> "GitLabが操作を受け付けませんでした（HTTP $status）"
    }) {
    companion object {
        fun from(status: Int, retry: String? = null) = ApiFailure(when (status) {
            401 -> FailureKind.AUTHENTICATION; 403 -> FailureKind.FORBIDDEN; 404 -> FailureKind.NOT_FOUND
            409, 412, 422 -> FailureKind.CONFLICT; 429 -> FailureKind.RATE_LIMIT
            in 500..599 -> FailureKind.SERVER; else -> FailureKind.INVALID
        }, status, retry?.toLongOrNull()?.coerceAtLeast(1))
    }
}
