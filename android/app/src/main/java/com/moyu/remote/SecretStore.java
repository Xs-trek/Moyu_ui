package com.moyu.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keystore-wrapped storage for long-lived credentials and the user-entered pairing draft. */
public final class SecretStore {
    private static final String ALIAS = "moyu-node-secrets-v1";
    private static final String PREFS = "moyu_secure_values";
    private final SharedPreferences preferences;

    SecretStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void put(String nodeId, String name, String value) {
        if (value == null || value.isEmpty()) return;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            ByteBuffer packed = ByteBuffer.allocate(4 + iv.length + encrypted.length);
            packed.putInt(iv.length).put(iv).put(encrypted);
            preferences.edit().putString(key(nodeId, name), Base64.encodeToString(packed.array(), Base64.NO_WRAP)).apply();
        } catch (Exception error) {
            throw new IllegalStateException("无法安全保存节点凭据", error);
        }
    }

    public synchronized String get(String nodeId, String name) {
        String encoded = preferences.getString(key(nodeId, name), null);
        if (encoded == null) return null;
        try {
            ByteBuffer packed = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
            int ivLength = packed.getInt();
            if (ivLength < 12 || ivLength > 32 || packed.remaining() <= ivLength) throw new IllegalStateException("bad secret envelope");
            byte[] iv = new byte[ivLength];
            packed.get(iv);
            byte[] encrypted = new byte[packed.remaining()];
            packed.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            return null;
        }
    }

    public boolean has(String nodeId, String name) { return preferences.contains(key(nodeId, name)) && get(nodeId, name) != null; }

    public synchronized void remove(String nodeId, String name) {
        preferences.edit().remove(key(nodeId, name)).apply();
    }

    public void deleteNode(String nodeId) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String name : new String[]{"token", "networkSecret"}) editor.remove(key(nodeId, name));
        editor.apply();
    }

    private String key(String nodeId, String name) { return nodeId + "." + name; }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
