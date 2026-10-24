# OAuth設定

接続する各GitLabのApplications設定でpublic OAuth applicationを登録する。

- Redirect URI: `mobgits://oauth/callback`
- Scope: `api`
- Confidential: 無効
- Client secret: 使用しない

[GitLab OAuth provider](https://docs.gitlab.com/integration/oauth_provider/) / [PKCE API](https://docs.gitlab.com/api/oauth2/)

GitLab.comのClient IDはGradle property `MOBGITS_GITLAB_CLIENT_ID` で渡す。
Self-Managedは配布者が `app/src/main/assets/oauth-servers.json` に正規化base URLをキー、そのサーバーで登録したClient IDを値として設定し配布する。一般ユーザーにはClient ID入力欄を表示しない。GitLab.comのIDを他のGitLabへ転用しない。
base URLのサブパスとportを保持し、API URLは内部生成する。tokenの入力は不要。

## ログインと復帰

System Browser、PKCE S256、state、callback完全一致、重複パラメーター拒否、10分TTLを使用する。
途中状態・tokenはAndroid Keystore AES-GCMで暗号化保存する。browser遷移中のprocess death後もcallbackを処理する。
成功したcallbackの再利用を拒否し、token交換後の `/user` 取得は暗号化した途中結果から再開できる。
期限切れtokenはrefreshし、並行401ではrefresh tokenを重複使用しない。一時的な通信失敗でaccountを削除しない。
旧PAT / 単一OAuth保存形式は破棄するため、OAuthで再ログインする。

## 運用上の条件

- 信頼されたHTTPS証明書が必要。企業CAが必要な配布では、そのCAを指定したNetwork Security Configを用意する。
- 外部browserでGitLab側のSSO / MFA policyに従う。
- Approve時のpassword再入力は承認dialogで対応し、保存しない。SAML強制等によりGitLabがAPI承認を拒否する場合はpolicyに従う。
- 実GitLab.com / Self-Managedのapplication登録・権限確認は、TLS mock serverによるCIとは別途実施する。

## Redirect URIエラー

登録済みOAuth ApplicationのApplication IDと配布APKのIDが一致することを確認し、そのApplicationのRedirect URIを `mobgits://oauth/callback` に設定する（末尾スラッシュなし）。以前案内された `jp.mobgits.lab://oauth/callback` は誤り。Confidentialは無効。アプリは登録URIを変更できない。更新後は古いブラウザー画面を閉じてアプリからログインし直す。
