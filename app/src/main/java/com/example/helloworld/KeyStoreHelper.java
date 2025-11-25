package com.example.liefantidia2;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.BadPaddingException;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystoreを使用して暗号化/復号化を処理するヘルパークラス。
 * AES/GCM/NoPaddingモードを使用します。
 */
public class KeyStoreHelper {
    // 鍵のエイリアス
    private static final String KEY_ALIAS = "MyKeyAlias";
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

    /**
     * コンストラクタ。KeyStoreのインスタンスを取得し、ロードします。
     * @throws Exception KeyStoreのロード中にエラーが発生した場合
     */
    public KeyStoreHelper() throws Exception {
        keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
    }

    /**
     * Keystoreから秘密鍵を取得します。
     * @return 秘密鍵 (SecretKey)
     * @throws Exception 鍵の取得中にエラーが発生した場合
     */
    private SecretKey getSecretKey() throws Exception {
        Key key = keyStore.getKey(KEY_ALIAS, null);
        if (key == null || !(key instanceof SecretKey)) {
             throw new KeyStoreException("Key could not be retrieved from Keystore or is not a SecretKey.");
        }
        return (SecretKey) key;
    }

    /**
     * 新しい鍵を生成し、Android Keystoreに保存します。
     * 鍵が既に存在する場合は、新しい鍵は生成されません。
     * @throws Exception 鍵の生成中にエラーが発生した場合
     */
    public void generateNewKey() throws Exception {
        // 鍵が既に存在するかどうかを確認
        if (keyStore.containsAlias(KEY_ALIAS)) {
            System.out.println("Key already exists. Skipping generation.");
            return;
        }
        
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        
        KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true) // 暗号化ごとに新しいIVを要求
                .setKeySize(256) // 推奨される鍵サイズ
                .build();
        
        keyGenerator.init(keyGenParameterSpec);
        keyGenerator.generateKey();
        System.out.println("New key generated successfully.");
    }
    
    /**
     * データを暗号化します。
     * @param data 暗号化する平文のデータ。
     * @return 暗号文とIVを格納したEncryptedDataオブジェクト。
     * @throws Exception 暗号化中のエラー。
     */
    public EncryptedData encrypt(byte[] data) throws Exception {
        // 1. Keystoreから秘密鍵を取得します。
        SecretKey secretKey = getSecretKey();

        // 2. 暗号化用のCipherを設定します。
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        
        // 3. Cipherを初期化します。GCMモードでは、ランダムなIVが自動的に生成されます。
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        
        // 4. 暗号化を実行します。
        byte[] encryptedData = cipher.doFinal(data);
        
        // 5. 生成されたIVを取得します。これは復号化に必須です。
        byte[] iv = cipher.getIV();
        
        // 6. 暗号文とIVを返します。
        return new EncryptedData(encryptedData, iv);
    }

    /**
     * データを復号化します。
     * @param encryptedData 復号化するデータ。
     * @param iv 暗号化時に使用された初期化ベクトル (IV)。
     * @return 復号化されたバイト配列（平文）。
     * @throws Exception 復号化中のエラー（BadPaddingExceptionやKeyStoreExceptionなど）。
     */
    public byte[] decrypt(byte[] encryptedData, byte[] iv) 
            throws Exception {

        // 1. Keystoreから秘密鍵を正しく取得します。
        SecretKey secretKey = getSecretKey();
        
        // 2. Cipherを設定します。
        Cipher authenticatedCipher = Cipher.getInstance(TRANSFORMATION);
        
        // 3. 復号化のためにGCMParameterSpecを使用してIVとタグ長を設定します。
        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        
        // 4. Cipherを秘密鍵とIVで初期化します。
        //    これが元のコードでエラーが発生していた箇所（Cipher.getKey()は存在しない）の修正版です。
        authenticatedCipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec);
        
        // 5. 復号化を実行します。
        return authenticatedCipher.doFinal(encryptedData);
    }
    
    /**
     * 暗号文とそのIVを保持するための単純なデータ構造。
     */
    public static class EncryptedData {
        private final byte[] encryptedBytes;
        private final byte[] iv;

        public EncryptedData(byte[] encryptedBytes, byte[] iv) {
            this.encryptedBytes = encryptedBytes;
            this.iv = iv;
        }

        public byte[] getEncryptedBytes() {
            return encryptedBytes;
        }

        public byte[] getIv() {
            return iv;
        }
    }
}