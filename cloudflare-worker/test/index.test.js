import assert from "node:assert/strict";
import { after, test } from "node:test";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { importPKCS8, SignJWT } from "jose";
import worker, { withTimeout } from "../src/index.js";

// Ephemeral signing material: no production credentials are used.
const dir = mkdtempSync(join(tmpdir(), "cookai-test-"));
execFileSync("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes",
  "-keyout", join(dir, "key.pem"), "-out", join(dir, "cert.pem"),
  "-subj", "/CN=cookai-test", "-days", "1"], { stdio: "ignore" });
const key = await importPKCS8(readFileSync(join(dir, "key.pem"), "utf8"), "RS256");
const cert = readFileSync(join(dir, "cert.pem"), "utf8");
rmSync(dir, { recursive: true, force: true });
const originalFetch = globalThis.fetch;
after(() => { globalThis.fetch = originalFetch; });
const env = { FIREBASE_PROJECT_ID: "test-project", AI: { run: async () => ({ response: "レシピ" }) } };

async function token(overrides = {}) {
  const now = Math.floor(Date.now() / 1000);
  const claims = { sub: "test-user", iat: now, auth_time: now, exp: now + 3600, ...overrides };
  for (const name of Object.keys(claims)) if (claims[name] === undefined) delete claims[name];
  return new SignJWT(claims).setProtectedHeader({ alg: "RS256", kid: "test-key" })
    .setAudience("test-project").setIssuer("https://securetoken.google.com/test-project").sign(key);
}
function mockFetch(provider = async () => { throw new Error("Unexpected provider request"); }) {
  globalThis.fetch = async (url, options) => String(url).includes("/metadata/x509/")
    ? Response.json({ "test-key": cert }, { headers: { "Cache-Control": "max-age=0" } })
    : provider(url, options);
}
async function request(body, jwt = null, config = env) {
  return worker.fetch(new Request("https://example.test/generate-recipe", {
    method: "POST", headers: { Authorization: `Bearer ${jwt ?? await token()}` }, body,
  }), config);
}

test("malformed, expired, and incomplete Firebase tokens return 401", async () => {
  mockFetch();
  for (const jwt of ["invalid", await token({ exp: 1 }), await token({ exp: undefined }),
    await token({ iat: undefined }), await token({ auth_time: undefined }),
    await token({ auth_time: "invalid" }), await token({ iat: Math.floor(Date.now()/1000) + 1000 }),
    await token({ sub: "x".repeat(129) })]) {
    assert.equal((await request('{"ingredients":"卵"}', jwt)).status, 401);
  }
});

test("certificate service failures are server errors, not authentication failures", async () => {
  globalThis.fetch = async () => new Response("unavailable", { status: 503 });
  assert.equal((await request('{"ingredients":"卵"}')).status, 500);
});

test("malformed JSON and invalid input types return 400", async () => {
  mockFetch();
  for (const body of ["{", "null", "[]", "{}", '{"ingredients":123}',
    '{"ingredients":"卵","constraints":{}}', '{"ingredients":"  "}',
    JSON.stringify({ ingredients: "x".repeat(2001) })]) {
    assert.equal((await request(body)).status, 400);
  }
});

test("valid authenticated request returns recipe and accepts omitted constraints", async () => {
  mockFetch();
  const response = await request('{"ingredients":"卵"}');
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { recipe: "レシピ", provider: "workers", uid: "test-user" });
});

test("provider failure falls back to Workers AI", async () => {
  mockFetch(async () => new Response("rate limited", { status: 429 }));
  const response = await request('{"ingredients":"卵"}', null, { ...env, GROQ_API_KEY: "test" });
  assert.equal(response.status, 200);
  assert.equal((await response.json()).provider, "workers");
});

test("unavailable providers return 503 without leaking internal errors", async () => {
  mockFetch();
  const response = await request('{"ingredients":"卵"}', null, {
    ...env, AI: { run: async () => { throw new Error("private upstream detail"); } },
  });
  assert.equal(response.status, 503);
  assert.equal((await response.text()).includes("private upstream detail"), false);
});

test("hung provider is bounded and aborts HTTP before fallback", async () => {
  let signal;
  await assert.rejects(withTimeout((value) => {
    signal = value;
    return new Promise(() => {});
  }, 10), /Provider timeout/);
  assert.equal(signal.aborted, true);
  assert.equal(await withTimeout(async () => "fallback", 10), "fallback");
});

test("successful timeout wrapper returns provider result", async () => {
  assert.equal(await withTimeout(async () => "recipe", 10), "recipe");
});

test("a timed-out Groq request falls back within the same Worker request", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  let providerStarted;
  const started = new Promise((resolve) => { providerStarted = resolve; });
  let signal;
  mockFetch(async (_url, options) => {
    signal = options.signal;
    providerStarted();
    return new Promise(() => {});
  });
  const responsePromise = request('{"ingredients":"卵"}', null, { ...env, GROQ_API_KEY: "test" });
  await started;
  t.mock.timers.tick(20000);
  const response = await responsePromise;
  assert.equal(signal.aborted, true);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).provider, "workers");
});

test("Firebase certificates are refreshed when max-age has expired", async () => {
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return Response.json({ "test-key": cert }, { headers: { "Cache-Control": "max-age=0" } });
  };
  assert.equal((await request('{"ingredients":"卵"}')).status, 200);
  assert.equal((await request('{"ingredients":"卵"}')).status, 200);
  assert.equal(calls, 2);
});
