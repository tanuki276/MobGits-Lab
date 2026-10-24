# CR1 / CR2 実装・検証

## 構成

UI → AppViewModel → CollaborationUseCase → ResourceRepository → Retrofit / Room。
`core/auth` がOAuth / Keystore、`core/datastore` がserver/account metadataと設定を保持する。
ViewModel / UI / DataStore / Roomはaccess / refresh tokenを持たない。認証code・verifier・repository本文をdebug logへ出さない。

## CR1

- OAuth Authorization Code + PKCEのみ。PAT入力・旧認証処理を削除し、旧保存データは再認証へ移行。
- state / verifier / server / client / 作成時刻をbrowser起動前に暗号化保存。callback完全一致、10分TTL、state、単回消費。
- refreshの排他とローテーション保存。通信障害時にaccountを削除せず、認証失敗時は再認証。
- server＋GitLab user IDでidentityを分離。同じserverの複数accountを保持・切替。
- 切替前に表示resourceを消し、古い通信結果を破棄。HTTPS・サブパス・portを保持。
- 設定: account追加/切替/削除/再認証、server/OAuth/API状態、接続確認、Theme/Dynamic Color、生体認証、cache削除、通知種別。
- API URLは内部生成。Client IDとendpoint表示は詳細・開発者設定。アプリのライセンス未決定も明示。

## CR2

| 領域 | 実装 |
|---|---|
| Issue一覧 | Opened / Closed / All、担当・作成者、search、labels/assignee/milestone filter、20件ずつpaging |
| Issue詳細・操作 | Markdown、author/assignee/labels/milestone/due date、会話/activity、create/edit/comment/close/reopen、属性変更 |
| MR | 概要・差分・commits・会話・pipeline、branch/author/reviewer/assignee、draft/conflict/CI/approval状態 |
| MR操作 | edit/comment/approve/unapprove/merge/close/reopen/draft、reviewer/assignee変更、必要時password再認証 |
| Discussion | 通常・inline comment、reply、resolve/unresolve、差分行に既存note表示 |
| Diff | 1ファイルずつ取得、別dispatcherでparse、遅延行描画、旧/新行番号、追加/削除、binary/collapsed/too-large表示 |
| ファイル移動 | 前/次のファイル、読み込み済みファイルの一覧。全MRのdiffは自動取得しない |
| CI | Pipeline/Jobのstatus/ref/SHA/user/duration、jobs、stage/runner/artifacts metadata、retry/cancel |
| Log | Range付き32KiB chunk、Range非対応も限定stream読み込み、直近1MiBを表示、UTF-8境界とEOF/416処理 |
| Inbox | review/assigned/mention/Issue/MR/Pipeline/Other、完了、targetのproject-local IIDで遷移 |
| Offline | account/path別Room cache、30日・300件、更新した対象と一覧のみ無効化、cache表示中は書込不可 |
| Error | authentication/forbidden/not found/conflict/rate limit/server/networkの区別、429の待機期限を永続保存 |

Approve/Mergeは表示したSHAを送信する。確認dialog中にrevisionが変わればdialogを破棄する。
行コメントはbase/start/head SHAを指定し、古い差分への誤投稿を拒否する。
結果不明の書込は自動再送せず再読み込みを要求する。下書きはaccount・対象・thread/行ごとに保存する。

## 通知

標準はopt-inのWorkManager同期（15分以上、OSの省電力制約あり）。

- pending To-Doは各accountで最大100件。初回を基準にして以後の追加を通知。
- Pipelineは直近5参加project×最新5pipeline。初回以後のsuccess/failed/canceledへの変化を通知。
- account付きDeep Link。削除したaccountを別accountへ読み替えない。通知本文にrepository内容を入れない。
- FCM受信口はあるが、Firebase設定・端末登録・認可・webhook relayの配備が別途必要。標準ビルドをリアルタイムpush対応と表現しない。

[NOTIFICATIONS.md](NOTIFICATIONS.md)を参照。

## 自動検証

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
gradle :app:connectedDebugAndroidTest
```

Unit: identity/active制約/URL/redaction、diff hunkとコメント位置、SHA・offline拒否、API method/body/path、paging、401再送、cache分離・限定無効化、共通error、PKCE RFC vector、Deep Link/IID、UTF-8 log境界。

Phone API 30 / tablet API 34:

- Issue作成 → コメント → 編集 → Close。
- MR差分 → inline comment → Approve → Merge。
- 確認取消、revision変更時の確認破棄、offline操作無効。
- Keystore保存、PKCE途中状態、並行401の単回refresh。
- TLS mock serverのtoken交換 → `/user` → account保存、callback replay拒否。
- hostからprepare → `am force-stop` → 別instrumentationでverifyし、pending authのprocess death復帰を確認。
- light Issue / dark Diffの画面captureをCI artifactへ保存。

## リリース前の実環境確認

- [ ] 実GitLab.com / Self-Managed（サブパスを含む）のOAuth・refresh・再認証
- [ ] 実accountの書込権限、保護branch、approval rule、CI条件
- [ ] 実機の通知許可・省電力・生体認証・TalkBack・文字拡大
- [ ] 大規模MRとlogの端末別速度・メモリprofile
- [ ] FCMを使用する場合、配信基盤の端末登録・認可・解除

機能実装と実環境でのリリース判定は区別する。CR3の編集・Commit・branch/MR作成は含めない。

## CI実行記録

機能実装のUnit / lint / APK、phone / tablet、force-stop復帰は [CI 40105972389](https://github.com/tanuki276/MobGits-Lab/actions/runs/40105972389) で成功。以後の変更は各PRの最新CIを参照する。
