# liefantidia2 Cloud AI Router

Cloudflare Worker を入口にして、次の順番でクラウドLLMをフォールバックします。

1. Groq (`openai/gpt-oss-20b`)
2. Gemini (`GEMINI_MODEL` で指定)
3. Cloudflare Workers AI (`@cf/google/gemma-4-26b-a4b-it`)

AndroidアプリにはLLMプロバイダのAPIキーを保存しません。アプリはFirebase匿名認証のIDトークンをWorkerへ送り、Worker側でFirebase署名・issuer・audienceを検証してから推論します。

## Deploy

```bash
cd cloudflare-worker
npm install
npx wrangler login
npx wrangler secret put GROQ_API_KEY
npx wrangler secret put GEMINI_API_KEY
npm run deploy
```

GroqかGeminiのどちらかを使わない場合、そのSecretは設定しなくても構いません。未設定プロバイダは自動でスキップされ、Workers AIへフォールバックします。

デプロイ後に返されたWorker URLをAndroidビルドへ渡します。

```bash
./gradlew assembleDebug -PAI_PROXY_URL=https://liefantidia2-ai-router.<subdomain>.workers.dev/generate-recipe
```

CIではRepository SecretやVariableから `AI_PROXY_URL` をGradle propertyとして渡してください。URL自体は秘密情報ではありませんが、LLMのAPIキーは必ずWrangler SecretとしてCloudflare側だけに保存してください。

## Provider configuration

`wrangler.toml` の以下を変更するとモデルや優先順を差し替えられます。

```toml
PROVIDER_ORDER = "groq,gemini,workers"
GROQ_MODEL = "openai/gpt-oss-20b"
GEMINI_MODEL = "gemini-3.8-flash"
WORKERS_AI_MODEL = "@cf/google/gemma-4-26b-a4b-it"
```

無料枠・利用可能モデルは各プロバイダ側で変更される可能性があるため、モデル名はWorker側の環境変数として管理し、APK更新なしで変更できるようにしています。

## Endpoints

- `GET /health` — Workerの死活確認
- `POST /generate-recipe` — Firebase ID token必須

`POST /generate-recipe` body:

```json
{
  "ingredients": "鶏肉、玉ねぎ、卵",
  "constraints": "30分以内、1000円以内"
}
```
