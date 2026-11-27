package az.fitnest.payment.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM encryption for short provider secrets stored in the DB (Kapital HPP order password).
 *
 * <p>Same key handling as order-backend's PromoPhoneEncryption (UTF-8 key zero-padded/truncated
 * to 32 bytes) so ops can reuse one key-management convention. Output is base64(iv || ciphertext)
 * so it fits a TEXT column; a fresh random IV per call keeps equal plaintexts distinct.</p>
 */
@Component
public class PaymentSecretEncryption {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String encryptionKey;

    public PaymentSecretEncryption(@Value("${kapital.order-password-encryption-key:}") String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String encryptToBase64(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // Never include plaintext in the message/cause chain.
            throw new IllegalStateException("Payment secret encryption failed", e);
        }
    }

    public String decryptFromBase64(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Payment secret decryption failed", e);
        }
    }

    private SecretKeySpec keySpec() {
        // Fail closed: an empty key would silently encrypt with an all-zero key.
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("kapital.order-password-encryption-key is not configured");
        }
        byte[] keyBytes = encryptionKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
