# MobGits Lab

GitLab.com / Self-Managed GitLab向けの、日本語を中心とした非公式Androidクライアント。

> This project is not affiliated with, endorsed by, or sponsored by GitLab Inc.

## 機能

- OAuth 2.0 + PKCE、複数GitLab / account、暗号化session、refresh、process-death復帰
- Issueの検索・作成・編集・コメント・Close/Reopen
- MRの概要・差分・行番号・inline discussion・reply/resolve・Approve/Unapprove・Merge
- Pipeline / Job詳細、32KiB chunk log、Retry/Cancel
- To-Do Inbox、account付きDeep Link、Android同期通知
- account別Room cache、オフライン閲覧、Markdown、画像cache、テーマ、生体認証ロック

実装範囲・検証と制約は [IMPLEMENTATION.md](docs/IMPLEMENTATION.md)。開発計画は [CR-PLAN.md](docs/CR-PLAN.md)。
CR3の編集・Commit・branch/MR作成は今後の対象。

## ビルド

JDK 17 / Gradle 8.10.2 / Android SDK 35。公開OAuth applicationのClient IDをローカルのGradle propertyへ設定する。

```properties
MOBGITS_GITLAB_CLIENT_ID=YOUR_PUBLIC_OAUTH_CLIENT_ID
```

```sh
gradle :app:assembleDebug
gradle :app:testDebugUnitTest :app:lintDebug
gradle :app:connectedDebugAndroidTest
```

OAuth applicationはredirect URI `mobgits://oauth/callback`、scope `api`、Confidential無効（public client）。Client secretは使用しない。
Self-Managedは接続先ごとにapplicationを作成し、アプリの詳細・開発者設定へClient IDを登録する。
API URLはbase URLから内部生成する。[AUTH.md](docs/AUTH.md)を参照。

## 通知

標準ビルドはWorkManagerによる15分以上間隔の同期通知であり、リアルタイムpushではない。
リモートpushにはFirebase設定・端末登録・GitLab webhook relayが別途必要。[NOTIFICATIONS.md](docs/NOTIFICATIONS.md)を参照。

## 開発・検証

`feat/cr1-*` → `cr/1-foundation`、CR2は検証済み基盤から `cr/2-collaboration` へ継続する。
CIはUnit / lint / APK、phone API 30 / tablet API 34のcritical flowとforce-stop復帰を検証する。
参照先とコピー禁止方針は [reference-repositories.yaml](docs/reference-repositories.yaml)。参照コード・assetはコピーしていない。
実GitLabのOAuth登録・project権限、実機TalkBack、巨大MRの端末別profileはリリース前の確認対象。
アプリ自体の配布ライセンスは未決定。


## CR3-A / v0.9 Editing Preview

プロジェクト作成とRepository編集を追加。Project → Repositoryからファイルを編集し、専用ブランチへ複数ファイルをコミットしてMRを作成できます。

[リリース内容と制限](docs/RELEASE-0.9.md)。CR3-BのローカルGitエンジンは未搭載です。
