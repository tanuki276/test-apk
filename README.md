# CookAi（コークアイ）

食材・調理時間・食事制限から、日本語のレシピを生成するAndroidアプリです。

## AIアーキテクチャ

AndroidアプリはLLMプロバイダへ直接接続せず、Firebase認証のIDトークンを付けてCloudflare Workerへリクエストします。Worker側で Groq → Gemini → Workers AI の順に利用可能なプロバイダへフォールバックします。

LLMのAPIキーはAPKや端末設定へ保存しません。`GROQ_API_KEY` / `GEMINI_API_KEY` などの秘密情報はCloudflare WorkerのSecretとして管理します。

## 主な機能

- CookAiへの表示名変更（既存データ互換のためapplicationIdとFirestore保存先は維持）
- レシピ生成の二重送信防止、画面破棄時の通信キャンセル
- 完成済みレシピの画面再作成時の復元
- レシピのコピー・共有
- 履歴からの再表示・テキスト保存
- 履歴の個別削除、読み込み失敗時の再試行
- メール登録・ログイン、ゲスト履歴の引き継ぎと再インストール後の履歴復元
- 設定のメール表示は伏せ字
- アレルギー・常備調味料・使える調理方法を端末に事前登録し、毎回の生成に自動反映
- 予算・各選択肢の自由入力を撤去し、補足は追加指示に集約
- 生成成功時だけ完了表示と履歴保存

生成の停止・再試行にも対応しています。

## Androidビルド

JDK 17、Android SDK 36、ネットワーク接続が必要です。

```sh
./gradlew testDebugUnitTest assembleDebug \
  -PAI_PROXY_URL=https://<worker>/generate-recipe
```

通常ビルドには既存デプロイ先のWorker URLが入ります。`AI_PROXY_URL` はGradle property、環境変数、`local.properties` の順で上書きできます。空値は無視します。不正な接続先やサンプルURLではビルドを失敗させ、生成できないAPKの配布を防ぎます。

## Cloudflare Worker

Worker実装は `cloudflare-worker/` にあります。Cloudflare側で必要なSecretを設定してからデプロイしてください。`.github/workflows/deploy-cloud-ai.yml` ではWorkerをデプロイし、取得したWorker URLを埋め込んだデバッグAPKをビルドできます。

## データとセキュリティ

既存インストールと履歴を維持するため、applicationId / Java package `com.example.liefantidia2` とFirestoreの既存保存先は維持します。Firebase認証トークンはWorkerへのアクセス確認に利用します。LLMプロバイダの秘密鍵はAndroidアプリへ配布しません。
