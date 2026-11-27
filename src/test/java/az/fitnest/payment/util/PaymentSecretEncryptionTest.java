package az.fitnest.payment.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaymentSecretEncryptionTest {

    @Test
    void roundTripsAndIsNonDeterministic() {
        PaymentSecretEncryption enc = new PaymentSecretEncryption("short-key");
        String a = enc.encryptToBase64("hpp-password");
        String b = enc.encryptToBase64("hpp-password");
        assertNotEquals(a, b);
        assertFalse(a.contains("hpp-password"));
        assertEquals("hpp-password", enc.decryptFromBase64(a));
    }

    @Test
    void failsClosedWithoutKey() {
        assertThrows(IllegalStateException.class, () -> new PaymentSecretEncryption("").encryptToBase64("x"));
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String encrypted = new PaymentSecretEncryption("key-one").encryptToBase64("secret");
        assertThrows(IllegalStateException.class, () -> new PaymentSecretEncryption("key-two").decryptFromBase64(encrypted));
    }
}
