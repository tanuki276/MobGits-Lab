# OAuth設定

接続する各GitLabのApplications設定でpublic OAuth applicationを登録する。

- Redirect URI: `mobgits://oauth/callback`
- Scope: `api`
- Confidential: 無効
- Client secret: 使用しない

[GitLab OAuth provider](https://docs.gitlab.com/integration/oauth_provider/) / [PKCE API](https://docs.gitlab.com/api/oauth2/)

GitLab.comのClient IDはGradle property `MOBGITS_GITLAB_CLIENT_ID` で渡す。
Self-Managedは「GitLabアカウントを追加」→ 接続先の詳細・開発者設定に、そのserverのClient IDを登録する。
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
