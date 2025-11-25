package com.example.liefantidia2;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

// Android KeyStoreを利用してAPIキーの暗号化/復号化に必要なキーとCipherを管理するヘルパークラス
public class KeyStoreHelper {
    private static final String TAG = "KeyStoreHelper";
    private static final String KEY_ALIAS = "GeminiApiKeyAlias";
    private static final String TRANSFORMATION = KeyProperties.KEY_ALGORITHM_AES + "/"
            + KeyProperties.BLOCK_MODE_GCM + "/"
            + KeyProperties.ENCRYPTION_PADDING_NONE;
    private static final int GCM_TAG_LENGTH = 128; // GCMのタグ長 (ビット)

    private final KeyStore keyStore;

    public KeyStoreHelper(Context context) {
        try {
            // Android KeyStoreのインスタンスを取得
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            createKeyIfNotFound(); // キーがなければ作成
        } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException("Failed to initialize KeyStoreHelper", e);
        }
    }

    // KeyStoreに暗号化キーが存在しない場合に新規作成する
    private void createKeyIfNotFound() throws KeyStoreException, NoSuchProviderException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            // AES/GCM/NoPaddingアルゴリズムのキー生成設定
            KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256) // キーサイズを明示 (推奨)
                    .setUserAuthenticationRequired(true) // 生体認証が必要
                    .setInvalidatedByBiometricEnrollment(true) // 生体認証情報が変更されたらキーを無効化
                    .build();

            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            keyGenerator.init(keyGenParameterSpec);
            keyGenerator.generateKey();
            Log.d(TAG, "New Key created in KeyStore: " + KEY_ALIAS);
        }
    }

    // データの暗号化
    // PreferencesHelper.EncryptedData を返す
    public PreferencesHelper.EncryptedData encryptData(String data) throws Exception {
        // Android KeyStoreからSecretKeyを取得 (暗号化では認証は不要)
        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);

        // 暗号化のためのCipherインスタンスの初期化
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        byte[] encryptedBytes = cipher.doFinal(data.getBytes("UTF-8"));
        byte[] iv = cipher.getIV(); // IV (初期化ベクトル) は復号化に必要なので保存

        return new PreferencesHelper.EncryptedData(encryptedBytes, iv);
    }

    /**
     * 復号化のために生体認証と連携させるCipherインスタンスを取得します。
     * このCipherをBiometricPrompt.CryptoObjectに渡します。
     * @return 復号モードで初期化されたCipher
     */
    public Cipher getDecryptCipher() throws NoSuchPaddingException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyStoreException {
        // Android KeyStoreからSecretKeyを取得
        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        
        // CipherをDECRYPT_MODEで初期化。GCMParameterSpecは復号化時にはまだ設定しない
        // BiometricPromptが認証に成功した後、CryptoObjectからCipherを取り出して使用する
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        try {
            // キーを使用して初期化 (ここで認証が必要であることを設定)
            cipher.init(Cipher.DECRYPT_MODE, secretKey); 
        } catch (Exception e) {
            // 例外処理が必要であれば追加
            throw new KeyStoreException("Failed to init cipher with secret key: " + e.getMessage(), e);
        }
        return cipher;
    }

    /**
     * 認証済みのCipherでデータの復号化を実行します。
     * @param encryptedData 暗号化されたデータとIV
     * @param authenticatedCipher 認証に成功したCryptoObjectから取得したCipher
     * @return 復号化された平文の文字列
     */
    public String decryptData(PreferencesHelper.EncryptedData encryptedData, Cipher authenticatedCipher) throws Exception {
        // 認証済みのCipherとIVを使用して復号化
        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.iv);

        // 認証済みのCipherにGCMParameterSpecを設定して最終的に初期化
        // Cipher.init()はキーが必要な操作を伴うため、認証済みCipherを再初期化する
        authenticatedCipher.init(Cipher.DECRYPT_MODE, authenticatedCipher.getKey(), gcmParameterSpec);

        byte[] decryptedBytes = authenticatedCipher.doFinal(encryptedData.encryptedBytes);
        return new String(decryptedBytes, "UTF-8");
    }

    // KeyStoreにエイリアスが存在するかチェック
    public boolean isKeyAliasExist() {
        try {
            return keyStore.containsAlias(KEY_ALIAS);
        } catch (KeyStoreException e) {
            Log.e(TAG, "KeyStore access error: " + e.getMessage());
            return false;
        }
    }

    // KeyStoreからキーを削除（再作成が必要な場合に備えて）
    public void deleteKeyAlias() {
        try {
            keyStore.deleteEntry(KEY_ALIAS);
            Log.d(TAG, "Key alias deleted from KeyStore.");
        } catch (KeyStoreException e) {
            Log.e(TAG, "Failed to delete key alias: " + e.getMessage());
        }
    }
}