package az.fitnest.payment.client.epoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EpointSignerTest {

    private EpointSigner signer;
    private final String privateKey = "test_private_key";

    @BeforeEach
    void setUp() {
        signer = new EpointSigner(new ObjectMapper());
    }

    @Test
    void testEncodeData() {
        Map<String, String> payload = new HashMap<>();
        payload.put("key", "value");
        
        String encoded = signer.encodeData(payload);
        assertNotNull(encoded);
        
        String decodedJson = new String(Base64.getDecoder().decode(encoded));
        assertTrue(decodedJson.contains("\"key\":\"value\""));
    }

    @Test
    void testSignAndVerify() {
        String data = "some_base64_data";
        String signature = signer.sign(data, privateKey);
        
        assertNotNull(signature);
        assertTrue(signer.verify(data, signature, privateKey));
    }

    @Test
    void testVerifyFailure() {
        String data = "some_base64_data";
        String signature = signer.sign(data, privateKey);
        
        assertFalse(signer.verify(data + "tampered", signature, privateKey));
        assertFalse(signer.verify(data, signature, "wrong_key"));
    }
}
