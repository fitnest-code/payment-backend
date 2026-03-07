package az.fitnest.payment.client.epoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EpointSignerTest {

    private EpointSigner signer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        signer = new EpointSigner(objectMapper);
    }

    @Test
    void testSignAndVerify() {
        String data = "some_base64_data";
        String privateKey = "my_private_key";

        String signature = signer.sign(data, privateKey);
        assertNotNull(signature);

        assertTrue(signer.verify(data, signature, privateKey));
    }

    @Test
    void testEncodeData() {
        Map<String, String> payload = Map.of("key", "value");
        String encoded = signer.encodeData(payload);
        assertNotNull(encoded);

        Map<String, String> decoded = signer.decodeData(encoded, Map.class);
        assertEquals("value", decoded.get("key"));
    }
}
