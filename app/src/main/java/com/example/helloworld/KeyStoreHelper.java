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
    // 鍵生成のエラーを避けるため、今回は長めに設定するが、通常は0秒が推奨される。
    private static final int AUTH_DURATION_SECONDS = 3600; // 1時間

    private final KeyStore keyStore;
    private final Context context;

    public KeyStoreHelper(Context context) {
        this.context = context;
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
            // 初期化失敗は致命的
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

    /**
     * 新しい秘密鍵を生成し、Keystoreに保存する
     * 鍵の復号（利用）には生体認証を必須とする
     */
    public void generateNewKey() throws NoSuchAlgorithmException, NoSuchProviderException,
            InvalidAlgorithmParameterException {

        KeyGenerator keyGenerator = KeyGenerator.getInstance(ENCRYPTION_ALGORITHM, ANDROID_KEY_STORE);

        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(BLOCK_MODE)
                .setEncryptionPaddings(PADDING)
                // 【★重要修正点 1】: 復号（DECRYPT）時のみユーザー認証を必須にする
                .setUserAuthenticationRequired(true)
                // 【★重要修正点 2】: 認証の有効期間を設定する (鍵生成エラー回避のため)
                .setUserAuthenticationValidityDurationSeconds(AUTH_DURATION_SECONDS) 
                .build());

        keyGenerator.generateKey();
        Log.i(TAG, "New SecretKey generated in Android Keystore.");
    }

    /**
     * 平文データ（APIキー）を暗号化する
     * この暗号化処理自体は認証不要で実行可能
     */
    public EncryptedData encryptData(String dataToEncrypt) throws Exception {
        // NOTE: isKeyExist()がtrueでも、generateNewKey()はSettingsActivity側で呼ばれるべき
        if (!isKeyExist()) {
            // 鍵が存在しない場合は、ここで生成処理を呼び出す
            generateNewKey();
        }
        
        // 鍵の取得。setUserAuthenticationRequired(true)でも暗号化時には認証は不要。
        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);

        // 暗号化用のCipherを初期化
        Cipher cipher = Cipher.getInstance(TRANSFORMATION); 
        cipher.init(Cipher.ENCRYPT_MODE, secretKey); // 暗号化モードで初期化

        byte[] encryptedBytes = cipher.doFinal(dataToEncrypt.getBytes("UTF-8"));
        byte[] iv = cipher.getIV();

        String encryptedDataString = Base64.encodeToString(encryptedBytes, Base64.DEFAULT);
        String ivString = Base64.encodeToString(iv, Base64.DEFAULT);

        return new EncryptedData(encryptedDataString, ivString);
    }

    /**
     * 復号化用に初期化されたCipherオブジェクトを取得する
     * このCipherは、生体認証（BiometricPrompt）のCryptoObjectとして使用される
     */
    public Cipher getDecryptCipher() throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, 
            InvalidKeyException, NoSuchProviderException, NoSuchPaddingException {

        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);

        // Cipherを復号化モードで初期化。認証必須のため、認証成功前は使用できない。
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey); 
        
        return cipher;
    }

    /**
     * 暗号化されたデータとIVを使って復号化を実行する
     * @param cipher 認証に成功して使えるようになったCipherオブジェクト
     */
    public String decryptData(String encryptedDataString, String ivString, Cipher cipher) throws Exception {

        byte[] iv = Base64.decode(ivString, Base64.DEFAULT);
        byte[] encryptedBytes = Base64.decode(encryptedDataString, Base64.DEFAULT);

        // BiometricPromptから渡されたCipherはすでに初期化されているため、
        // IVを設定して再度初期化する必要がある。
        // NOTE: cipher.init() は、BiometricPromptから渡されたCipherのキーを再利用する。
        cipher.init(Cipher.DECRYPT_MODE, cipher.getKey(), new IvParameterSpec(iv)); 
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