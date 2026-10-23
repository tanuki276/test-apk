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

    if (request.method !== "POST" || url.pathname !== "/generate-recipe") {
      return json({ error: "Not found" }, 404);
    }

    try {
      const uid = await verifyFirebaseRequest(request, env.FIREBASE_PROJECT_ID).catch(() => {
        throw new Error("AUTH_INVALID");
      });
      // Bound the body before buffering/parsing it, including chunked requests.
      const reader = request.body?.getReader();
      if (!reader) return json({ error: "入力内容を確認してください。" }, 400);
      const chunks = [];
      let size = 0;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > 32768) {
          await reader.cancel();
          return json({ error: "入力が長すぎます。" }, 413);
        }
        chunks.push(value);
      }
      const bytes = new Uint8Array(size);
      let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      let body;
      try { body = JSON.parse(new TextDecoder().decode(bytes)); }
      catch { return json({ error: "入力内容を確認してください。" }, 400); }
      if (typeof body?.ingredients !== "string" ||
          (body.constraints !== undefined && typeof body.constraints !== "string")) {
        return json({ error: "入力内容を確認してください。" }, 400);
      }
      const ingredients = String(body?.ingredients || "").trim();
      const constraints = String(body?.constraints || "").trim();

      if (!ingredients) {
        return json({ error: "食材が入力されていません。" }, 400);
      }

      if (ingredients.length > 2000 || constraints.length > 4000) {
        return json({ error: "入力が長すぎます。" }, 400);
      }

      // One durable coordinator provides atomic, global admission limits.
      if (!env.GENERATION_BUDGET) return json({ error: "しばらくしてから再試行してください。" }, 503);
      const budget = env.GENERATION_BUDGET.get(env.GENERATION_BUDGET.idFromName("generation-budget"));
      const admission = await budget.fetch("https://budget/admit", {
        method: "POST", body: JSON.stringify({ uid }),
      });
      if (!admission.ok) return new Response(admission.body, { status: admission.status, headers: admission.headers });

      const prompt = buildRecipePrompt(ingredients, constraints);
      const order = [...new Set(String(env.PROVIDER_ORDER || "groq,gemini,workers")
        .split(",")
        .map((v) => v.trim().toLowerCase())
        .filter(v => ["groq", "gemini", "workers"].includes(v)))];


      for (const provider of order) {
        try {
          let recipe = null;

          if (provider === "groq" && env.GROQ_API_KEY) {
            recipe = await callGroq(env, prompt);
          } else if (provider === "gemini" && env.GEMINI_API_KEY) {
            recipe = await callGemini(env, prompt);
          } else if (provider === "workers" && env.AI) {
            recipe = await callWorkersAI(env, prompt);
          } else {
            continue;
          }

          if (recipe && recipe.trim()) {
            return json({ recipe: recipe.trim() });
          }

        } catch (error) {
          console.error(`Provider ${provider} failed`);
        }
      }

      return json({ error: "ただいま混み合っています。しばらくしてから再試行してください。" }, 503);
    } catch (error) {
      const message = safeMessage(error);
      const status = message.startsWith("AUTH_") ? 401 : 500;
      return json(
        {
          error:
            status === 401
              ? "認証に失敗しました。アプリを再起動して再試行してください。"
              : "AIサーバーでエラーが発生しました。",
        },
        status,
      );
    }
  },
};

function buildRecipePrompt(ingredients, constraints) {
  return [
    "あなたは日本語で回答する料理アシスタントです。",
    "実際に家庭で作れるレシピを1つ提案してください。",
    "出力はMarkdownで、必ず『レシピ名』『材料』『手順』の順にしてください。",
    "アレルギー・食事制限・調理方法・調理時間の指定がある場合は優先してください。",
    "存在しない食材や危険な調理手順を作らないでください。",
    "",
    `利用食材: ${ingredients}`,
    `制約: ${constraints || "指定なし"}`,
  ].join("\n");
}

async function callGroq(env, prompt) {
  const response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.GROQ_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: env.GROQ_MODEL || "openai/gpt-oss-20b",
      messages: [
        {
          role: "system",
          content: "簡潔で実用的な日本語の料理レシピを作成してください。",
        },
        { role: "user", content: prompt },
      ],
      temperature: 0.7,
      max_completion_tokens: 1400,
    }),
    signal: AbortSignal.timeout(25000),
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  const data = await response.json();
  return data?.choices?.[0]?.message?.content || "";
}

async function callGemini(env, prompt) {
  const model = env.GEMINI_MODEL || "gemini-3.8-flash";
  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(env.GEMINI_API_KEY)}`,
    {
      method: "POST",
      signal: AbortSignal.timeout(25000),
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: {
          temperature: 0.7,
          maxOutputTokens: 1400,
        },
      }),
    },
  );

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  const data = await response.json();
  const parts = data?.candidates?.[0]?.content?.parts || [];
  return parts.map((part) => part?.text || "").join("\n");
}

async function callWorkersAI(env, prompt) {
  const model = env.WORKERS_AI_MODEL || "@cf/google/gemma-4-26b-a4b-it";
  const result = await env.AI.run(model, {
    max_tokens: 1400,
    messages: [
      {
        role: "system",
        content: "簡潔で実用的な日本語の料理レシピを作成してください。",
      },
      { role: "user", content: prompt },
    ],
  });

  if (typeof result === "string") return result;
  if (typeof result?.response === "string") return result.response;
  if (typeof result?.result?.response === "string") return result.result.response;
  if (typeof result?.choices?.[0]?.message?.content === "string") {
    return result.choices[0].message.content;
  }
  if (typeof result?.result?.choices?.[0]?.message?.content === "string") {
    return result.result.choices[0].message.content;
  }
  if (Array.isArray(result?.choices?.[0]?.message?.content)) {
    return result.choices[0].message.content
      .map((part) => (typeof part === "string" ? part : part?.text || ""))
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

  if (protectedHeader.alg !== "RS256" || !protectedHeader.kid) {
    throw new Error("AUTH_INVALID_HEADER");
  }

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
  if (!payload.sub || typeof payload.sub !== "string") {
    throw new Error("AUTH_INVALID_SUBJECT");
  }
  if (typeof payload.auth_time === "number" && payload.auth_time > now) {
    throw new Error("AUTH_INVALID_TIME");
  }

  return payload.sub;
}

async function getFirebaseCerts() {
  const now = Date.now();
  if (cachedCerts && now < certsExpireAt) {
    return cachedCerts;
  }

  const response = await fetch(FIREBASE_CERTS_URL);
  if (!response.ok) {
    throw new Error("AUTH_CERT_FETCH_FAILED");
  }

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
