package az.fitnest.payment.client.abb.bnpl;

import az.fitnest.payment.exception.BnplPaymentException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Obtains and caches ABB OAuth2 access tokens via Private Key JWT (client_credentials).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbbBnplTokenService {

    private final AbbBnplProperties properties;
    private final ObjectMapper objectMapper;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public String getAccessToken() {
        CachedToken current = cached.get();
        if (current != null && current.expiresAt.isAfter(Instant.now().plusSeconds(30))) {
            return current.accessToken;
        }
        synchronized (this) {
            current = cached.get();
            if (current != null && current.expiresAt.isAfter(Instant.now().plusSeconds(30))) {
                return current.accessToken;
            }
            CachedToken fresh = requestToken();
            cached.set(fresh);
            return fresh.accessToken;
        }
    }

    public void invalidate() {
        cached.set(null);
    }

    private CachedToken requestToken() {
        if (isBlank(properties.getClientId()) || isBlank(properties.getPrivateKey())) {
            throw new BnplPaymentException("BNPL_CONFIG_MISSING",
                    "ABB BNPL clientId / privateKey konfiqurasiya olunmayıb");
        }

        String assertion = buildClientAssertion();
        String body = "grant_type=" + enc("client_credentials")
                + "&client_id=" + enc(properties.getClientId())
                + "&client_assertion_type=" + enc("urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                + "&client_assertion=" + enc(assertion)
                + "&scope=" + enc(properties.getScope() != null ? properties.getScope() : "openid");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getAuthUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("[BNPL][Auth] Token request failed status={} body={}",
                        response.statusCode(), response.body());
                throw new BnplPaymentException("BNPL_AUTH_FAILED",
                        "ABB BNPL token alınamadı: HTTP " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String accessToken = json.path("access_token").asText(null);
            int expiresIn = json.path("expires_in").asInt(300);
            if (isBlank(accessToken)) {
                throw new BnplPaymentException("BNPL_AUTH_FAILED", "ABB BNPL access_token boş qayıtdı");
            }

            log.info("[BNPL][Auth] Access token obtained, expiresIn={}s", expiresIn);
            return new CachedToken(accessToken, Instant.now().plusSeconds(Math.max(60, expiresIn)));
        } catch (BnplPaymentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BNPL][Auth] Token request error", e);
            throw new BnplPaymentException("BNPL_AUTH_FAILED",
                    "ABB BNPL auth servisi ilə əlaqə qurula bilmədi: " + e.getMessage(), e);
        }
    }

    private String buildClientAssertion() {
        try {
            String aud = !isBlank(properties.getTokenAudience())
                    ? properties.getTokenAudience()
                    : properties.getAuthUrl();
            long iat = Instant.now().getEpochSecond();
            long exp = iat + 180; // doc: must expire within 3 minutes
            String jti = UUID.randomUUID().toString();

            String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
            String payloadJson = "{"
                    + "\"iss\":\"" + escapeJson(properties.getClientId()) + "\","
                    + "\"sub\":\"" + escapeJson(properties.getClientId()) + "\","
                    + "\"aud\":\"" + escapeJson(aud) + "\","
                    + "\"iat\":" + iat + ","
                    + "\"exp\":" + exp + ","
                    + "\"jti\":\"" + jti + "\""
                    + "}";

            String header = base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
            String payload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;

            PrivateKey privateKey = loadPrivateKey(properties.getPrivateKey());
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String sig = base64Url(signature.sign());

            return signingInput + "." + sig;
        } catch (BnplPaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new BnplPaymentException("BNPL_JWT_SIGN_FAILED",
                    "ABB BNPL client_assertion imzalanamadı: " + e.getMessage(), e);
        }
    }

    private PrivateKey loadPrivateKey(String pemOrBase64) throws Exception {
        String stripped = pemOrBase64
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(stripped);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception pkcs8Fail) {
            // PKCS#1 → PKCS#8 wrap (same approach as AbbSigner)
            byte[] pkcs8 = wrapPkcs1ToPkcs8(keyBytes);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        }
    }

    private static byte[] wrapPkcs1ToPkcs8(byte[] pkcs1Bytes) {
        byte[] algId = new byte[]{
                0x30, 0x0d,
                0x06, 0x09,
                0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        byte[] octetString = encodeAsn1Length(0x04, pkcs1Bytes);
        byte[] inner = new byte[3 + algId.length + octetString.length];
        inner[0] = 0x02;
        inner[1] = 0x01;
        inner[2] = 0x00;
        System.arraycopy(algId, 0, inner, 3, algId.length);
        System.arraycopy(octetString, 0, inner, 3 + algId.length, octetString.length);
        return encodeAsn1Length(0x30, inner);
    }

    private static byte[] encodeAsn1Length(int tag, byte[] content) {
        int length = content.length;
        byte[] header;
        if (length < 128) {
            header = new byte[]{(byte) tag, (byte) length};
        } else if (length < 256) {
            header = new byte[]{(byte) tag, (byte) 0x81, (byte) length};
        } else {
            header = new byte[]{(byte) tag, (byte) 0x82, (byte) (length >> 8), (byte) (length & 0xff)};
        }
        byte[] result = new byte[header.length + content.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(content, 0, result, header.length, content.length);
        return result;
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }
}
