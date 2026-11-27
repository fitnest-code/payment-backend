package az.fitnest.payment.client.kapital;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KapitalRestClientMaskTest {

    @Test
    void masksPasswordCryptValAndTdsARes() {
        String masked = KapitalRestClient.mask(
                "{\"order\":{\"id\":7,\"password\":\"p4ss\",\"secret\":\"s3c\"},\"cryptVal\":\"cv\",\"tdsARes\":\"ares\"}");
        assertFalse(masked.contains("p4ss"));
        assertFalse(masked.contains("s3c"));
        assertFalse(masked.contains("\"cv\""));
        assertFalse(masked.contains("ares\""));
        assertTrue(masked.contains("\"id\":7"));
    }
}
