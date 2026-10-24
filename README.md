# MobGits Lab

**MobGits Lab** は、日本語で使いやすいことを目指す非公式の GitLab Android クライアントです。

> This project is not affiliated with, endorsed by, or sponsored by GitLab Inc.

## MVPでできること

- GitLab.com / HTTPSのセルフホストGitLabに接続
- Personal Access Tokenでログイン
- トークンをAndroid Keystoreで端末内暗号化保存
- 参加中プロジェクトの一覧表示
- Open Issueの一覧表示
- Open Merge Requestの一覧表示
- Pipelineの一覧・状態表示
- 日本語UI

## 動作要件

- Android 8.0 (API 26) 以上
- 接続先はHTTPS必須
- Personal Access Tokenは `read_api` スコープ推奨

## 開発

Android Studioでこのリポジトリを開くか、Gradle 8.10.2 / JDK 17環境で以下を実行します。

```bash
gradle :app:assembleDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

GitHub Actionsでもpush / pull requestごとにDebug APKをビルドし、Artifactとして保存します。

## 今後の候補

- OAuth 2.0ログイン
- Issue / Merge Request詳細画面
- コメント投稿
- Pipelineのジョブ詳細・再実行
- GitLab通知
- Markdown表示
- ダークテーマ調整
- GitHub ReleasesへのAPK自動添付

## セキュリティ

Personal Access Tokenをソースコードへ埋め込まないでください。MobGits Labは入力されたトークンをAndroid KeystoreのAES/GCM鍵を使って暗号化し、端末内に保存します。

## ライセンス / 商標

GitLabはGitLab Inc.の商標です。本プロジェクトはGitLab Inc.とは無関係の非公式クライアントです。
