package com.example.liefantidia2;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystoreを使用して暗号化/復号化を処理するヘルパークラス。
 * AES/GCM/NoPaddingモードを使用し、生体認証でキーを保護します。
 */
public class KeyStoreHelper {
    private static final String TAG = "KeyStoreHelper";
    // 鍵のエイリアス
    private static final String KEY_ALIAS = "BiometricKeyAlias";
    // Android Keystoreプロバイダー
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    // AES/GCMモードとパディングなし
    private static final String TRANSFORMATION = KeyProperties.KEY_ALGORITHM_AES + "/"
                                               + KeyProperties.BLOCK_MODE_GCM + "/"
                                               + KeyProperties.ENCRYPTION_PADDING_NONE;
    // GCM認証タグの長さ (ビット)
    private static final int GCM_TAG_LENGTH = 128;

    // Keystoreインスタンス
    private final KeyStore keyStore;
    private final Context context;

    /**
     * コンストラクタ。KeyStoreのインスタンスを取得し、ロードします。
     * 鍵が存在しない場合は、生体認証が必須の新しい鍵を生成します。
     * @param context アプリケーションコンテキスト
     */
    public KeyStoreHelper(Context context) {
        this.context = context;
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            // 鍵が存在しない場合は生成を試みる
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateNewKey();
            }
        } catch (Exception e) {
            Log.e(TAG, "KeyStore initialization failed: " + e.getMessage());
            throw new RuntimeException("KeyStore初期化エラー", e);
        }
    }

    /**
     * Keystoreから秘密鍵を取得します。
     * @return 秘密鍵 (SecretKey)
     * @throws KeyPermanentlyInvalidatedException 生体認証情報変更などでキーが無効化された場合
     * @throws KeyStoreException 鍵の取得中にエラーが発生した場合
     * @throws UnrecoverableKeyException 鍵の回復に失敗した場合
     * @throws NoSuchAlgorithmException アルゴリズムが見つからない場合
     */
    private SecretKey getSecretKey() 
            throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyPermanentlyInvalidatedException { // 💡 修正: KeyPermanentlyInvalidatedExceptionを追加
        try {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        } catch (KeyStoreException e) {
            // キーが無効化された可能性を検出
            if (e.getMessage() != null && e.getMessage().contains("Key user not authenticated")) {
                // 修正されたシグネチャにより、このthrowがコンパイル可能に
                throw new KeyPermanentlyInvalidatedException("Key permanently invalidated due to authentication change.", e);
            }
            throw e;
        }
    }

    /**
     * 生体認証を必要とする新しい鍵を生成し、Android Keystoreに保存します。
     * @throws Exception 鍵の生成中にエラーが発生した場合
     */
    private void generateNewKey() throws KeyStoreException, CertificateException, IOException, 
                                 NoSuchAlgorithmException, NoSuchProviderException, 
                                 InvalidAlgorithmParameterException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);

        // 鍵生成の仕様を定義
        KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true) // 暗号化ごとに新しいIVを要求
                .setKeySize(256)
                // 生体認証が必要な設定
                .setUserAuthenticationRequired(true)
                // 認証が必要なのは使用時のみ
                .setUserAuthenticationValidityDurationSeconds(-1) 
                .build();

        keyGenerator.init(keyGenParameterSpec);
        keyGenerator.generateKey();
        Log.i(TAG, "New Biometric Key generated successfully.");
    }

    /**
     * データを暗号化します。このCipherは生体認証を必要としません。
     * @param plainText 暗号化する平文のデータ (String)。
     * @return 暗号文とIVを格納したPreferencesHelper.EncryptedDataオブジェクト。
     * @throws Exception 暗号化中のエラー。
     */
    public PreferencesHelper.EncryptedData encryptData(String plainText) 
            throws NoSuchAlgorithmException, NoSuchPaddingException, 
                   InvalidKeyException, IllegalBlockSizeException, BadPaddingException,
                   UnrecoverableKeyException, KeyStoreException {
        // 💡 getSecretKey()はKeyPermanentlyInvalidatedExceptionをスローするようになったため、
        // ここでもそれを処理する必要があります。ここではRuntimeExceptionとしてラップします。
        SecretKey secretKey;
        try {
            secretKey = getSecretKey();
        } catch (KeyPermanentlyInvalidatedException e) {
            // KeyPermanentlyInvalidatedExceptionはチェック例外ですが、
            // encryptDataはBiometricPromptなしで呼ばれるため、ここではRuntime例外としてラップするのが適切です。
            throw new RuntimeException("Key is permanently invalid (auth change). Must regenerate key.", e);
        }

        // 暗号化用のCipherを設定し、鍵で初期化
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        byte[] dataToEncrypt = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = cipher.doFinal(dataToEncrypt);
        byte[] iv = cipher.getIV();

        return new PreferencesHelper.EncryptedData(encryptedBytes, iv);
    }

    /**
     * データを復号化します。このメソッドは、生体認証によって認証済みのCipherを受け取ります。
     * @param encryptedData 復号化するデータとIVを含むオブジェクト。
     * @param authenticatedCipher 生体認証が成功して得られたCipherオブジェクト。
     * @return 復号化された平文の文字列。
     * @throws Exception 復号化中のエラー。
     */
    public String decryptData(PreferencesHelper.EncryptedData encryptedData, Cipher authenticatedCipher) 
            throws InvalidAlgorithmParameterException, IllegalBlockSizeException, 
                   BadPaddingException, KeyPermanentlyInvalidatedException {

        try {
            // 認証済みCipherを生体認証に使用されたGCMParameterSpecで初期化
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.getIv());
            // 💡 修正: getSecretKey()がKeyPermanentlyInvalidatedExceptionをスローするようになったため、
            // ここで呼び出し元にKeyPermanentlyInvalidatedExceptionをそのままスローするように変更します。
            authenticatedCipher.init(Cipher.DECRYPT_MODE, getSecretKey(), gcmParameterSpec);

            byte[] decryptedBytes = authenticatedCipher.doFinal(encryptedData.getEncryptedBytes());
            return new String(decryptedBytes, StandardCharsets.UTF_8);

        } catch (InvalidKeyException | UnrecoverableKeyException | NoSuchAlgorithmException | KeyStoreException e) {
             // 鍵の無効化を適切に処理
            if (e.getMessage() != null && e.getMessage().contains("Key user not authenticated")) {
                // KeyPermanentlyInvalidatedExceptionをスローし、メソッドシグネチャに宣言済み
                throw new KeyPermanentlyInvalidatedException("Key permanently invalidated due to authentication change.", e);
            }
            Log.e(TAG, "Decryption error (Key issue): " + e.getMessage());
            return null;
        }
    }

    /**
     * 復号化のためにBiometricPromptに渡すためのCipherを準備します。
     * このCipherは、生体認証が成功した後にKeyStoreによって初期化されます。
     * @return 復号化前のCipherオブジェクト
     * @throws Exception Cipherの取得と初期化中のエラー
     */
    public Cipher getDecryptCipher() throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        // 💡 修正: getSecretKey()がKeyPermanentlyInvalidatedExceptionをスローするようになったため、
        // ここでそれを処理するように修正します。
        SecretKey secretKey;
        try {
            secretKey = getSecretKey();
        } catch (KeyPermanentlyInvalidatedException e) {
            // この例外は呼び出し元 (MainActivityなど) に伝播させるべきなので、
            // throws句に追加するか、ここでは単にExceptionとしてラップします。
            // 既存のthrows Exceptionを利用するため、ここではそのまま伝播させます。
            throw e; 
        }

        // 復号化モードで鍵のみを使って初期化（IVは認証後に設定される）
        // 認証後にKeyStoreHelper.decryptDataでGCMParameterSpecを設定して最終的な復号化を行う
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        return cipher;
    }

    /**
     * Keystoreから鍵を削除します。
     */
    public void deleteKeyAlias() {
        try {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
                Log.w(TAG, "Key alias deleted: " + KEY_ALIAS);
                // 削除後、すぐに新しいキーを生成
                generateNewKey(); 
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete key alias: " + e.getMessage());
        }
    }

    /**
     * 鍵のエイリアスがKeystoreに存在するかチェックします。
     * @return 存在すればtrue
     */
    public boolean isKeyAliasExist() {
        try {
            return keyStore.containsAlias(KEY_ALIAS);
        } catch (KeyStoreException e) {
            Log.e(TAG, "KeyStore check failed: " + e.getMessage());
            return false;
        }
    }
}