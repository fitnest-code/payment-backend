package az.fitnest.payment.client.kapital;

import az.fitnest.payment.exception.KapitalPaymentException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Kapital Bank REST client (Basic auth). Only the three calls we need: create order,
 * read order (the single source of truth for status) and exec-tran (void/refund).
 */
@Slf4j
@Component
public class KapitalRestClient {

    /** Secrets that must never reach logs, in JSON ("key":"value") form. */
    private static final Pattern SECRET_FIELDS = Pattern.compile(
            "(\"(?:password|cryptVal|tdsARes|secret)\"\\s*:\\s*)(\"[^\"]*\"|[^,}\\s]+)",
            Pattern.CASE_INSENSITIVE);
    private static final int LOG_BODY_MAX = 2000;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final KapitalProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public KapitalRestClient(KapitalProperties properties) {
        this.properties = properties;
    }

    public KapitalCreateOrderResponse createOrder(KapitalCreateOrderRequest request) {
        String body = call("POST", "/order", request);
        return read(body, KapitalCreateOrderResponse.class);
    }

    /** Authoritative status read; detail levels expose tran/token data incl. pmoResultCode. */
    public KapitalOrderResponse getOrder(String bankOrderId) {
        String path = "/order/" + URLEncoder.encode(bankOrderId, StandardCharsets.UTF_8)
                + "?tranDetailLevel=2&tokenDetailLevel=2&orderDetailLevel=2";
        return read(call("GET", path, null), KapitalOrderResponse.class);
    }

    public KapitalExecTranResponse execTran(String bankOrderId, KapitalExecTranRequest request) {
        String path = "/order/" + URLEncoder.encode(bankOrderId, StandardCharsets.UTF_8) + "/exec-tran";
        return read(call("POST", path, request), KapitalExecTranResponse.class);
    }

    private String call(String method, String path, Object payload) {
        String url = trimTrailingSlash(properties.getBaseUrl()) + path;
        try {
            String json = payload == null ? null : objectMapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Authorization", basicAuth())
                    .header("Accept", "application/json");
            if (json != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            log.info("[KAPITAL][HTTP] {} {} body={}", method, path, mask(json));

            HttpResponse<String> response = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();
            log.info("[KAPITAL][HTTP] {} {} -> {} body={}", method, path, response.statusCode(), mask(responseBody));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new KapitalPaymentException("KAPITAL_HTTP_" + response.statusCode(),
                        "Kapital Bank sorğusu uğursuz oldu (HTTP " + response.statusCode() + ")");
            }
            return responseBody;
        } catch (KapitalPaymentException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KapitalPaymentException("KAPITAL_INTERRUPTED", "Kapital Bank sorğusu yarımçıq qaldı");
        } catch (Exception e) {
            log.error("[KAPITAL][HTTP] {} {} failed: {}", method, path, e.getClass().getSimpleName());
            throw new KapitalPaymentException("KAPITAL_UNAVAILABLE", "Kapital Bank ilə əlaqə qurulmadı");
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body == null || body.isBlank() ? "{}" : body, type);
        } catch (Exception e) {
            throw new KapitalPaymentException("KAPITAL_BAD_RESPONSE", "Kapital Bank cavabı oxunmadı");
        }
    }

    private String basicAuth() {
        String user = properties.getUsername() == null ? "" : properties.getUsername();
        String pass = properties.getPassword() == null ? "" : properties.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Masks password/cryptVal/tdsARes/secret values and truncates for log safety. */
    public static String mask(String body) {
        if (body == null) {
            return null;
        }
        String masked = SECRET_FIELDS.matcher(body).replaceAll("$1\"***\"");
        return masked.length() > LOG_BODY_MAX ? masked.substring(0, LOG_BODY_MAX) + "...(truncated)" : masked;
    }
}
