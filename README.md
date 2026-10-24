# MobGits Lab

開発方針: [CR1–CR3 チーム開発計画](docs/CR-PLAN.md)。新規feature PRはCR統合branchを対象にします。

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

- Issue/MR詳細のMarkdown・コメント・編集・Close/Reopen
- 通知配信基盤・Androidプッシュ・対象画面へのDeep Link
- branch/tag切替・commit履歴・検索・競合検知付きファイル編集
- 複数アカウント・複数GitLab・アカウント単位のRoomキャッシュとオフライン
- Paging・画像キャッシュ・Markdownレンダリング・生体認証ロック
- タブレット最適化・TalkBack実機検証・画面テスト

これらは未実装であり、現状はGitHub Mobileと同等の完成度ではありません。

## MRレビュー

MR一覧の項目をタップすると差分・コメント・概要を表示します。

- 全ページのファイル差分を取得し、追加・削除・文脈行を旧/新の行番号付きで表示
- 追加・削除・文脈行をタップし、GitLab Discussionとしてインラインコメントを投稿
- 既存コメントを差分の該当行とコメントタブに表示（古い差分のコメントはコメントタブで確認）
- 承認者・自分の承認状態・マージ状態を表示
- Approve・Mergeは確認後に実行し、レビューしたhead SHAをAPIに送信
- 差分取得の前後でもSHAを比較し、取得途中で変わった差分のレビューを防止
- エラー時は下書きを保持し、再取得して反映状態を確認してから再操作

操作にはGitLab上の権限と書き込み可能なAPIスコープが必要です。
Draft・CI待機・競合・承認不足などのマージ条件はGitLab側に従います。
パスワード再認証が必要な承認はこの画面では未対応です。
GitLabが省略する巨大差分・バイナリ差分は理由を表示し、行コメントを無効にします。
Markdown描画、スレッドへの返信・解決、複数行コメント、レビューの一括送信は未対応です。

検証: `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
単体テストは差分パーサー、コメント位置、APIのメソッド・SHA、差分更新の拒否、
ページング、承認情報が取得できない場合をカバーします。
