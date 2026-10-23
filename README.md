# CookAi（コークアイ）

食材・予算・調理時間・食事制限から、日本語のレシピを生成するAndroidアプリです。

## AIアーキテクチャ

AndroidアプリはLLMプロバイダへ直接接続せず、Firebase匿名認証のIDトークンを付けてCloudflare Workerへリクエストします。Worker側で Groq → Gemini → Workers AI の順に利用可能なプロバイダへフォールバックします。

LLMのAPIキーはAPKや端末設定へ保存しません。`GROQ_API_KEY` / `GEMINI_API_KEY` などの秘密情報はCloudflare WorkerのSecretとして管理します。

## 主な機能

- CookAiへの表示名変更（既存データ互換のためapplicationIdとFirestore保存先は維持）
- レシピ生成の二重送信防止、画面破棄時の通信キャンセル
- 完成済みレシピの画面再作成時の復元
- レシピのコピー・共有
- 履歴からの再表示・テキスト保存
- 履歴の大量削除を400件ずつ処理し、読み込み失敗時に再試行
- 生成成功時だけ完了表示と履歴保存

写真からの食材入力は準備中です。

## Androidビルド

JDK 17、Android SDK 34、ネットワーク接続が必要です。

```sh
./gradlew testDebugUnitTest assembleDebug \
  -PAI_PROXY_URL=https://<worker>/generate-recipe
```

`AI_PROXY_URL` が未設定の場合はプレースホルダーURLでビルドされるため、AI生成は無効になります。

## Cloudflare Worker

Worker実装は `cloudflare-worker/` にあります。Cloudflare側で必要なSecretを設定してからデプロイしてください。`.github/workflows/deploy-cloud-ai.yml` ではWorkerをデプロイし、取得したWorker URLを埋め込んだデバッグAPKをビルドできます。

## データとセキュリティ

既存インストールと履歴を維持するため、applicationId / Java package `com.example.liefantidia2` とFirestoreの既存保存先は維持します。Firebase匿名認証トークンはWorkerへのアクセス確認に利用します。LLMプロバイダの秘密鍵はAndroidアプリへ配布しません。
