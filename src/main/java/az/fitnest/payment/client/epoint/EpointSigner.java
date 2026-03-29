package az.fitnest.payment.client.epoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class EpointSigner {

    private final ObjectMapper objectMapper;

    public String encodeData(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("error.payment_encoding_failed", e);
        }
    }

    public String sign(String data, String privateKey) {
        try {
            String combined = privateKey + data + privateKey;
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("error.payment_crypto_error", e);
        }
    }

    public boolean verify(String data, String signature, String privateKey) {
        String expectedSignature = sign(data, privateKey);
        byte[] expectedBytes = Base64.getDecoder().decode(expectedSignature);
        byte[] actualBytes = Base64.getDecoder().decode(signature);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    public <T> T decodeData(String base64Data, Class<T> clazz) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Data);
            String json = new String(decoded, StandardCharsets.UTF_8);
            System.out.println("Decoded JSON: " + json);
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("error.payment_decoding_failed", e);
        }
    }
}
