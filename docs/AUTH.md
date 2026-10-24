# MobGits Lab 認証設計

## 推奨: GitLab OAuth 2.0 + PKCE

MobGits Lab はモバイルアプリ向けに OAuth 2.0 Authorization Code + PKCE を使います。
クライアントシークレットはAPKに埋め込みません。

### GitLab.com 用 OAuth Application

1. GitLab の User Settings > Applications で OAuth Application を作成
2. Redirect URI に `mobgits://oauth/callback` を設定
3. Confidential のチェックを外す
4. Scope は `api` を有効化
5. 発行された Application ID を Gradle property `MOBGITS_GITLAB_CLIENT_ID` に設定

例: `~/.gradle/gradle.properties`

```properties
MOBGITS_GITLAB_CLIENT_ID=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

GitHub Actions ではビルド自体は Client ID なしでも通ります。配布APKでOAuthを有効にする場合は、CI側でGradle propertyを注入してください。

## PAT

セルフホストGitLabや開発時のフォールバックとして Personal Access Token ログインも残します。
認証情報は Android Keystore の AES/GCM キーで暗号化して端末内に保存します。

## セキュリティ方針

- OAuth client secret をアプリへ埋め込まない
- OAuth state を検証する
- PKCE S256 を使用する
- access token / refresh token は暗号化保存
- OAuth access token は Bearer token として送信
- PAT は PRIVATE-TOKEN ヘッダーとして送信
- 平文HTTPのGitLabサーバーはサポートしない
