package com.example.liefantidia2;

import android.content.Context;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;

import java.util.HashMap;
import java.util.Map;

/** App-scoped operations survive rotation without retaining an Activity or password on disk. */
public final class AccountController {
    private static final String TAG = "AccountController";

    public static final class State {
        public final boolean busy;
        public final String message;
        public final boolean migrationBlocked;
        State(boolean busy, String message) { this(busy, message, false); }
        State(boolean busy, String message, boolean migrationBlocked) {
            this.busy = busy; this.message = message; this.migrationBlocked = migrationBlocked;
        }
    }

    private static AccountController instance;
    public static synchronized AccountController get(Context context) {
        if (instance == null) instance = new AccountController(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(false, ""));
    private FirebaseApp restoreApp;
    private final PendingHistoryStore pending;

    private AccountController(Context context) {
        this.context = context;
        pending = new PendingHistoryStore(context);
    }

    public LiveData<State> state() { return state; }
    public boolean busy() { return state.getValue() != null && state.getValue().busy; }

    private boolean begin() {
        if (busy()) return false;
        state.setValue(new State(true, "処理しています。通信が終わるまでお待ちください。"));
        return true;
    }

    private void finish(String message) {
        if (restoreApp != null) FirebaseAuth.getInstance(restoreApp).signOut();
        state.setValue(new State(false, message));
    }

    /** Older installations may have linked a generated credential. Keep their history accessible. */
    public static boolean isGuest(FirebaseUser user) {
        return user != null && (user.isAnonymous() || (user.getEmail() != null
            && user.getEmail().endsWith("@guest.cookai.invalid")));
    }

    private FirebaseAuth destinationAuth() {
        if (restoreApp == null) {
            for (FirebaseApp app : FirebaseApp.getApps(context)) {
                if (app.getName().equals("account-restore")) restoreApp = app;
            }
            if (restoreApp == null) restoreApp = FirebaseApp.initializeApp(context,
                FirebaseApp.getInstance().getOptions(), "account-restore");
        }
        return FirebaseAuth.getInstance(restoreApp);
    }

    public void register(String email, String password) {
        if (!begin()) return;
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener(task ->
                finish(task.isSuccessful()
                    ? "登録しました。同じメールアドレスで再ログインできます。"
                    : error(task.getException())));
        } else if (user.isAnonymous()) {
            // Linking keeps the same UID, so the current guest history remains the account history.
            user.linkWithCredential(EmailAuthProvider.getCredential(email, password))
                .addOnCompleteListener(task -> finish(task.isSuccessful()
                    ? "登録しました。今のゲスト履歴をそのまま引き継ぎました。再インストール後も同じメールアドレスでログインできます。"
                    : error(task.getException())));
        } else if (isGuest(user)) {
            destinationAuth().createUserWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
                if (!task.isSuccessful()) { finish(error(task.getException())); return; }
                completeLogin(user, task.getResult().getUser().getUid(), email, password);
            });
        } else {
            finish("すでにログインしています。");
        }
    }

    public void login(String email, String password) {
        if (!begin()) return;
        FirebaseUser guest = auth.getCurrentUser();
        if (guest != null && !isGuest(guest)) { finish("すでにログインしています。"); return; }

        // Authenticate the destination with a second FirebaseApp so a failed login or migration never
        // destroys the current guest session or its history.
        destinationAuth().signInWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null || task.getResult().getUser() == null) {
                finish(error(task.getException()));
                return;
            }
            completeLogin(guest, task.getResult().getUser().getUid(), email, password);
        });
    }

    private void completeLogin(FirebaseUser guest, String destinationUid, String email, String password) {
        Runnable switchAccount = () -> {
            boolean copied = new CookingPreferencesStore(context).copyIfMissing(
                guest == null ? null : guest.getUid(), destinationUid);
            auth.signInWithEmailAndPassword(email, password).addOnCompleteListener(result -> {
                if (!result.isSuccessful()) {
                    finish("ゲスト履歴を端末に退避しましたが、ログイン状態の切り替えに失敗しました。同じメールアドレスで再度ログインしてください。");
                    return;
                }
                importPending(destinationUid, copied
                    ? "ログインしました。以前の履歴と今回のゲスト履歴をまとめて利用できます。"
                    : "ログインしました。履歴を引き継ぎました。調理設定は設定画面で確認してください。");
            });
        };
        if (guest == null || guest.getUid().equals(destinationUid)) {
            switchAccount.run();
            return;
        }
        migratePage(guest.getUid(), destinationUid, null, switchAccount);
    }

    private CollectionReference history(FirebaseFirestore db, String uid) {
        return db.collection("artifacts/liefantidia2/users/" + uid + "/history");
    }

    /**
     * Snapshot guest history durably before switching authentication. The original cloud copy stays
     * intact; stable destination IDs keep retries idempotent, including after process death.
     */
    private void migratePage(String sourceUid, String destinationUid, DocumentSnapshot cursor, Runnable done) {
        FirebaseFirestore sourceDb = FirebaseFirestore.getInstance();
        Query query = history(sourceDb, sourceUid).orderBy(FieldPath.documentId()).limit(200);
        if (cursor != null) query = query.startAfter(cursor);
        query.get(Source.SERVER).addOnCompleteListener(task -> {
            if (!task.isSuccessful()) { migrationFailed(task.getException()); return; }
            QuerySnapshot page = task.getResult();
            if (page == null || page.isEmpty()) { done.run(); return; }

            try {
                java.util.List<PendingHistoryStore.Entry> entries = new java.util.ArrayList<>();
                for (DocumentSnapshot document : page) {
                    entries.add(new PendingHistoryStore.Entry(
                        HistoryMigration.destinationId(sourceUid, document.getId()), migrationData(document)));
                }
                pending.putAll(destinationUid, entries);
            } catch (RuntimeException failure) { migrationFailed(failure); return; }
            DocumentSnapshot last = page.getDocuments().get(page.size() - 1);
            migratePage(sourceUid, destinationUid, last, done);
        });
    }

    /** Keep only fields accepted by the current Firestore rules when old-version history is migrated. */
    private Map<String, Object> migrationData(DocumentSnapshot document) {
        Map<String, Object> data = new HashMap<>(document.getData());
        Object timestamp = data.get("timestamp");
        if (timestamp instanceof com.google.firebase.Timestamp) {
            data.put("timestamp", ((com.google.firebase.Timestamp) timestamp).toDate().getTime());
        } else if (timestamp instanceof java.util.Date) {
            data.put("timestamp", ((java.util.Date) timestamp).getTime());
        }
        return HistoryMigration.normalize(data, System.currentTimeMillis());
    }

    public boolean hasPendingHistory() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null && pending.hasPending(user.getUid());
    }

    public void retryHistory() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || isGuest(user) || !begin()) return;
        importPending(user.getUid(), "ゲスト履歴の同期が完了しました。");
    }

    private void importPending(String uid, String success) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || !uid.equals(user.getUid())) { finish("同じアカウントでログインして履歴の同期を再試行してください。"); return; }
        final java.util.List<PendingHistoryStore.Entry> page;
        try { page = pending.page(uid); }
        catch (RuntimeException failure) { pendingFailed(failure); return; }
        if (page.isEmpty()) { finish(success); return; }
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch batch = db.batch();
        for (PendingHistoryStore.Entry entry : page) batch.set(history(db, uid).document(entry.id), entry.data);
        batch.commit().addOnCompleteListener(result -> {
            if (!result.isSuccessful()) { pendingFailed(result.getException()); return; }
            try { pending.remove(uid, page); }
            catch (RuntimeException failure) { pendingFailed(failure); return; }
            importPending(uid, success);
        });
    }

    private void pendingFailed(Exception failure) {
        Log.e(TAG, "History outbox upload failed", failure);
        finish("ログインしました。以前のアカウント履歴は履歴画面で確認できます。今回のゲスト履歴は端末に保管しています。設定の「未同期のゲスト履歴を再送」で同期してください。同期が終わるまでアプリを削除しないでください。");
    }

    private void migrationFailed(Exception exception) {
        Log.e(TAG, "Guest history migration failed", exception);
        if (restoreApp != null) FirebaseAuth.getInstance(restoreApp).signOut();
        String detail = "通信を確認して、同じメールアドレスでログインを再試行してください。";
        if (exception instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException firestore = (FirebaseFirestoreException) exception;
            if (firestore.getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                detail = "履歴の保存権限を確認できませんでした。アプリを最新版にしてから再試行してください。";
            }
        }
        state.setValue(new State(false,
            "ログイン情報は確認できましたが、ゲスト履歴の引き継ぎに失敗しました。" + detail + " ゲスト履歴はこのまま残しています。",
            true));
    }

    public void resetPassword(String email) {
        if (!begin()) return;
        auth.sendPasswordResetEmail(email).addOnCompleteListener(task -> finish(task.isSuccessful()
            ? "登録されている場合、パスワード再設定メールが届きます。迷惑メールもご確認ください。"
            : error(task.getException())));
    }

    public void logout() {
        if (busy()) return;
        auth.signOut();
        finish("ログアウトしました。履歴はアカウントに保存されています。");
    }

    public void deleteAccount(String password) {
        if (!begin()) return;
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || isGuest(user) || user.getEmail() == null) {
            finish("ログインし直してお試しください。");
            return;
        }
        if (pending.hasPending(user.getUid())) {
            finish("未同期のゲスト履歴があります。先に「未同期のゲスト履歴を再送」を実行してください。");
            return;
        }
        user.reauthenticate(EmailAuthProvider.getCredential(user.getEmail(), password))
            .addOnCompleteListener(task -> {
                if (!task.isSuccessful()) { finish(error(task.getException())); return; }
                deletePage(user);
            });
    }

    private void deletePage(FirebaseUser user) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        history(db, user.getUid()).limit(200).get(Source.SERVER).addOnCompleteListener(task -> {
            if (!task.isSuccessful()) { deletionFailed(); return; }
            if (task.getResult().isEmpty()) {
                user.delete().addOnCompleteListener(deleted -> {
                    if (deleted.isSuccessful()) {
                        new CookingPreferencesStore(context).delete(user.getUid());
                        finish("アカウントと履歴を削除しました。");
                    } else deletionFailed();
                });
                return;
            }
            WriteBatch batch = db.batch();
            for (DocumentSnapshot document : task.getResult()) batch.delete(document.getReference());
            batch.commit().addOnCompleteListener(deleted -> {
                if (deleted.isSuccessful()) deletePage(user); else deletionFailed();
            });
        });
    }

    private void deletionFailed() {
        finish("削除を完了できませんでした。一部の履歴は削除済みの場合があります。通信を確認し、アカウント削除を再試行してください。");
    }

    private String error(Exception exception) {
        if (exception instanceof FirebaseNetworkException) {
            return "通信できません。接続を確認してもう一度お試しください。";
        }
        if (exception instanceof FirebaseTooManyRequestsException) {
            return "試行回数が多いため、少し待ってからお試しください。";
        }
        if (exception instanceof FirebaseAuthUserCollisionException) {
            return "このメールアドレスは登録済みです。「ログインして履歴を引き継ぐ」を選んでください。";
        }
        if (exception instanceof FirebaseAuthWeakPasswordException) {
            return "パスワードが弱すぎます。より長いパスワードを設定してください。";
        }
        if (exception instanceof FirebaseAuthInvalidUserException) {
            return "このメールアドレスのアカウントが見つからないか、無効になっています。新規登録したメールアドレスか確認してください。";
        }
        if (exception instanceof FirebaseAuthInvalidCredentialsException) {
            return "メールアドレスまたはパスワードが正しくありません。パスワードを忘れた場合は再設定してください。";
        }
        if (exception instanceof FirebaseAuthException) {
            String code = ((FirebaseAuthException) exception).getErrorCode();
            if ("ERROR_OPERATION_NOT_ALLOWED".equals(code)) {
                return "Firebaseでメール/パスワードログインが有効になっていません。Authenticationのログイン方法を確認してください。";
            }
            Log.e(TAG, "Firebase Auth failed: " + code, exception);
        }
        return "ログイン処理を完了できませんでした。通信状態とメールアドレス・パスワードを確認して再試行してください。";
    }
}
