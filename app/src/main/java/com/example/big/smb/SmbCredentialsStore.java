package com.example.big.smb;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted, device-local logins. This preference file is excluded from backup and transfer. */
public final class SmbCredentialsStore {
    private static final String PREFERENCES = "smb_credentials";
    private static final String KEY_ALIAS = "com.example.big.smb.favorites.v1";
    private static final Object LOCK = new Object();
    private static final Set<String> BLOCKED_KEYS = new HashSet<>();
    private static final int VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int MAX_RECORD_LENGTH = 300000;
    private final SharedPreferences preferences;
    private final String keyAlias;

    public SmbCredentialsStore(Context context) {
        this(preferences(context), KEY_ALIAS);
    }

    SmbCredentialsStore(SharedPreferences preferences, String keyAlias) {
        this.preferences = preferences;
        this.keyAlias = keyAlias;
    }

    public boolean save(SmbLocation location, SmbSession session) {
        if (!isFolder(location) || session == null || !session.accepts(location)) {
            return false;
        }
        synchronized (LOCK) {
            byte[] plaintext = null;
            try (SecretBuffer buffer = new SecretBuffer()) {
                session.writeCredentials(new DataOutputStream(buffer));
                plaintext = buffer.toByteArray();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                // AndroidKeyStore generates a new, randomized IV for every encryption.
                cipher.init(Cipher.ENCRYPT_MODE, key(true));
                cipher.updateAAD(location.getUrl().getBytes(StandardCharsets.UTF_8));
                byte[] iv = cipher.getIV();
                if (iv == null || iv.length != IV_LENGTH) {
                    throw new GeneralSecurityException("Unsupported IV");
                }
                byte[] ciphertext = cipher.doFinal(plaintext);
                byte[] record = new byte[1 + IV_LENGTH + ciphertext.length];
                record[0] = VERSION;
                System.arraycopy(iv, 0, record, 1, IV_LENGTH);
                System.arraycopy(ciphertext, 0, record, 1 + IV_LENGTH, ciphertext.length);
                if (preferences != null && preferences.edit().putString(location.getUrl(),
                        Base64.encodeToString(record, Base64.NO_WRAP)).commit()) {
                    session.rememberCredentials(location);
                    return true;
                }
            } catch (GeneralSecurityException | IOException | RuntimeException ignored) {
                // Never fall back to plaintext, and never log credential or provider exceptions.
            } finally {
                if (plaintext != null) {
                    Arrays.fill(plaintext, (byte) 0);
                }
            }
            remove(location);
            return false;
        }
    }

    public SmbSession load(SmbLocation location) {
        if (!isFolder(location)) {
            return null;
        }
        synchronized (LOCK) {
            byte[] plaintext = null;
            try {
                String value = preferences == null ? null : preferences.getString(location.getUrl(), null);
                if (value == null) {
                    return null;
                }
                if (value.length() > MAX_RECORD_LENGTH) {
                    throw new IOException("Invalid record");
                }
                byte[] record = Base64.decode(value, Base64.NO_WRAP);
                if (record.length < 1 + IV_LENGTH + 16 || record[0] != VERSION) {
                    throw new IOException("Invalid record");
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(false),
                        new GCMParameterSpec(128, record, 1, IV_LENGTH));
                cipher.updateAAD(location.getUrl().getBytes(StandardCharsets.UTF_8));
                plaintext = cipher.doFinal(record, 1 + IV_LENGTH, record.length - 1 - IV_LENGTH);
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
                    SmbSession session = SmbSession.readCredentials(input, location);
                    if (input.available() != 0) {
                        throw new IOException("Invalid record");
                    }
                    return session;
                }
            } catch (GeneralSecurityException | IOException | RuntimeException ignored) {
                remove(location);
                return null;
            } finally {
                if (plaintext != null) {
                    Arrays.fill(plaintext, (byte) 0);
                }
            }
        }
    }

    public void remove(SmbLocation location) {
        synchronized (LOCK) {
            if (location == null) {
                return;
            }
            try {
                if (preferences != null && preferences.edit().remove(location.getUrl()).commit()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // Fall through to revoke the key if the old record cannot be deleted.
            }
            BLOCKED_KEYS.add(keyAlias);
            try {
                KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
                keys.load(null);
                keys.deleteEntry(keyAlias);
                BLOCKED_KEYS.remove(keyAlias);
            } catch (GeneralSecurityException | IOException | RuntimeException ignored) {
                // Refuse this key for the rest of the process if both storage services failed.
            }
        }
    }

    private SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        if (BLOCKED_KEYS.contains(keyAlias)) {
            throw new GeneralSecurityException("Key unavailable");
        }
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (keys.containsAlias(keyAlias)) {
            return (SecretKey) keys.getKey(keyAlias, null);
        }
        if (!create) {
            throw new GeneralSecurityException("Key unavailable");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static boolean isFolder(SmbLocation location) {
        return location != null && location.isDirectory() && !location.isServerRoot();
    }

    private static SharedPreferences preferences(Context context) {
        try {
            return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class SecretBuffer extends ByteArrayOutputStream {
        @Override
        public void close() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }
}