# MobGits Lab v0.9.0-preview — CR3-A Editing Preview

GitLab APIを利用した軽量コード編集のプレビューリリースです。

## 追加機能

- プロジェクト作成：名前・パス・説明・名前空間・公開範囲、README初期化
- Repository：ディレクトリ閲覧、branch/tagの検索と切替、ページ単位の履歴、コミット詳細
- UTF-8ファイル：行番号、基本的なキーワード色分け、ファイル内検索、コピー、50行単位のBlame
- 新規作成・編集・削除を最大20ファイルの1コミットにまとめる
- 読み込んだSHAを基点に `mobgits/edit-*` ブランチを作成し、MRを作成
- アカウント・プロジェクト別の暗号化下書きと作業復元
- コミット応答が途切れた場合は専用ブランチと操作IDを確認。MR応答が途切れた場合は既存MRを確認

## 利用手順

Projects → プロジェクトを作成、またはプロジェクト → Repository → ファイル → コードを編集 → 変更に追加 → コミットメッセージ → コミットを確認 → Merge Requestを作成。

## 配布と制限

このリリースのAPKはCIのdebug署名によるPreviewです。ストア向け正式署名ではありません。既存APKの署名が異なる場合は上書きインストールできません。アンインストールすると端末の下書き・アカウント情報が消えるため、先に作業を完了してください。

- GitLab.comのOAuth Client IDは内蔵。Self-Managedは配布者による接続設定が必要
- 編集はUTF-8テキストのみ、1ファイル256KB・1コミット20ファイル/合計1MBまで
- 元ブランチへの直接コミットは提供せず、専用ブランチとMRを使用
- namespace・保護ブランチ・公開範囲はGitLab側の権限に従う
- 自動テストはモックAPIを使用。実GitLabの全権限構成・実機TalkBackの確認は含まない
- ローカルclone/fetch/push・オフラインGitエンジンはCR3-Bの対象で、このリリースには含まれない
- 下書き編集は保存後に復元可能。新しいファイル取得・commit/MR作成にはオンライン接続が必要

GitLab API仕様：
https://docs.gitlab.com/api/commits/
https://docs.gitlab.com/api/repository_files/
https://docs.gitlab.com/api/projects/
