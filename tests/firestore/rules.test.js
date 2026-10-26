import { before, after, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, collection, setDoc, getDoc, getDocs, updateDoc, deleteDoc } from 'firebase/firestore';
let env;
const path = 'artifacts/liefantidia2/users/alice/history/recipe';
const recipe = { id: null, recipeTitle: '卵焼き', ingredientsWithUsage: '卵', allConstraints: '', recipeContent: '材料と手順', timestamp: Date.now() };
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-cookai', firestore: { rules: readFileSync('../../firestore.rules', 'utf8') } }); });
after(async () => { await env?.cleanup(); });
test('owner can create/read/query/update/delete; other users and guests cannot', async () => {
  const owner = env.authenticatedContext('alice').firestore();
  const other = env.authenticatedContext('bob').firestore();
  const guest = env.unauthenticatedContext().firestore();
  await assertSucceeds(setDoc(doc(owner, path), recipe));
  await assertSucceeds(getDoc(doc(owner, path)));
  await assertSucceeds(getDocs(collection(owner, 'artifacts/liefantidia2/users/alice/history')));
  for (const db of [other, guest]) {
    await assertFails(getDoc(doc(db, path)));
    await assertFails(getDocs(collection(db, 'artifacts/liefantidia2/users/alice/history')));
    await assertFails(setDoc(doc(db, path), recipe));
    await assertFails(updateDoc(doc(db, path), { recipeTitle: 'x' }));
    await assertFails(deleteDoc(doc(db, path)));
  }
  await assertSucceeds(updateDoc(doc(owner, path), { recipeTitle: '変更' }));
  await assertFails(updateDoc(doc(owner, path), { unexpected: true }));
  await assertFails(setDoc(doc(owner, 'artifacts/other/users/alice/history/recipe'), recipe));
  await assertFails(updateDoc(doc(owner, path), { recipeContent: 'x'.repeat(100001) }));
  await assertSucceeds(deleteDoc(doc(owner, path)));
});
