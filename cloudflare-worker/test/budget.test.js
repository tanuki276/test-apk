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
function admit(budget, uid) {
  return budget.fetch(new Request('https://budget/admit', {
    method: 'POST', body: JSON.stringify({ uid }),
  }));
}
test('same UID concurrent calls admit only once during cooldown', async () => {
  const { budget } = fixture();
  const results = await Promise.all(Array.from({ length: 10 }, () => admit(budget, 'alice')));
  assert.equal(results.filter(r => r.status === 204).length, 1);
  assert.equal(results.filter(r => r.status === 429).length, 9);
});
test('global cap applies even when anonymous users change', async () => {
  const { budget } = fixture({ GLOBAL_DAILY_LIMIT: '2' });
  assert.equal((await admit(budget, 'a')).status, 204);
  assert.equal((await admit(budget, 'b')).status, 204);
  assert.equal((await admit(budget, 'c')).status, 429);
});
test('user daily cap survives cooldown; other users can continue', async () => {
  const { budget, records } = fixture({ USER_DAILY_LIMIT: '1' });
  await admit(budget, 'a');
  records.get('budget').users['user:a'].last = 0;
  assert.equal((await admit(budget, 'a')).status, 429);
  assert.equal((await admit(budget, 'b')).status, 204);
});
test('UTC next day resets counters and old identifiers', async () => {
  const { budget, records } = fixture({ GLOBAL_DAILY_LIMIT: '1' });
  await admit(budget, 'old');
  records.get('budget').day--;
  assert.equal((await admit(budget, 'new')).status, 204);
  assert.deepEqual(Object.keys(records.get('budget').users), ['user:new']);
});
test('invalid limits use safe defaults and rejected attempts do not consume budget', async () => {
  const { budget, records } = fixture({ GLOBAL_DAILY_LIMIT: '-1', USER_DAILY_LIMIT: 'NaN' });
  await admit(budget, 'a');
  const result = await admit(budget, 'a');
  assert.equal(result.status, 429);
  assert.equal(result.headers.get('Retry-After'), '60');
  assert.equal(records.get('budget').count, 1);
});
