# MobGits Lab CR1–CR3 チーム開発計画

## ゴールと進め方

GitLab.com / Self-Managed GitLabの両方で、確認・レビュー・Issue管理・CI確認・軽量なコード修正まで完結できる、日本語ファーストのAndroidクライアントを作る。

| CR | テーマ | 完了時にできること | リリース目安 |
|---|---|---|---|
| CR1 | Foundation / Accounts / API | OAuth、複数GitLab・アカウント、設定、安定したAPI基盤 | v0.3 Foundation Preview |
| CR2 | Collaboration | Issue / MR / Diff / CI / Inbox・通知を実務利用 | v0.6 Collaboration Preview |
| CR3-A | API Editing | Repository閲覧、APIによる安全な編集・Commit・Branch・MR作成 | v0.9 Editing Preview |
| CR3-B | Local Git | ローカル編集、fetch / diff / commit / push、オフラインworkspace | v1.0候補 |

原則CR1 → CR2 → CR3-A → 必要条件を満たしてCR3-B。v1.0は機能数ではなく日常利用で壊れないことを条件にする。

## 実装状況

CR1基盤・画面移行はPR #4〜#8で統合済み。CR2の仕上げは `feat/cr2-hardening` で継続する。
現在の機能・外部依存・検証チェックリストは [IMPLEMENTATION.md](IMPLEMENTATION.md) を正とする。
以下の棚卸しは開始時点の記録として残す。

## 開始時点の現状と移行

2026-10-24時点の開始点は `0291ff65f9af93c5a999152789cc7bae6b9aec74`。
このコミットには既存のOAuth/PAT、プロジェクト一覧、MR差分・行コメント・Approve/Merge、Job一覧・ログ・Retry/Cancelがある。
これらは移行対象の実装であり、CR1またはCR2のDefinition of Doneを満たしたものとは扱わない。

既知の差分:

- PATが残り、OAuthの途中状態はメモリのみ。単一セッションでtokenをViewModel/AppStateが保持している。
- APIはHttpURLConnection直接呼び出し。Repository / UseCase / Room / DataStoreがない。
- Diffは全ファイルを取得し、Job logも全文取得する。CR2の遅延ロード・chunk要件は未達。
- Issue詳細、Discussion reply/resolve、通知Deep Link、オフライン、編集フローは未実装。
- Unit / lint / assembleDebugは通過済み。実GitLabの認証・書き込み、process death、TalkBack、端末UI検証は未完了。

既存PR #1 (`feat/android-mvp` → main)、#2 (`feat/product-foundation` → #1のbranch)、#3 (`feat/project-management` → #2のbranch)は移行前のstack。
#3のプロジェクト作成・編集は別作業のため、CR1の開始点には未統合。統合時に競合・権限・OAuth基盤への移行をレビューする。

## ブランチとPR

- CR1統合先: `cr/1-foundation`。上記の開始点から作成する。
- 新規feature PRは `feat/cr1-*` → `cr/1-foundation`。次のfeatureを未mergeのfeature branchから作らない。
- 依存PRが必要なら先行PRをCR統合先にmergeし、そのheadから次を作る。
- CR1完了時のみ `cr/1-foundation` → `main` のPRをReadyにする。
- CR2 / CR3の統合先は、それぞれ前CRが完了したmainから作る。
- 既存stackのclose/retargetはCR統合PRの差分を確認してから行う。既存featureを取りこぼさない。
- 1 PR = 1責務、目安200–800行。API / model / UI / migration / refactor / 大規模生成物を分離する。
- この文書とreference設定はDocs PR、認証modelはCore PR。モデル導入だけでOAuth移行完了としない。

## チーム

| Role | 主担当 |
|---|---|
| Android Lead | architecture / review / navigation / release |
| Core Engineer | network / auth / Room / GitLab API |
| Feature Engineer | Issue / MR / Repository / Editor |
| UI/QA Engineer | Design System / Compose / tests / accessibility |

2人の場合はAがCore / Auth / API / Data、BがUI / Features / Tests。
各タスクのownerとreviewerは実際の担当者決定後に設定する。

## CR1 — Foundation

### アーキテクチャ

UI / Compose → ViewModel → UseCase → Repository → Remote / Local。
RemoteはGitLab API → AuthInterceptor → SessionManager → SecureTokenStore。
LocalはRoom / DataStore。Gradle module分割は必須ではなく、まずpackage境界を作る。

```
core/model, core/network, core/auth, core/database, core/datastore,
core/designsystem, core/navigation
feature/auth, feature/home, feature/inbox, feature/projects,
feature/search, feature/profile, feature/settings
```

- `GitLabServer`: id、displayName、baseUrl、apiBaseUrl、oauthClientId、isGitLabCom。
- `GitLabAccount`: id、serverId、userId、username、displayName、avatarUrl、isActive。
- `OAuthSession`: accountId、accessToken、refreshToken、expiresAt。core/authの内部型とし、UI / ViewModelへ渡さない。
- account identityはserver + user IDを使う。usernameは変更可能かつserver間で重複するためkeyにしない。
- account切替はactiveを一度に更新し、通信・Room cacheのscopeも切り替える。別accountのデータを混ぜない。

### 認証

通常ログインをOAuth 2.0 Authorization Code + PKCEに統一し、PAT UI/処理を削除する。
access / refresh tokenをユーザーに入力・表示・コピーさせない。

起動時はactive accountを読み込み、有効期限内ならHome、期限切れならrefresh。
refresh失敗時は再認証へ進める。オフラインなど一時的な失敗でaccountやcacheを削除しない。
refreshはaccount単位で排他し、更新後のrefresh tokenを永続化する。

ログインはSystem Browser → callbackの完全一致確認 → state照合 → token exchange → `/user` → account保存 → Home。
PKCE verifierはアプリがtoken exchangeへ送り、サーバーがchallengeと照合する。
state / verifier / 対象server / clientId / redirectUri / 作成時刻を一時永続化し、process death後も復帰する。
途中状態にはTTLと単回消費を設け、token・verifierは暗号化し、認証失敗・取消・期限切れ時の扱いをテストする。

### Serverと設定

「APIを追加」ではなく「GitLabアカウントを追加」。通常入力は接続先のbase URLのみ。
API base URLは `baseUrl + /api/v4` として内部生成する。Self-Managedのサブパスを保持する。
Self-ManagedのOAuth applicationはserverごとの登録が必要。client IDの提供は管理者・配布設定または開発者設定で行い、secretをAPKへ埋め込まない。

| 設定区分 | 内容 |
|---|---|
| アカウント | server / @user、追加、切替 |
| GitLab | 接続先、OAuth状態、API状態、再認証 |
| 通知 | To-Do / MR / Pipeline |
| 表示 | Theme / Dynamic Color |
| セキュリティ | 生体認証 / ローカルデータ削除 |
| 開発者設定 | API endpoint / Debug logging / Reference repositories |
| アプリについて | Version / GitHub / License |

未対応の設定は有効な機能のように見せない。debug loggingでもtoken・認証code・verifier・repository本文を出力しない。

### タスク

状態は `未着手 / 実装中 / PR / 完了 / Blocked`。完了にはmergeと検証が必要。

| ID | タスク | 領域 | 依存 | 状態 |
|---|---|---|---|---|
| CR1-01 | Auth model整理 | Core | — | 統合済（#5） |
| CR1-02 | OAuth PKCE SessionManager | Auth | 01 | 実装・統合済（#6〜#8） |
| CR1-03 | SecureTokenStore | Auth | 01 | 実装・統合済（#6〜#8） |
| CR1-04 | OAuth process-death復帰 | Auth | 02,03 | 実装・統合済（#6〜#8） |
| CR1-05 | OkHttp / Retrofit導入 | Network | — | 実装・統合済（#6〜#8） |
| CR1-06 | AuthInterceptor | Network/Auth | 02,05 | 実装・統合済（#6〜#8） |
| CR1-07 | API error model統一 | Network | 05 | 実装・統合済（#6〜#8） |
| CR1-08 | Repository layer | Data | 05 | 実装・統合済（#6〜#8） |
| CR1-09 | Room | Data | — | 実装・統合済（#6〜#8） |
| CR1-10 | DataStore設定保存 | Data | — | 実装・統合済（#6〜#8） |
| CR1-11 | GitLabServer管理 | Settings | 10 | 実装・統合済（#6〜#8） |
| CR1-12 | Account管理 | Settings | 02,10 | 実装・統合済（#6〜#8） |
| CR1-13 | Account switcher | UI | 12 | 実装・統合済（#6〜#8） |
| CR1-14 | Settings画面 | UI | 10,11,12 | 実装・統合済（#6〜#8） |
| CR1-15 | PAT UI/処理削除 | Auth | 02 | 実装・統合済（#6〜#8） |
| CR1-16 | Reference repo設定 | Docs | — | 統合済（#4） |
| CR1-17 | Unit / UI tests | QA | 全体 | phone/tablet・force-stop検証済（CI 40105972389） |

### CR1 Definition of Done

| 項目 | 合格条件 |
|---|---|
| Login | PATなしでOAuthログイン |
| Refresh | token期限切れをユーザー操作なしで回復、並行401でも単回refresh |
| Process death | browser遷移中にprocessを停止してもcallbackから復帰 |
| Multi-account | 2 account以上を保存・切替、同じserverの別accountも分離 |
| Multi-server | GitLab.comとSelf-Managedの保持・切替 |
| Security | tokenがUI / ViewModel / logsに出ない、cacheはaccountで分離 |
| Network | 401 / 403 / 404 / 429 / 5xxを共通処理、Retry-After尊重 |
| Settings | server / account / OAuth / API状態を確認 |
| CI | lint / unit test / assembleDebug成功、critical flow UI tests |

## CR2 — Collaboration

### 機能と受け入れ条件

Issue一覧: Opened / Closed / Assigned to me / Created by me、pagination、refresh、search、filter、labels、assignee、milestone。
詳細: title、Markdown description、author、assignee、labels、milestone、due date、comments、activity。
操作: create / edit / comment / close / reopen / assignee / labels / milestone変更。

MRはOverview / Changes / Commits / Discussions / Pipelineで構成する。
Overviewにはtitle、description、author、reviewers、assignee、source / target、draft、merge status、conflict、pipeline、approvals。
操作はcomment / edit / approve / unapprove / merge / close / reopen / draft toggle / reviewer / assignee変更。
MergeはレビューしたSHAを指定し、権限・承認・CI・競合などGitLabの条件に従う。

`feature/diff`を独立した境界にする。changed files一覧、unified diff、旧/新行番号、addition/deletion、inline discussion、resolved state、巨大diff / binary表示、file navigationを実装。
巨大MRの全diffを一括取得せず、ファイル単位で遅延ロードする。GitLabの差分上限は省略状態として表示する。
Discussionはid / notes / resolvable / resolvedを持ち、通常・inline・reply・resolve / unresolveを扱う。

Pipelineはstatus / ref / commit / duration / created by / jobs。
Jobはstatus / stage / runner / duration / logs / artifacts。Retry / Cancelを確認付きで実行。
logはchunk / streaming相当で取得し、全文をCompose Textへ投入しない。

InboxはGitLab To-Doを中心にReview requested / Assigned / Mentioned / Issue / MR / Pipeline / Otherへ分類。
target type / idを解析して対象へ遷移する。Android notificationも同じDeep Linkを使う。
通知の実配信方法・server側要件は実装前に明示する。

| ID | タスク | 状態 |
|---|---|---|
| CR2-01 | Issue repository | 実装済。phone/tablet CI成功（#9） |
| CR2-02 | Issue list | 実装済。phone/tablet CI成功（#9） |
| CR2-03 | Issue detail | 実装済。phone/tablet CI成功（#9） |
| CR2-04 | Issue create/edit | 実装済。phone/tablet CI成功（#9） |
| CR2-05 | Issue comments | 実装済。phone/tablet CI成功（#9） |
| CR2-06 | MR repository | 実装済。phone/tablet CI成功（#9） |
| CR2-07 | MR overview | 実装済。phone/tablet CI成功（#9） |
| CR2-08 | Diff parser/model | 実装済。phone/tablet CI成功（#9） |
| CR2-09 | Diff viewer | 実装済。phone/tablet CI成功（#9） |
| CR2-10 | Inline discussions | 実装済。phone/tablet CI成功（#9） |
| CR2-11 | Approve/unapprove | 実装済。phone/tablet CI成功（#9） |
| CR2-12 | Merge flow | 実装済。phone/tablet CI成功（#9） |
| CR2-13 | Pipeline detail | 実装済。phone/tablet CI成功（#9） |
| CR2-14 | Job detail | 実装済。phone/tablet CI成功（#9） |
| CR2-15 | Job logs | 実装済。phone/tablet CI成功（#9） |
| CR2-16 | Retry/cancel | 実装済。phone/tablet CI成功（#9） |
| CR2-17 | Inbox分類 | 実装済。phone/tablet CI成功（#9） |
| CR2-18 | Deep Link | 実装済。phone/tablet CI成功（#9） |
| CR2-19 | Android notification | 定期同期実装。FCM配信基盤は外部設定が必要 |
| CR2-20 | Tests/performance | Unit/UIあり。実機profileはリリース判定項目 |

CR2 DoD:

- Issue作成 → コメント → 編集 → closeが可能。
- MR詳細 → diff → comment → approve、条件が揃えばmerge可能。
- 大規模MRでUI freezeしない。端末・データ規模・測定方法をテスト結果へ記録する。
- Pipeline → Job → logに到達、通知から対象へ直接遷移。
- 最近開いたIssue / MRをaccount別cacheからオフライン閲覧。
- 権限不足・conflict・pipeline failureを区別表示。
- critical flowにUnit / Compose UI tests。

## CR3 — Repository / Editing / Git

### CR3-A API Editing

Repositoryはtree / file / branch / tag / commit history / commit detail / blame。
Viewerはsyntax highlighting / line number / in-file search / copy / open raw / edit / history / blame。

File → Edit → 変更内容 → Commit options。
Commit optionsはmessage、target branch、create new branch、start MR。
標準フローは `main → mobgits/edit-xxxx → commit → create MR`。
main/master等への直接commitは明示的に選択した場合のみ。

GitLab Files / Commits APIでcreate / update / delete file、create commit、multiple file actions、create branch、create MR。
複数ファイルはCommits APIのactionsで扱う。編集元commitを保存し、楽観的競合検知と未保存変更保護を設ける。

### CR3-B Local Git

offline editing / large multi-file change / local diff / real history / fetch / push / branch operationsが必要になった時点で導入。
`core/git`にGitRepositoryインターフェースを隔離し、clone / fetch / checkout / diff / commit / pushを提供する。
UIを特定Gitライブラリへ直接依存させない。clone先・認証・容量上限・削除・ライセンス方針を導入前にレビューする。

| ID | タスク | 段階 | 状態 |
|---|---|---|---|
| CR3-01 | Branch/tag API | A | 未着手 |
| CR3-02 | Commit history | A | 未着手 |
| CR3-03 | Commit detail | A | 未着手 |
| CR3-04 | Blame | A | 未着手 |
| CR3-05 | Syntax highlighting | A | 未着手 |
| CR3-06 | File editor | A | 未着手 |
| CR3-07 | Create/update/delete file | A | 未着手 |
| CR3-08 | Commit form | A | 未着手 |
| CR3-09 | Create branch | A | 未着手 |
| CR3-10 | Multi-file commit | A | 未着手 |
| CR3-11 | Create MR after edit | A | 未着手 |
| CR3-12 | Unsaved change protection | A | 未着手 |
| CR3-13 | Conflict handling | A | 未着手 |
| CR3-14 | Local Git abstraction | B | 未着手 |
| CR3-15 | Clone/fetch | B | 未着手 |
| CR3-16 | Local diff | B | 未着手 |
| CR3-17 | Local commit | B | 未着手 |
| CR3-18 | Push | B | 未着手 |
| CR3-19 | Offline workspace | B | 未着手 |
| CR3-20 | Tests/performance/security | A/B | 未着手 |

## Definition of Ready

各Issueの開始前にUX、endpoint、loading/error/empty/success、必要権限、cache対象、想定エラー、acceptance criteriaを確定する。

## 共通Definition of Done

1. Loading / Empty / Success / Errorがあり、再試行できる。
2. Pagination対象を一括ロードしない。
3. tokenやrepository内容を不要にlogへ出さない。
4. TalkBackラベル、Dark theme、日本語文言を確認する。
5. process deathとGitLab.com / Self-Managed差分を考慮する。
6. Unit test、critical flowのCompose UI testを追加する。
7. CI成功。実機・実serverが必要な検証は実施環境と結果を記録する。

## Reference repositories

[reference-repositories.yaml](reference-repositories.yaml)で目的・license・pin・review日・利用方針を管理する。
参照実装とMobGits独自実装を区別する。pinとlicenseのレビュー前にコードをvendorしない。
Gitlingは常に `copy_code: false`。他のreferenceも現時点は比較・設計参照のみ。
