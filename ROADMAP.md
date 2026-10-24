# MobGits Lab Roadmap

MobGits Lab は、日本語で使いやすく、GitHub Mobile と並べても違和感のない品質を目指す非公式 GitLab Android クライアントです。

> This project is not affiliated with, endorsed by, or sponsored by GitLab Inc.

## Product bar

「動く」ではなく、日常的に安心して使えることを完成条件にします。

- 起動が速く、スクロールが滑らか
- 通信が遅い・失敗した場合でも画面が壊れない
- 戻る操作、Deep Link、画面復元が一貫している
- TalkBack、フォント拡大、ダークテーマに対応
- 認証情報を安全に扱う
- GitLab.com と Self-Managed を同じ体験で扱う
- 日本語を第一言語として自然な用語・文章にする
- 大規模プロジェクトでもページングとキャッシュで快適に動く

## v0.1 — Foundation

- GitLab.com / HTTPS Self-Managed
- Personal Access Token 認証
- Android Keystore 保存
- プロジェクト一覧
- Issue 一覧
- Merge Request 一覧
- Pipeline 一覧
- GitHub Actions APK build

## v0.2 — Mobile shell

- Navigation Compose
- Home
- Notifications / To-Do
- Projects
- Search
- Profile / Settings
- Material 3 dynamic color
- Pull to refresh
- Empty / Loading / Error state の統一
- Deep Link 基盤

## v0.3 — Project experience

- Project overview
- README 表示
- Repository tree
- File viewer
- Branch / tag 切り替え
- Commit 一覧・詳細
- Star / favorite
- Members / basic project metadata

## v0.4 — Issues

- Issue 詳細
- Markdown rendering
- コメント / discussion
- Issue 作成・編集・close/reopen
- Assignee
- Labels
- Milestone
- Due date
- 添付リンク

## v0.5 — Merge Requests

- MR 詳細
- Diff viewer
- Inline discussions
- コメント
- Approve / unapprove
- Merge
- Draft 状態
- Reviewer / assignee
- Conflict / pipeline / approval status
- changed files ナビゲーション

## v0.6 — CI/CD

- Pipeline 詳細
- Jobs
- Job logs
- Retry / cancel
- Artifacts metadata
- Environment / deployment status
- Failed pipeline の原因へ素早く移動

## v0.7 — Search & inbox

- Projects / Issues / MRs / Users 検索
- Repository code search
- GitLab To-Do を使った Inbox
- Mention / assignment / review request の整理
- Filter / unread / done
- Background sync
- Android notifications

## v0.8 — Accounts & auth

- OAuth 2.0
- 複数アカウント
- 複数 GitLab instance
- アカウント切り替え
- Session expiration recovery
- Biometric app lock (optional)

## v0.9 — Offline & polish

- Room cache
- Paging 3
- Offline read
- Optimistic UI where safe
- Saved scroll positions
- Process death restoration
- Tablet / foldable layout
- TalkBack audit
- Performance profiling
- Crash reporting strategy without collecting sensitive repository data

## v1.0 — Daily-driver quality

v1.0 は「主要機能が揃った時」ではなく、以下を満たした時に出します。

- Issue / MR / Repository / Pipeline を日常利用できる
- 通知から対象画面へ直接移動できる
- 大規模リポジトリでも実用的な速度
- 主要操作に自動テストがある
- 認証情報がログやクラッシュ情報に出ない
- 通信失敗からユーザー操作だけで復帰できる
- 日本語 UI の文言が統一されている
- リリース APK を GitHub Releases から安全に配布できる

## Architecture target

段階的に以下へ移行します。

- Kotlin
- Jetpack Compose + Material 3
- Navigation Compose
- MVVM / unidirectional state flow
- Coroutines + Flow
- Retrofit / OkHttp
- Kotlinx Serialization
- Room
- Paging 3
- DataStore
- Android Keystore
- WorkManager
- Coil
- Modularization when feature count warrants it

Suggested package direction:

```text
app/
core/
  network/
  database/
  model/
  designsystem/
  auth/
feature/
  home/
  inbox/
  projects/
  repository/
  issues/
  merge_requests/
  pipelines/
  search/
  profile/
```

## Quality gates

Every major feature should eventually have:

1. Loading / empty / success / error states
2. Retry behavior
3. Pagination where applicable
4. Accessibility labels
5. Unit tests for state/repository logic
6. Compose UI tests for critical flows
7. No secrets in logs
8. Self-Managed compatibility considered
9. Japanese copy review
10. Dark theme verification
