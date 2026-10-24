# Android通知 / Deep Link

設定でAndroid通知を許可し、To-Do（Issue・その他）/ MR / Pipelineを選ぶ。
標準はWorkManagerの15分以上間隔の同期であり、OSの省電力制約により遅延する。初回は基準を保存するだけで既存通知を大量配信しない。
Pipeline監視は直近5参加project×最新5pipelineに限定する。

## 任意のリモートpush

受信口は `feature/inbox/Notifications.kt` の `PushService`。運用者が次を用意する。

1. application IDに対応するFirebase Android appのリソース設定。
2. FCM registration tokenの取得、relayへの端末登録・更新・解除。
3. account/server/projectと端末の購読を認可して関連付ける登録方式。
4. GitLab webhookのsecret検証、重複排除、project/userの権限照合。
5. Firebase Admin SDK等からのdata-only送信。OAuth tokenをpayloadへ入れない。

標準ビルドにこの配信基盤・端末登録はない。受信Serviceだけではリモートpush配信は開始しない。
通知設定と本文非表示方針を適用できるよう、SDKが自動表示するnotification payloadは使わずdata-onlyにする。

```json
{"data":{"deep_link":"mobgits://open?account=ACCOUNT_ID&kind=mr&project=42&id=7"}}
```

kindはissue/mr/pipeline/job。Issue/MRはglobal IDではなくIID、Pipeline/JobはID。
accountは端末に保存したserver＋user IDのidentity。usernameだけで関連付けず、保存されていないaccountを別accountへ読み替えない。

```sh
adb shell am start -a android.intent.action.VIEW -d 'mobgits://open?account=ACCOUNT_ID&kind=issue&project=42&id=7' jp.mobgits.lab
```
