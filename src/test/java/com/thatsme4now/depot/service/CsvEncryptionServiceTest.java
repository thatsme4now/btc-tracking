package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests (no Spring context needed) for the AES-256-GCM CSV
 * encryption/decryption round trip.
 */
class CsvEncryptionServiceTest {

    private final CsvEncryptionService service = new CsvEncryptionService();

    @Test
    void encryptThenDecrypt_returnsOriginalBytes() {
        byte[] original = "typ,date,exchange\nTrade,15.03.2023,Binance\n".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = service.encrypt(original, "correct-horse-battery-staple");
        byte[] decrypted = service.decrypt(encrypted, "correct-horse-battery-staple");

        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void encrypt_producesDifferentCiphertext_forSameInput() {
        byte[] original = "same content".getBytes(StandardCharsets.UTF_8);

        byte[] first = service.encrypt(original, "password");
        byte[] second = service.encrypt(original, "password");

        // random salt/IV per call -> ciphertext must differ even for identical input
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void decrypt_withWrongPassword_throwsEncryptionException() {
        byte[] encrypted = service.encrypt("secret data".getBytes(StandardCharsets.UTF_8), "correct-password");

        assertThatThrownBy(() -> service.decrypt(encrypted, "wrong-password"))
                .isInstanceOf(CsvEncryptionService.EncryptionException.class);
    }

    @Test
    void decrypt_withTruncatedFile_throwsEncryptionException() {
        byte[] tooShort = new byte[]{1, 2, 3};

        assertThatThrownBy(() -> service.decrypt(tooShort, "password"))
                .isInstanceOf(CsvEncryptionService.EncryptionException.class);
    }

    @Test
    void decrypt_withWrongMagicBytes_throwsEncryptionException() {
        byte[] encrypted = service.encrypt("data".getBytes(StandardCharsets.UTF_8), "password");
        encrypted[0] = 'X'; // corrupt the magic header

        assertThatThrownBy(() -> service.decrypt(encrypted, "password"))
                .isInstanceOf(CsvEncryptionService.EncryptionException.class);
    }

    @Test
    void decrypt_withUnsupportedVersion_throwsEncryptionException() {
        byte[] encrypted = service.encrypt("data".getBytes(StandardCharsets.UTF_8), "password");
        encrypted[4] = 0x02; // byte after the 4-byte magic is the version

        assertThatThrownBy(() -> service.decrypt(encrypted, "password"))
                .isInstanceOf(CsvEncryptionService.EncryptionException.class);
    }
}
