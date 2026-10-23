import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';

test('health does not expose configured providers', async () => {
  const response = await worker.fetch(new Request('https://example/health'), { GROQ_API_KEY: 'secret' });
  assert.deepEqual(await response.json(), { ok: true });
});
test('missing and malformed authentication cannot reach providers', async () => {
  for (const token of ['', 'Bearer not-a-jwt']) {
    const response = await worker.fetch(new Request('https://example/generate-recipe', {
      method: 'POST', headers: { Authorization: token }, body: JSON.stringify({ ingredients: '卵' }),
    }), { FIREBASE_PROJECT_ID: 'demo-project' });
    assert.equal(response.status, 401);
    const body = await response.text();
    assert.ok(!body.includes('AUTH_'));
    assert.ok(!body.includes('provider'));
  }
});
