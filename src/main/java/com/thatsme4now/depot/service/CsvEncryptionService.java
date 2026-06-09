package com.thatsme4now.depot.service;

import org.springframework.stereotype.Service;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

/**
 * AES-256-GCM encryption/decryption for CSV export files.
 *
 * File format (binary):
 *   [4 bytes magic "DPTV"]
 *   [1 byte version = 0x01]
 *   [16 bytes salt  (PBKDF2)]
 *   [12 bytes IV    (GCM nonce)]
 *   [N bytes ciphertext + 16-byte GCM auth tag]
 *
 * Key derivation: PBKDF2WithHmacSHA256, 310_000 iterations, 256-bit key.
 */
@Service
public class CsvEncryptionService {

    private static final byte[] MAGIC   = {'D', 'P', 'T', 'V'};
    private static final byte   VERSION = 0x01;

    private static final int SALT_LEN   = 16;
    private static final int IV_LEN     = 12;
    private static final int TAG_LEN    = 128; // bits
    private static final int PBKDF2_IT  = 310_000;
    private static final int KEY_LEN    = 256; // bits

    // ── Encrypt ───────────────────────────────────────────────────────────────

    /**
     * Encrypts UTF-8 CSV bytes with the given password.
     *
     * @param csvBytes  raw CSV content
     * @param password  user-supplied password (non-null, non-empty)
     * @return encrypted byte array ready to write to .enc file
     */
    public byte[] encrypt(byte[] csvBytes, String password) {
        try {
            byte[] salt = randomBytes(SALT_LEN);
            byte[] iv   = randomBytes(IV_LEN);

            SecretKey key = deriveKey(password.toCharArray(), salt);
            Cipher cipher = buildCipher(Cipher.ENCRYPT_MODE, key, iv);
            byte[] ciphertext = cipher.doFinal(csvBytes);

            // Assemble: magic + version + salt + iv + ciphertext(+tag)
            byte[] out = new byte[MAGIC.length + 1 + SALT_LEN + IV_LEN + ciphertext.length];
            int pos = 0;
            System.arraycopy(MAGIC,      0, out, pos, MAGIC.length);   pos += MAGIC.length;
            out[pos++] = VERSION;
            System.arraycopy(salt,       0, out, pos, SALT_LEN);       pos += SALT_LEN;
            System.arraycopy(iv,         0, out, pos, IV_LEN);         pos += IV_LEN;
            System.arraycopy(ciphertext, 0, out, pos, ciphertext.length);
            return out;

        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Encryption failed: " + e.getMessage(), e);
        }
    }

    // ── Decrypt ───────────────────────────────────────────────────────────────

    /**
     * Decrypts an .enc file back to UTF-8 CSV bytes.
     *
     * @param encBytes  raw bytes of the .enc file
     * @param password  user-supplied password
     * @return decrypted CSV bytes
     * @throws EncryptionException if magic/version mismatch, wrong password, or tampered data
     */
    public byte[] decrypt(byte[] encBytes, String password) {
        try {
            int minLen = MAGIC.length + 1 + SALT_LEN + IV_LEN + TAG_LEN / 8;
            if (encBytes.length < minLen) {
                throw new EncryptionException("File too short — not a valid .enc export.");
            }

            int pos = 0;

            // Validate magic
            byte[] magic = Arrays.copyOfRange(encBytes, pos, pos + MAGIC.length);
            pos += MAGIC.length;
            if (!Arrays.equals(magic, MAGIC)) {
                throw new EncryptionException("Not a valid Depot export file (magic mismatch).");
            }

            // Validate version
            byte version = encBytes[pos++];
            if (version != VERSION) {
                throw new EncryptionException("Unsupported file version: " + version);
            }

            byte[] salt = Arrays.copyOfRange(encBytes, pos, pos + SALT_LEN); pos += SALT_LEN;
            byte[] iv   = Arrays.copyOfRange(encBytes, pos, pos + IV_LEN);   pos += IV_LEN;
            byte[] ciphertext = Arrays.copyOfRange(encBytes, pos, encBytes.length);

            SecretKey key = deriveKey(password.toCharArray(), salt);
            Cipher cipher = buildCipher(Cipher.DECRYPT_MODE, key, iv);

            try {
                return cipher.doFinal(ciphertext);
            } catch (AEADBadTagException e) {
                throw new EncryptionException("Wrong password or corrupted file.");
            }

        } catch (EncryptionException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Decryption failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SecretKey deriveKey(char[] password, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        var spec = new PBEKeySpec(password, salt, PBKDF2_IT, KEY_LEN);
        var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private Cipher buildCipher(int mode, SecretKey key, byte[] iv)
            throws NoSuchAlgorithmException, NoSuchPaddingException,
                   InvalidKeyException, InvalidAlgorithmParameterException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_LEN, iv));
        return cipher;
    }

    private byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        new SecureRandom().nextBytes(b);
        return b;
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    public static class EncryptionException extends RuntimeException {
        public EncryptionException(String msg) { super(msg); }
        public EncryptionException(String msg, Throwable cause) { super(msg, cause); }
    }
}