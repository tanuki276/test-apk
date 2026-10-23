import { decodeProtectedHeader, importX509, jwtVerify } from "jose";

const FIREBASE_CERTS_URL =
  "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";

let cachedCerts = null;
let certsExpireAt = 0;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({
        ok: true,
        service: "liefantidia2-ai-router",
        providers: {
          groq: Boolean(env.GROQ_API_KEY),
          gemini: Boolean(env.GEMINI_API_KEY),
          workers: Boolean(env.AI),
        },
      });
    }

    if (request.method !== "POST" || url.pathname !== "/generate-recipe") {
      return json({ error: "Not found" }, 404);
    }

    try {
      const uid = await verifyFirebaseRequest(request