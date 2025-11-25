package com.example.liefantidia2;

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
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

public class KeyStoreHelper {

    private static final String TAG = "KeyStoreHelper";
    private static final String KEY_ALIAS = "AIRecipeKey";

    // 暗号化に使用する設定
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String ENCRYPTION_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES;
    private static final String BLOCK_MODE = KeyProperties.BLOCK_MODE_CBC;
    private static final String PADDING = KeyProperties.ENCRYPTION_PADDING_PKCS7;
    private static final String TRANSFORMATION = ENCRYPTION_ALGORITHM + "/" + BLOCK_MODE + "/" + PADDING;

    // 認証が有効な時間（秒）。0秒は「常に認証が必要」。
    private static final int AUTH_DURATION_SECONDS = 3600; 

    private final KeyStore keyStore;
    private final Context context;

    public KeyStoreHelper(Context context) {
        this.context = context;
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
            throw new RuntimeException("Failed to initialize KeyStore.", e);
        }
    }

    public boolean isKeyExist() {
        try {
            return keyStore.containsAlias(KEY_ALIAS);
        } catch (KeyStoreException e) {
            Log.e(TAG, "Key existence check failed.", e);
            return false;
        }
    }

    public void generateNewKey() throws NoSuchAlgorithmException, NoSuchProviderException,
            InvalidAlgorithmParameterException {

        KeyGenerator keyGenerator = KeyGenerator.getInstance(ENCRYPTION_ALGORITHM, ANDROID_KEY_STORE);

        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(BLOCK_MODE)
                .setEncryptionPaddings(PADDING)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(AUTH_DURATION_SECONDS) 
                .build());

        keyGenerator.generateKey();
        Log.i(TAG, "New SecretKey generated in Android Keystore.");
    }

    public EncryptedData encryptData(String dataToEncrypt) throws Exception {
        if (!isKeyExist()) {
            generateNewKey();
        }
        
        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION); 
        cipher.init(Cipher.ENCRYPT_MODE, secretKey); 

        byte[] encryptedBytes = cipher.doFinal(dataToEncrypt.getBytes("UTF-8"));
        byte[] iv = cipher.getIV();

        String encryptedDataString = Base64.encodeToString(encryptedBytes, Base64.DEFAULT);
        String ivString = Base64.encodeToString(iv, Base64.DEFAULT);

        return new EncryptedData(encryptedDataString, ivString);
    }

    /**
     * 復号化用に初期化されたCipherオブジェクトを取得する（IVなし）
     * BiometricPromptのCryptoObjectとして使用される。認証成功後、このCipherが利用可能になる。
     */
    public Cipher getDecryptCipher() throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, 
            InvalidKeyException, NoSuchProviderException, NoSuchPaddingException {

        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);

        // NOTE: ここではIVは不要。認証が成功し、このCipherが復号化に使用可能になった後、
        // decryptData()メソッド内でIVを使って最終的な復号化を実行します。
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey); 
        
        return cipher;
    }

    /**
     * 暗号化されたデータとIVを使って復号化を実行する
     * * NOTE: BiometricPromptから渡されるCipherは使用せず、新たにCipherを初期化することで、
     * IV required... のエラーを回避します。
     * @param encryptedDataString 暗号化されたAPIキー (Base64)
     * @param ivString 初期化ベクトル (Base64)
     */
    public String decryptData(String encryptedDataString, String ivString) throws Exception {

        byte[] iv = Base64.decode(ivString, Base64.DEFAULT);
        byte[] encryptedBytes = Base64.decode(encryptedDataString, Base64.DEFAULT);

        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);

        // 【★重要修正点】: BiometricPromptの認証完了後に、新しいCipherインスタンスを作成し、
        // 鍵とIVParameterSpecを使って正しく初期化する。
        Cipher cipher = Cipher.getInstance(TRANSFORMATION); 
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv)); 
        
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

        return new String(decryptedBytes, "UTF-8");
    }

    public static class EncryptedData {
        public final String encryptedData;
        public final String iv;

        public EncryptedData(String encryptedData, String iv) {
            this.encryptedData = encryptedData;
            this.iv = iv;
        }
    }
}