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
     */
    private SecretKey getSecretKey() 
            throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyPermanentlyInvalidatedException {
        try {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        } catch (KeyStoreException e) {
            // キーが無効化された可能性を検出
            if (e.getMessage() != null && e.getMessage().contains("Key user not authenticated")) {
                throw new KeyPermanentlyInvalidatedException("Key permanently invalidated due to authentication change.", e);
            }
            throw e;
        }
    }

    /**
     * 生体認証を必要とする新しい鍵を生成し、Android Keystoreに保存します。
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
     */
    public PreferencesHelper.EncryptedData encryptData(String plainText) 
            throws NoSuchAlgorithmException, NoSuchPaddingException, 
                   InvalidKeyException, IllegalBlockSizeException, BadPaddingException,
                   UnrecoverableKeyException, KeyStoreException {
        
        SecretKey secretKey;
        try {
            secretKey = getSecretKey();
        } catch (KeyPermanentlyInvalidatedException e) {
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
     */
    public String decryptData(PreferencesHelper.EncryptedData encryptedData, Cipher authenticatedCipher) 
            throws InvalidAlgorithmParameterException, IllegalBlockSizeException, 
                   BadPaddingException, KeyPermanentlyInvalidatedException {

        try {
            byte[] decryptedBytes = authenticatedCipher.doFinal(encryptedData.getEncryptedBytes());
            return new String(decryptedBytes, StandardCharsets.UTF_8);

        } catch (IllegalBlockSizeException | BadPaddingException e) {
             // 認証されたCipherでもエラーが出ることがあるためログ出力
            Log.e(TAG, "Decryption execution failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 【重要修正】復号化のためにCipherを準備します。
     * 必ずIV（初期化ベクトル）を受け取り、それを使ってCipherを初期化する必要があります。
     * これがないと "IV required when decrypting" エラーになります。
     * * @param iv 暗号化時に保存されたIV
     */
    public Cipher getDecryptCipher(byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        SecretKey secretKey;
        try {
            secretKey = getSecretKey();
        } catch (KeyPermanentlyInvalidatedException e) {
            throw e; 
        }

        // 復号化モードで、鍵とIVを使って初期化
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        
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

    public boolean isKeyAliasExist() {
        try {
            return keyStore.containsAlias(KEY_ALIAS);
        } catch (KeyStoreException e) {
            Log.e(TAG, "KeyStore check failed: " + e.getMessage());
            return false;
        }
    }
}