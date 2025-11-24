package com.example.liefantidia;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

public class KeyStoreHelper {

    private static final String TAG = "KeyStoreHelper";
    // Keystoreに鍵を保存する際の固有のエイリアス（名前）
    private static final String KEY_ALIAS = "AIRecipeKey";
    
    // 暗号化に使用する設定
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String ENCRYPTION_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES;
    private static final String BLOCK_MODE = KeyProperties.BLOCK_MODE_CBC;
    private static final String PADDING = KeyProperties.ENCRYPTION_PADDING_PKCS7;
    private static final String TRANSFORMATION = ENCRYPTION_ALGORITHM + "/" + BLOCK_MODE + "/" + PADDING;

    private final KeyStore keyStore;
    private final Context context;

    public KeyStoreHelper(Context context) {
        this.context = context;
        try {
            // Android Keystore インスタンスを取得
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null); // KeyStoreをロード
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
            throw new RuntimeException("Failed to initialize KeyStore.", e);
        }
    }

    /**
     * 指定されたエイリアスの秘密鍵がKeystore内に存在するか確認する
     */
    public boolean isKeyExist() {
        try {
            return keyStore.containsAlias(KEY_ALIAS);
        } catch (KeyStoreException e) {
            Log.e(TAG, "Key existence check failed.", e);
            return false;
        }
    }

    /**
     * 新しい秘密鍵を生成し、Keystoreに保存する
     * 鍵の使用には生体認証を必須とする
     */
    public void generateNewKey() throws NoSuchAlgorithmException, NoSuchProviderException,
            InvalidAlgorithmParameterException {

        // KeyGeneratorでAESアルゴリズムを選択
        KeyGenerator keyGenerator = KeyGenerator.getInstance(ENCRYPTION_ALGORITHM, ANDROID_KEY_STORE);

        // KeyGenParameterSpecで鍵の生成パラメータを設定
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(BLOCK_MODE)
                .setEncryptionPaddings(PADDING)
                // ★最重要設定: 鍵の使用にユーザー認証（指紋/PIN）を必須にする
                .setUserAuthenticationRequired(true)
                // 認証有効期間の設定は省略（毎回認証が必要な設定）
                .build());

        keyGenerator.generateKey();
        Log.i(TAG, "New SecretKey generated in Android Keystore.");
    }

    /**
     * 平文データ（APIキー）を暗号化する
     * このメソッドはSettingsActivityでAPIキーを保存するときに使用される
     */
    public EncryptedData encryptData(String dataToEncrypt) throws Exception {
        if (!isKeyExist()) {
            // 鍵が存在しない場合は、まず生成する
            generateNewKey();
        }
        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        
        // 暗号化用のCipherを初期化（認証は不要）
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        // 暗号化を実行
        byte[] encryptedBytes = cipher.doFinal(dataToEncrypt.getBytes("UTF-8"));
        
        // 初期化ベクトル (IV) も復号化のために保存する必要がある
        byte[] iv = cipher.getIV();
        
        // Base64エンコードして、文字列として保存可能にする
        String encryptedDataString = Base64.encodeToString(encryptedBytes, Base64.DEFAULT);
        String ivString = Base64.encodeToString(iv, Base64.DEFAULT);

        return new EncryptedData(encryptedDataString, ivString);
    }
    
    /**
     * 復号化用に初期化されたCipherオブジェクトを取得する
     * MainActivityで生体認証を行う直前に呼び出される
     */
    public Cipher getDecryptCipher() throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, 
            InvalidKeyException, NoSuchProviderException {
        
        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        
        // Cipherを復号化モードで初期化。認証必須のため、この時点では復号化はできない。
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey); 
        // 実際の復号化は、BiometricPromptのCryptoObject経由で認証成功後に行われる

        return cipher;
    }

    /**
     * 暗号化されたデータとIVを使って復号化を実行する
     * このメソッドはBiometricPrompt認証成功後に呼び出される
     * @param encryptedDataString Base64でエンコードされた暗号化データ
     * @param ivString Base64でエンコードされたIV
     * @param cipher 認証に成功して使えるようになったCipherオブジェクト
     */
    public String decryptData(String encryptedDataString, String ivString, Cipher cipher) throws Exception {
        
        // 保存されたIVと暗号化データをデコード
        byte[] iv = Base64.decode(ivString, Base64.DEFAULT);
        byte[] encryptedBytes = Base64.decode(encryptedDataString, Base64.DEFAULT);

        // CipherにIVを設定して復号化
        cipher.init(Cipher.DECRYPT_MODE, keyStore.getKey(KEY_ALIAS, null), new IvParameterSpec(iv));
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

        return new String(decryptedBytes, "UTF-8");
    }

    /**
     * 暗号化データとIVを保持するための簡易クラス
     */
    public static class EncryptedData {
        public final String encryptedData;
        public final String iv;

        public EncryptedData(String encryptedData, String iv) {
            this.encryptedData = encryptedData;
            this.iv = iv;
        }
    }
}
