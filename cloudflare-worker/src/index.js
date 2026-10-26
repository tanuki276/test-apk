export { GenerationBudget } from "./budget.js";
import { decodeProtectedHeader, importX509, jwtVerify } from "jose";

const FIREBASE_CERTS_URL =
  "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";

let cachedCerts = null;
let certsExpireAt = 0;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true });
    }

    try {
      if (request.method === "GET" && url.pathname === "/usage") {
        const identity = await verifyFirebaseRequest(request, env.FIREBASE_PROJECT_ID);
        const budget = getBudget(env);
        if (!budget) return json({ error: "利用状況を取得できません。" }, 503);
        const status = await budget.fetch("https://budget/status", {
          method: "POST",
          body: JSON.stringify(identity),
        });
        return new Response(status.body, { status: status.status, headers: status.headers });
      }

      if (request.method !== "POST" || url.pathname !== "/generate-recipe") {
        return json({ error: "Not found" }, 404);
      }

      const identity = await verifyFirebaseRequest(request, env.FIREBASE_PROJECT_ID);
      const body = await readJsonBody(request);
      if (!body) return json({ error: "入力内容を確認してください。" }, 400);
      if (typeof body.ingredients !== "string" ||
          (body.constraints !== undefined && typeof body.constraints !== "string") ||
          (body.mode !== undefined && body.mode !== "normal" && body.mode !== "high")) {
        return json({ error: "入力内容を確認してください。" }, 400);
      }

      const ingredients = String(body.ingredients || "").trim();
      const constraints = String(body.constraints || "").trim();
      const mode = body.mode === "high" ? "high" : "normal";
      if (!ingredients) return json({ error: "食材が入力されていません。" }, 400);
      if (ingredients.length > 2000 || constraints.length > 4000) {
        return json({ error: "入力が長すぎます。" }, 413);
      }

      const budget = getBudget(env);
      if (!budget) return json({ error: "しばらくしてから再試行してください。" }, 503);
      const admission = await budget.fetch("https://budget/admit", {
        method: "POST",
        body: JSON.stringify({ ...identity, mode }),
      });
      if (!admission.ok) {
        return new Response(admission.body, { status: admission.status, headers: admission.headers });
      }

      const prompt = buildRecipePrompt(ingredients, constraints, mode === "high");
      if (mode === "normal") {
        if (!env.AI) return json({ error: "AIサーバーでエラーが発生しました。" }, 503);
        try {
          const recipe = await callWorkersAI(env, prompt, false);
          if (recipe?.trim()) return json({ recipe: recipe.trim(), mode: "normal" });
        } catch (error) {
          console.error("Workers AI normal generation failed", safeMessage(error));
        }
        return json({ error: "ただいま混み合っています。しばらくしてから再試行してください。" }, 503);
      }

      // High accuracy is intentionally the only route that can use Groq or Gemini.
      const order = highProviderOrder(env);
      for (const provider of order) {
        try {
          const first = await callProvider(provider, env, prompt, true);
          if (!first?.trim()) continue;
          const reviewed = await callProvider(provider, env, buildReviewPrompt(prompt, first), true);
          return json({ recipe: (reviewed?.trim() || first.trim()), mode: "high" });
        } catch (error) {
          console.error(`High accuracy provider ${provider} failed`, safeMessage(error));
        }
      }
      return json({ error: "高精度モードを完了できませんでした。少し待ってから再試行してください。" }, 503);
    } catch (error) {
      const message = safeMessage(error);
      const status = message.startsWith("AUTH_") ? 401 : message === "BODY_TOO_LARGE" ? 413 : 500;
      return json({
        error: status === 401
          ? "認証に失敗しました。ログイン状態を確認して再試行してください。"
          : status === 413 ? "入力が長すぎます。" : "AIサーバーでエラーが発生しました。",
      }, status);
    }
  },
};

function getBudget(env) {
  if (!env.GENERATION_BUDGET) return null;
  return env.GENERATION_BUDGET.get(env.GENERATION_BUDGET.idFromName("generation-budget"));
}

async function readJsonBody(request) {
  const reader = request.body?.getReader();
  if (!reader) return null;
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > 32768) {
      await reader.cancel();
      throw new Error("BODY_TOO_LARGE");
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  try {
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return null;
  }
}

function buildRecipePrompt(ingredients, constraints, highAccuracy) {
  const quality = highAccuracy ? [
    "高精度モードです。回答前に条件を内部で照合し、矛盾や食材の使い忘れを修正してください。",
    "アレルギー・食事制限・利用可能な調味料や器具を最優先し、満たせない場合は無理にレシピを作らず必要な確認を説明してください。",
    "材料の分量、下準備、火加減、加熱時間の目安、完成確認を具体的にしてください。",
  ] : [];
  return [
    "あなたは日本語で回答する料理アシスタントです。",
    "実際に家庭で作れるレシピを1つ提案してください。",
    "出力はMarkdownで、必ず『レシピ名』『材料』『手順』の順にしてください。",
    "アレルギー・食事制限・調理方法・調理時間の指定がある場合は優先してください。",
    "存在しない食材や危険な調理手順を作らないでください。",
    ...quality,
    "",
    `利用食材: ${ingredients}`,
    `制約: ${constraints || "指定なし"}`,
  ].join("\n");
}

function buildReviewPrompt(originalPrompt, recipe) {
  return [
    "次のレシピを高精度レビューしてください。",
    "元の条件と照合し、食材・アレルギー・調味料・器具・調理時間・分量・加熱の矛盾や不足を修正してください。",
    "問題がなければ内容を維持してください。説明やレビュー結果は付けず、修正後の完成レシピ全文だけをMarkdownで返してください。",
    "",
    "【元の依頼】",
    originalPrompt,
    "",
    "【生成済みレシピ】",
    recipe,
  ].join("\n");
}

function highProviderOrder(env) {
  return [...new Set(String(env.HIGH_PROVIDER_ORDER || "groq,gemini,workers")
    .split(",")
    .map(v => v.trim().toLowerCase())
    .filter(v => ["groq", "gemini", "workers"].includes(v)))]
    .filter(provider => provider === "workers" ? !!env.AI
      : provider === "groq" ? !!env.GROQ_API_KEY : !!env.GEMINI_API_KEY);
}

async function callProvider(provider, env, prompt, highAccuracy) {
  if (provider === "groq") return callGroq(env, prompt, highAccuracy);
  if (provider === "gemini") return callGemini(env, prompt, highAccuracy);
  if (provider === "workers") return callWorkersAI(env, prompt, highAccuracy);
  return "";
}

async function callGroq(env, prompt, highAccuracy) {
  const response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.GROQ_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: highAccuracy ? (env.GROQ_HIGH_MODEL || "openai/gpt-oss-120b") : (env.GROQ_MODEL || "openai/gpt-oss-20b"),
      messages: [{ role: "user", content: prompt }],
      temperature: highAccuracy ? 0.45 : 0.7,
      max_completion_tokens: highAccuracy ? 2200 : 1400,
      reasoning_effort: highAccuracy ? "high" : "medium",
      include_reasoning: false,
    }),
    signal: AbortSignal.timeout(highAccuracy ? 45000 : 25000),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const data = await response.json();
  return data?.choices?.[0]?.message?.content || "";
}

async function callGemini(env, prompt, highAccuracy) {
  const model = highAccuracy ? (env.GEMINI_HIGH_MODEL || "gemini-3.8-flash") : (env.GEMINI_MODEL || "gemini-3.8-flash");
  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(env.GEMINI_API_KEY)}`,
    {
      method: "POST",
      signal: AbortSignal.timeout(highAccuracy ? 45000 : 25000),
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: {
          temperature: highAccuracy ? 0.45 : 0.7,
          maxOutputTokens: highAccuracy ? 2200 : 1400,
          ...(highAccuracy ? { thinkingConfig: { thinkingLevel: "HIGH", includeThoughts: false } } : {}),
        },
      }),
    },
  );
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const data = await response.json();
  const parts = data?.candidates?.[0]?.content?.parts || [];
  return parts.filter(part => !part?.thought).map(part => part?.text || "").join("\n");
}

async function callWorkersAI(env, prompt, highAccuracy) {
  const model = env.WORKERS_AI_MODEL || "@cf/google/gemma-4-26b-a4b-it";
  const result = await env.AI.run(model, {
    max_tokens: highAccuracy ? 2200 : 1400,
    temperature: highAccuracy ? 0.45 : 0.7,
    messages: [
      { role: "system", content: highAccuracy
        ? "条件を厳密に照合し、安全で再現可能な日本語の料理レシピを作成してください。"
        : "簡潔で実用的な日本語の料理レシピを作成してください。" },
      { role: "user", content: prompt },
    ],
  });
  if (typeof result === "string") return result;
  if (typeof result?.response === "string") return result.response;
  if (typeof result?.result?.response === "string") return result.result.response;
  if (typeof result?.choices?.[0]?.message?.content === "string") return result.choices[0].message.content;
  if (typeof result?.result?.choices?.[0]?.message?.content === "string") return result.result.choices[0].message.content;
  if (Array.isArray(result?.choices?.[0]?.message?.content)) {
    return result.choices[0].message.content
      .map(part => typeof part === "string" ? part : part?.text || "")
      .join("\n");
  }
  return "";
}

async function verifyFirebaseRequest(request, projectId) {
  if (!projectId) throw new Error("AUTH_PROJECT_NOT_CONFIGURED");
  const header = request.headers.get("Authorization") || "";
  if (!header.startsWith("Bearer ")) throw new Error("AUTH_MISSING_TOKEN");
  const token = header.slice("Bearer ".length).trim();
  const protectedHeader = decodeProtectedHeader(token);
  if (protectedHeader.alg !== "RS256" || !protectedHeader.kid) throw new Error("AUTH_INVALID_HEADER");

  const certs = await getFirebaseCerts();
  const cert = certs[protectedHeader.kid];
  if (!cert) throw new Error("AUTH_UNKNOWN_KEY");
  const key = await importX509(cert, "RS256");
  const { payload } = await jwtVerify(token, key, {
    algorithms: ["RS256"],
    audience: projectId,
    issuer: `https://securetoken.google.com/${projectId}`,
  });

  const now = Math.floor(Date.now() / 1000);
  if (!payload.sub || typeof payload.sub !== "string") throw new Error("AUTH_INVALID_SUBJECT");
  if (typeof payload.auth_time === "number" && payload.auth_time > now) throw new Error("AUTH_INVALID_TIME");
  const provider = payload.firebase?.sign_in_provider || "";
  const email = typeof payload.email === "string" ? payload.email : "";
  return {
    uid: payload.sub,
    guest: provider === "anonymous" || email.endsWith("@guest.cookai.invalid"),
  };
}

async function getFirebaseCerts() {
  const now = Date.now();
  if (cachedCerts && now < certsExpireAt) return cachedCerts;
  const response = await fetch(FIREBASE_CERTS_URL);
  if (!response.ok) throw new Error("AUTH_CERT_FETCH_FAILED");
  cachedCerts = await response.json();
  const cacheControl = response.headers.get("Cache-Control") || "";
  const maxAgeMatch = cacheControl.match(/max-age=(\d+)/);
  const maxAgeSeconds = maxAgeMatch ? Number(maxAgeMatch[1]) : 3600;
  certsExpireAt = now + Math.max(300, maxAgeSeconds - 60) * 1000;
  return cachedCerts;
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

function safeMessage(error) {
  if (error instanceof Error) return error.message;
  return String(error || "unknown error");
}
