package az.fitnest.payment.client.abb.bnpl;

import az.fitnest.payment.dto.abb.bnpl.BnplAbbOrderDetail;
import az.fitnest.payment.dto.abb.bnpl.BnplAbbSubmitRequest;
import az.fitnest.payment.dto.abb.bnpl.BnplAbbSubmitResponse;
import az.fitnest.payment.exception.BnplPaymentException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * ABB BNPL External API HTTP client.
 *
 * <ul>
 *   <li>POST /bnpl/orders — submit order</li>
 *   <li>GET /bnpl/orders/{id} — order detail</li>
 *   <li>PUT /bnpl/orders/{id}/reverse — full reverse</li>
 *   <li>PUT /bnpl/orders/{id}/partial-reverse — partial reverse</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbbBnplRestClient {

    private final AbbBnplProperties properties;
    private final AbbBnplTokenService tokenService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public BnplAbbSubmitResponse submitOrder(BnplAbbSubmitRequest body) {
        String json = writeJson(body);
        HttpResponse<String> response = send("POST", "/bnpl/orders", json, true);
        return readJson(response.body(), BnplAbbSubmitResponse.class);
    }

    public BnplAbbOrderDetail getOrder(long orderId) {
        HttpResponse<String> response = send("GET", "/bnpl/orders/" + orderId, null, true);
        return readJson(response.body(), BnplAbbOrderDetail.class);
    }

    public void fullReverse(long orderId) {
        send("PUT", "/bnpl/orders/" + orderId + "/reverse", null, true);
    }

    public void partialReverse(long orderId, double amount) {
        String json = writeJson(Map.of("amount", amount));
        send("PUT", "/bnpl/orders/" + orderId + "/partial-reverse", json, true);
    }

    private HttpResponse<String> send(String method, String path, String jsonBody, boolean retryOn401) {
        try {
            String token = tokenService.getAccessToken();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + path))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json");

            if (jsonBody != null) {
                builder.header("Content-Type", "application/json");
            }

            switch (method) {
                case "GET" -> builder.GET();
                case "POST" -> builder.POST(jsonBody != null
                        ? HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)
                        : HttpRequest.BodyPublishers.noBody());
                case "PUT" -> builder.PUT(jsonBody != null
                        ? HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)
                        : HttpRequest.BodyPublishers.noBody());
                default -> throw new BnplPaymentException("BNPL_HTTP_METHOD", "Unsupported method: " + method);
            }

            log.info("[BNPL][Client] {} {}", method, path);
            HttpResponse<String> response = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 && retryOn401) {
                log.warn("[BNPL][Client] 401 — refreshing token and retrying {}", path);
                tokenService.invalidate();
                return send(method, path, jsonBody, false);
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw mapHttpError(response.statusCode(), response.body());
            }
            return response;
        } catch (BnplPaymentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BNPL][Client] {} {} failed", method, path, e);
            throw new BnplPaymentException("BNPL_CONNECTION_ERROR",
                    "ABB BNPL servisi ilə əlaqə qurula bilmədi: " + e.getMessage(), e);
        }
    }

    private BnplPaymentException mapHttpError(int status, String body) {
        String message = extractMessage(body);
        String code = switch (status) {
            case 400 -> "BNPL_BAD_REQUEST";
            case 401 -> "BNPL_UNAUTHORIZED";
            case 403 -> "BNPL_FORBIDDEN";
            case 404 -> "BNPL_NOT_FOUND";
            case 429 -> "BNPL_RATE_LIMIT";
            default -> "BNPL_HTTP_" + status;
        };
        String userMessage = mapUserFacingMessage(message, status);
        log.error("[BNPL][Client] HTTP {} body={}", status, body);
        return new BnplPaymentException(code, userMessage);
    }

    private String mapUserFacingMessage(String bankMessage, int status) {
        if (bankMessage == null) {
            bankMessage = "";
        }
        String lower = bankMessage.toLowerCase();
        if (lower.contains("pin") && lower.contains("phone")
                || lower.contains("not related")
                || lower.contains("related")) {
            return "Daxil etdiyiniz məlumatlar ABB-dəki məlumatlarla uyğun gəlmir.";
        }
        if (lower.contains("already exists") || lower.contains("pending")) {
            return "Hazırda aktiv sorğunuz mövcuddur.";
        }
        if (lower.contains("term") && lower.contains("not available")) {
            return "Seçdiyiniz ödəniş müddəti əlçatan deyil.";
        }
        if (status == 404 || lower.contains("customer not found") || lower.contains("not found")) {
            if (lower.contains("customer") || status == 404 && lower.contains("customer")) {
                return "ABB-də uyğun müştəri məlumatı tapılmadı.";
            }
            if (lower.contains("order")) {
                return "BNPL sifarişi tapılmadı.";
            }
        }
        if (status == 403) {
            return "ABB bağlantısı rədd edildi. Dəstəyə müraciət edin.";
        }
        if (status == 429) {
            return "Çox sayda sorğu göndərildi. Bir az sonra yenidən cəhd edin.";
        }
        if (status >= 500) {
            return "Hazırda əməliyyatı tamamlamaq mümkün deyil. Daha sonra yenidən cəhd edin.";
        }
        if (!bankMessage.isBlank()) {
            return bankMessage;
        }
        return "BNPL əməliyyatı uğursuz oldu.";
    }

    private String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has("message")) {
                return node.get("message").asText();
            }
            if (node.has("error")) {
                JsonNode error = node.get("error");
                if (error.isTextual()) {
                    return error.asText();
                }
                if (error.has("message")) {
                    return error.get("message").asText();
                }
            }
            if (node.has("title")) {
                return node.get("title").asText();
            }
        } catch (Exception ignored) {
            // raw body fallback
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private String baseUrl() {
        String base = properties.getApiBaseUrl();
        if (base == null || base.isBlank()) {
            throw new BnplPaymentException("BNPL_CONFIG_MISSING", "ABB BNPL apiBaseUrl boşdur");
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BnplPaymentException("BNPL_SERIALIZE_ERROR", "BNPL request serialize olunamadı", e);
        }
    }

    private <T> T readJson(String body, Class<T> type) {
        try {
            if (body == null || body.isBlank()) {
                return type.getDeclaredConstructor().newInstance();
            }
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new BnplPaymentException("BNPL_PARSE_ERROR",
                    "ABB BNPL cavabı parse olunamadı: " + e.getMessage(), e);
        }
    }
}
