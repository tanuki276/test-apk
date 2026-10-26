// Live deployment check. Uses only newly created test identities, deletes them in finally.
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const config = JSON.parse(readFileSync('app/google-services.json', 'utf8'));
const key = config.client[0].api_key[0].current_key;
const project = config.project_info.project_id;
const base = process.env.SMOKE_BASE_URL;
assert.ok(base?.startsWith('https://'));
const users = [];
async function auth(action, body) {
  const response = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:${action}?key=${encodeURIComponent(key)}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  });
  if (!response.ok) throw new Error(`Firebase ${action} failed: HTTP ${response.status}`);
  return response.json();
}
async function firestore(user, method, suffix, body) {
  const response = await fetch(`https://firestore.googleapis.com/v1/projects/${project}/databases/default/documents/artifacts/liefantidia2/users/${user.localId}/history${suffix}`, {
    method, headers: { Authorization: `Bearer ${user.idToken}`, 'Content-Type': 'application/json' },
    ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(20000),
  });
  if (!response.ok) throw new Error(`Firestore ${method} failed: HTTP ${response.status}`);
  return method === 'DELETE' ? null : response.json();
}
async function usage(user) {
  const response = await fetch(`${base}/usage`, { headers: { Authorization: `Bearer ${user.idToken}` }, signal: AbortSignal.timeout(20000) });
  assert.equal(response.status, 200, 'usage endpoint');
  return response.json();
}
try {
  const health = await (await fetch(`${base}/health`)).json();
  assert.equal(health.apiVersion, 2, 'deployed API version');
  const guest = await auth('signUp', { returnSecureToken: true }); users.push(guest);
  const before = await usage(guest);
  assert.equal(before.guest, true);
  const response = await fetch(`${base}/generate-recipe`, {
    method: 'POST', headers: { Authorization: `Bearer ${guest.idToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ ingredients: '卵2個、塩、水', constraints: '電子レンジで安全に加熱する料理。日本語。', mode: 'normal' }),
    signal: AbortSignal.timeout(120000),
  });
  assert.equal(response.status, 200, 'live recipe generation');
  const recipe = (await response.json()).recipe;
  assert.ok(typeof recipe === 'string' && recipe.trim().length > 30, 'nonempty recipe');
  const after = await usage(guest);
  assert.equal(after.normal.remaining, before.normal.remaining - 1);
  const fields = { id: { nullValue: null }, recipeTitle: { stringValue: 'CookAi deployment test' },
    ingredientsWithUsage: { stringValue: '卵' }, allConstraints: { stringValue: '' },
    recipeContent: { stringValue: recipe }, timestamp: { integerValue: String(Date.now()) } };
  await firestore(guest, 'PATCH', '/deployment-test', { fields });
  const source = await firestore(guest, 'GET', '/deployment-test');
  const account = await auth('signUp', {
    email: `cookai-smoke-${Date.now()}@example.com`, password: `Smoke-${crypto.randomUUID()}!`, returnSecureToken: true,
  }); users.push(account);
  assert.equal((await usage(account)).guest, false);
  await firestore(account, 'PATCH', '/deployment-guest-copy', { fields: source.fields });
  assert.equal((await firestore(account, 'GET', '/deployment-guest-copy')).fields.recipeContent.stringValue, recipe);
  // Re-login is covered by emulator tests; this checks production ownership and migration writes.
  await firestore(account, 'DELETE', '/deployment-guest-copy');
  await firestore(guest, 'DELETE', '/deployment-test');
  console.log('PASS: deployed API v2, guest/account quota, real recipe generation, cloud history read/write and guest copy');
} finally {
  for (const user of users.reverse()) {
    for (const suffix of ['/deployment-test', '/deployment-guest-copy']) {
      try { await firestore(user, 'DELETE', suffix); } catch { /* document may not exist */ }
    }
    try { await auth('delete', { idToken: user.idToken }); }
    catch { console.error('Test identity cleanup failed; review Firebase Authentication test users.'); }
  }
}
