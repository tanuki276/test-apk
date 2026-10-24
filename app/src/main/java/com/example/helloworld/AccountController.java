package com.example.liefantidia2;

import android.content.Context;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;

/** App-scoped operations survive rotation without retaining an Activity or password on disk. */
public final class AccountController {
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
    private AccountController(Context context) {
        this.context = context;
        // Retire old locally stored credentials; the Firebase session remains valid for migration.
        context.getSharedPreferences("guest_recovery", Context.MODE_PRIVATE).edit().clear().apply();
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
                finish(task.isSuccessful() ? "登録しました。同じメールアドレスで履歴を引き継げます。" : error(task.getException())));
        } else if (user.isAnonymous()) {
            user.linkWithCredential(EmailAuthProvider.getCredential(email, password))
                .addOnCompleteListener(task -> finish(task.isSuccessful()
                    ? "登録しました。ゲストの履歴を引き継ぎました。再インストール後も同じメールアドレスでログインしてください。"
                    : error(task.getException())));
        } else if (isGuest(user)) {
            // Upgrade an old guest without discarding its currently authenticated session.
            destinationAuth().createUserWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
                if (!task.isSuccessful()) { finish(error(task.getException())); return; }
                completeLogin(user, task.getResult().getUser().getUid(), email, password);
            });
        } else finish("すでにログインしています。");
    }
    public void login(String email, String password) {
        if (!begin()) return;
        FirebaseUser guest = auth.getCurrentUser();
        if (guest != null && !isGuest(guest)) { finish("すでにログインしています。"); return; }
        // Validate the destination without losing the guest's access to their own history.
        destinationAuth().signInWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (!task.isSuccessful()) { finish(error(task.getException())); return; }
            completeLogin(guest, task.getResult().getUser().getUid(), email, password);
        });
    }
    private void completeLogin(FirebaseUser guest, String destinationUid, String email, String password) {
        Runnable switchAccount = () -> {
            boolean copied = new CookingPreferencesStore(context).copyIfMissing(
                guest == null ? null : guest.getUid(), destinationUid);
            auth.signInWithEmailAndPassword(email, password).addOnCompleteListener(result -> {
                if (!result.isSuccessful()) {
                    finish("ログインを完了できませんでした。同じメールアドレスで再試行してください。引き継ぎ済みの履歴は保存されています。"); return;
                }
                finish(copied ? "ログインしました。以前の履歴とゲストの履歴をまとめて利用できます。"
                    : "ログインしました。履歴を引き継ぎました。調理設定は設定画面で確認してください。");
            });
        };
        if (guest == null || guest.getUid().equals(destinationUid)) { switchAccount.run(); return; }
        migratePage(guest.getUid(), destinationUid, switchAccount);
    }
    private CollectionReference history(FirebaseFirestore db, String uid) {
        return db.collection("artifacts/liefantidia2/users/" + uid + "/history");
    }
    private void migratePage(String sourceUid, String destinationUid, Runnable done) {
        FirebaseFirestore sourceDb = FirebaseFirestore.getInstance();
        FirebaseFirestore targetDb = FirebaseFirestore.getInstance(restoreApp);
        history(sourceDb, sourceUid).limit(200).get(Source.SERVER).addOnCompleteListener(task -> {
            if (!task.isSuccessful()) { migrationFailed(); return; }
            QuerySnapshot page = task.getResult();
            if (page.isEmpty()) { done.run(); return; }
            WriteBatch copies = targetDb.batch();
            for (DocumentSnapshot document : page) {
                // Deterministic IDs make a retry after interruption idempotent.
                copies.set(history(targetDb, destinationUid).document(
                    "guest_" + sourceUid + "_" + document.getId()), document.getData());
            }
            copies.commit().addOnCompleteListener(copied -> {
                if (!copied.isSuccessful()) { migrationFailed(); return; }
                // Delete only after the destination acknowledges all writes.
                WriteBatch deletes = sourceDb.batch();
                for (DocumentSnapshot document : page) deletes.delete(document.getReference());
                deletes.commit().addOnCompleteListener(deleted -> {
                    if (!deleted.isSuccessful()) { migrationFailed(); return; }
                    migratePage(sourceUid, destinationUid, done);
                });
            });
        });
    }
    private void migrationFailed() {
        if (restoreApp != null) FirebaseAuth.getInstance(restoreApp).signOut();
        state.setValue(new State(false,
            "ログイン情報は確認できましたが、ゲスト履歴の引き継ぎに失敗しました。通信を確認して、同じメールアドレスでログインを再試行してください。履歴を失わないようゲストのままにしています。", true));
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
        if (user == null || user.isAnonymous() || user.getEmail() == null) {
            finish("ログインし直してお試しください。"); return;
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
                    }
                    else deletionFailed();
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
    private String error(Exception error) {
        if (error instanceof FirebaseNetworkException) return "通信できません。接続を確認してもう一度お試しください。";
        if (error instanceof FirebaseTooManyRequestsException) return "試行回数が多いため、少し待ってからお試しください。";
        if (error instanceof FirebaseAuthUserCollisionException) return "このメールアドレスは登録済みです。ログインするか、別のメールアドレスをお使いください。";
        if (error instanceof FirebaseAuthWeakPasswordException) return "より強いパスワードを設定してください。";
        return "手続きを完了できません。メールアドレスとパスワードを確認してもう一度お試しください。";
    }
}
