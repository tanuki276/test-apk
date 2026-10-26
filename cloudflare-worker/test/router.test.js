import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { handleRequest } from '../src/index.js';

test('health does not expose configured providers', async () => {
  const response = await worker.fetch(new Request('https://example/health'), { GROQ_API_KEY: 'secret' });
  assert.deepEqual(await response.json(), { ok: true, apiVersion: 2, capabilities: ['usage', 'normal', 'high'] });
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

const identity = async () => ({ uid: 'test-user', guest: false });
function envWithAI(run) {
  const calls = [];
  return { calls, AI: { run }, GENERATION_BUDGET: {
    idFromName: name => name,
    get: () => ({ fetch: async (url, init) => {
      calls.push({ url, body: JSON.parse(init.body) });
      if (url.endsWith('/status')) return Response.json({ guest: false,
        normal: { used: 1, limit: 20, remaining: 19 },
        highAccuracy: { available: true, used: 0, limit: 10, remaining: 10 } });
      return new Response(null, { status: 204 });
    } }),
  } };
}
const generate = mode => new Request('https://worker/generate-recipe', {
  method: 'POST', body: JSON.stringify({ ingredients: '卵', mode }),
});
test('usage forwards authenticated identity without consuming a generation', async () => {
  const env = envWithAI(() => { throw new Error('must not generate'); });
  const response = await handleRequest(new Request('https://worker/usage'), env, identity);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).normal.remaining, 19);
  assert.deepEqual(env.calls, [{ url: 'https://budget/status', body: { uid: 'test-user', guest: false } }]);
});
test('normal generation accepts each Workers AI response envelope', async () => {
  for (const result of ['レシピ', { response: 'レシピ' }, { result: { response: 'レシピ' } },
    { choices: [{ message: { content: 'レシピ' } }] },
    { result: { choices: [{ message: { content: 'レシピ' } }] } },
    { choices: [{ message: { content: [{ type: 'text', text: 'レシピ' }] } }] }]) {
    const env = envWithAI(async (_model, input) => {
      assert.equal(input.chat_template_kwargs.enable_thinking, false);
      assert.ok(input.max_completion_tokens >= 2400);
      return result;
    });
    const response = await handleRequest(generate('normal'), env, identity);
    assert.equal(response.status, 200);
    assert.equal((await response.json()).recipe, 'レシピ');
    assert.equal(env.calls.length, 1);
  }
});
test('review failure preserves the generated recipe', async () => {
  let calls = 0;
  const env = envWithAI(async () => {
    if (++calls === 2) throw new Error('review temporarily unavailable');
    return { response: '完成したレシピ' };
  });
  const response = await handleRequest(generate('high'), env, identity);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).recipe, '完成したレシピ');
  assert.equal(calls, 2);
});
test('invalid input never reaches the budget or AI', async () => {
  const env = envWithAI(() => { throw new Error('must not generate'); });
  const response = await handleRequest(new Request('https://worker/generate-recipe', {
    method: 'POST', body: JSON.stringify({ ingredients: '卵'.repeat(2001) }),
  }), env, identity);
  assert.equal(response.status, 413);
  assert.equal(env.calls.length, 0);
});

test('production fetch context cannot replace the authentication verifier', async () => {
  const response = await worker.fetch(new Request('https://worker/usage'),
    { FIREBASE_PROJECT_ID: 'demo-project' }, { waitUntil() {}, passThroughOnException() {} });
  assert.equal(response.status, 401);
});

test('missing providers do not consume quota in either mode', async () => {
  for (const mode of ['normal', 'high']) {
    const env = envWithAI(async () => 'unused');
    delete env.AI;
    const response = await handleRequest(generate(mode), env, identity);
    assert.equal(response.status, 503);
    assert.equal(env.calls.length, 0);
  }
});
test('an empty high accuracy provider order does not consume quota', async () => {
  const env = envWithAI(async () => { throw new Error('must not generate'); });
  env.HIGH_PROVIDER_ORDER = 'unknown';
  const response = await handleRequest(generate('high'), env, identity);
  assert.equal(response.status, 503);
  assert.equal(env.calls.length, 0);
});
