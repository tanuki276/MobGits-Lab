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

## CI/CD詳細（追加実装）

プロジェクトのCI/CDタブからPipelineを選択するとJob一覧を表示します。
Jobを選択するとログを閲覧・選択コピーでき、更新ボタンで再取得できます。
完了済みJobの再実行、実行中・待機中Jobのキャンセルは確認ダイアログ後に実行します。
Retryで発行される新しいJob IDに追従します。権限不足などのAPIエラーは画面に表示します。
Job一覧は100件ずつ取得し、全ページを読み込みます。ログは全文を取得し、行ごとに遅延描画します。
非常に大きいログの取得量制限、ANSI装飾、ライブ追尾、子Pipelineは今後の対応です。

### 次の実装対象

- MR差分・行番号・インラインコメント・Approve・SHA検証付きMerge
- Issue/MR詳細のMarkdown・コメント・編集・Close/Reopen
- 通知配信基盤・Androidプッシュ・対象画面へのDeep Link
- branch/tag切替・commit履歴・検索・競合検知付きファイル編集
- 複数アカウント・複数GitLab・アカウント単位のRoomキャッシュとオフライン
- Paging・画像キャッシュ・Markdownレンダリング・生体認証ロック
- タブレット最適化・TalkBack実機検証・画面テスト

これらは未実装であり、現状はGitHub Mobileと同等の完成度ではありません。
