import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails } from '@firebase/rules-unit-testing';
import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, signInAnonymously, linkWithCredential,
  EmailAuthProvider, signInWithEmailAndPassword, signOut, reauthenticateWithCredential, deleteUser } from 'firebase/auth';
import { getFirestore, connectFirestoreEmulator, doc, setDoc, getDocFromServer, deleteDoc } from 'firebase/firestore';
let env;
const apps = [];
const recipe = { id: null, recipeTitle: '卵焼き', ingredientsWithUsage: '卵', allConstraints: '', recipeContent: '手順', timestamp: Date.now() };
function session(name) {
  const app = initializeApp({ apiKey: 'fake-api-key', projectId: 'demo-cookai' }, name);
  apps.push(app);
  const auth = getAuth(app);
  connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
  const db = getFirestore(app);
  connectFirestoreEmulator(db, '127.0.0.1', 8080);
  return { auth, db };
}
const history = (db, uid, id = 'recipe') => doc(db, `artifacts/liefantidia2/users/${uid}/history/${id}`);
before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-cookai', firestore: { rules: readFileSync('../../firestore.rules', 'utf8') } });
});
after(async () => { await Promise.all(apps.map(deleteApp)); await env?.cleanup(); });
test('upgrade preserves UID/history; another installation restores it with login', async () => {
  const first = session('registration');
  const user = (await signInAnonymously(first.auth)).user;
  const uid = user.uid;
  await setDoc(history(first.db, uid), recipe);
  const email = `restore-${Date.now()}@example.com`, password = 'Test-password-123!';
  await linkWithCredential(user, EmailAuthProvider.credential(email, password));
  assert.equal(first.auth.currentUser.uid, uid);
  assert.equal(first.auth.currentUser.isAnonymous, false);
  assert.deepEqual((await getDocFromServer(history(first.db, uid))).data(), recipe);
  const second = session('reinstalled');
  const guest = (await signInAnonymously(second.auth)).user;
  await assertFails(getDocFromServer(history(second.db, uid)));
  const restored = await signInWithEmailAndPassword(second.auth, email, password);
  assert.notEqual(guest.uid, restored.user.uid);
  assert.equal(restored.user.uid, uid);
  assert.deepEqual((await getDocFromServer(history(second.db, uid))).data(), recipe);
  await assert.rejects(signInWithEmailAndPassword(second.auth, email, 'wrong-password'));
  await reauthenticateWithCredential(restored.user, EmailAuthProvider.credential(email, password));
  await deleteDoc(history(second.db, uid));
  await deleteUser(restored.user);
  await assert.rejects(signInWithEmailAndPassword(second.auth, email, password));
});
test('guest history can be copied with separate authenticated sessions without cross-user permissions', async () => {
  const source = session('guest-source');
  const guest = (await signInAnonymously(source.auth)).user;
  await setDoc(history(source.db, guest.uid), recipe);
  const target = session('target-account');
  const targetUser = (await signInAnonymously(target.auth)).user;
  const email = `merge-${Date.now()}@example.com`, password = 'Test-password-123!';
  await linkWithCredential(targetUser, EmailAuthProvider.credential(email, password));
  await assertFails(getDocFromServer(history(source.db, targetUser.uid)));
  await assertFails(getDocFromServer(history(target.db, guest.uid)));
  const destination = history(target.db, targetUser.uid, `guest_${guest.uid}_recipe`);
  const data = (await getDocFromServer(history(source.db, guest.uid))).data();
  await setDoc(destination, data);
  await setDoc(destination, data);
  await deleteDoc(history(source.db, guest.uid));
  assert.equal((await getDocFromServer(history(source.db, guest.uid))).exists(), false);
  assert.deepEqual((await getDocFromServer(destination)).data(), recipe);
  await signOut(source.auth);
  await signInWithEmailAndPassword(source.auth, email, password);
  assert.deepEqual((await getDocFromServer(history(source.db, targetUser.uid, `guest_${guest.uid}_recipe`))).data(), recipe);
});
