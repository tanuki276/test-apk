import { test } from 'node:test';
import assert from 'node:assert/strict';
import { GenerationBudget } from '../src/budget.js';

function fixture(env = {}) {
  const records = new Map();
  let queue = Promise.resolve();
  const storage = {
    get: async key => structuredClone(records.get(key)),
    put: async (key, value) => records.set(key, structuredClone(value)),
    transaction(fn) {
      const next = queue.then(() => fn(storage));
      queue = next.catch(() => {});
      return next;
    },
  };
  return { budget: new GenerationBudget({ storage }, env), records };
}

function request(budget, path, body) {
  return budget.fetch(new Request(`https://budget/${path}`, {
    method: 'POST', body: JSON.stringify(body),
  }));
}

const admit = (budget, uid, { guest = false, mode = 'normal' } = {}) =>
  request(budget, 'admit', { uid, guest, mode });
const status = (budget, uid, guest = false) => request(budget, 'status', { uid, guest });

function clearCooldown(records, uid) {
  records.get('budget-v2').users[`user:${uid}`].last = 0;
}

test('same UID concurrent calls admit only once during cooldown', async () => {
  const { budget } = fixture();
  const results = await Promise.all(Array.from({ length: 10 }, () => admit(budget, 'alice')));
  assert.equal(results.filter(r => r.status === 204).length, 1);
  assert.equal(results.filter(r => r.status === 429).length, 9);
});

test('guest gets five normal generations and cannot use high accuracy', async () => {
  const { budget, records } = fixture({ GUEST_NORMAL_DAILY_LIMIT: '5' });
  for (let i = 0; i < 5; i++) {
    assert.equal((await admit(budget, 'guest-a', { guest: true })).status, 204);
    clearCooldown(records, 'guest-a');
  }
  assert.equal((await admit(budget, 'guest-a', { guest: true })).status, 429);
  assert.equal((await admit(budget, 'guest-b', { guest: true, mode: 'high' })).status, 403);
});

test('signed-in normal and high accuracy have independent daily limits', async () => {
  const { budget, records } = fixture({ USER_NORMAL_DAILY_LIMIT: '2', USER_HIGH_DAILY_LIMIT: '1' });
  assert.equal((await admit(budget, 'user')).status, 204);
  clearCooldown(records, 'user');
  assert.equal((await admit(budget, 'user')).status, 204);
  clearCooldown(records, 'user');
  assert.equal((await admit(budget, 'user')).status, 429);
  assert.equal((await admit(budget, 'user', { mode: 'high' })).status, 204);
  clearCooldown(records, 'user');
  assert.equal((await admit(budget, 'user', { mode: 'high' })).status, 429);
});

test('status reports remaining quotas for settings screens', async () => {
  const { budget, records } = fixture({ USER_NORMAL_DAILY_LIMIT: '20', USER_HIGH_DAILY_LIMIT: '10' });
  await admit(budget, 'u');
  clearCooldown(records, 'u');
  await admit(budget, 'u', { mode: 'high' });
  const response = await status(budget, 'u');
  const body = await response.json();
  assert.deepEqual(body.normal, { used: 1, limit: 20, remaining: 19 });
  assert.deepEqual(body.highAccuracy, { available: true, used: 1, limit: 10, remaining: 9 });
});

test('global cap applies even when anonymous users change', async () => {
  const { budget } = fixture({ GLOBAL_DAILY_LIMIT: '2' });
  assert.equal((await admit(budget, 'a')).status, 204);
  assert.equal((await admit(budget, 'b')).status, 204);
  assert.equal((await admit(budget, 'c')).status, 429);
});

test('UTC next day resets counters and old identifiers', async () => {
  const { budget, records } = fixture({ GLOBAL_DAILY_LIMIT: '1' });
  await admit(budget, 'old');
  records.get('budget-v2').day--;
  assert.equal((await admit(budget, 'new')).status, 204);
  assert.deepEqual(Object.keys(records.get('budget-v2').users), ['user:new']);
});
