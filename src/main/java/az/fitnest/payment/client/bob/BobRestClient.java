package az.fitnest.payment.client.bob;

import az.fitnest.payment.dto.bob.BobOrderStatusResponse;
import az.fitnest.payment.exception.BobPaymentException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Bank of Baku SmartVista EPG REST API üçün HTTP Client.
 *
 * <p>SmartVista EPG REST metodları (register.do, getOrderStatusExtended.do,
 * paymentOrderBinding.do, refund.do, unBindCard.do) ilə əlaqə yaradır.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BobRestClient {

    private final BobProperties bobProperties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * SmartVista EPG register.do metodu vasitəsilə yeni ödəniş (Order) qeydə alır.
     *
     * @param orderNumber Unikal tranzaksiya nömrəsi
     * @param amountManiats Məbləğ (manat ilə, məs: 15.50)
     * @param description Təsvir
     * @param returnUrl Uğurlu yönləndirmə URL-i
     * @param failUrl Uğursuz yönləndirmə URL-i
     * @param clientId İstifadəçi ID-si (Kart saxlama üçün)
     * @return Map cavab (orderId, formUrl, errorCode, errorMessage)
     */
    public Map<String, Object> registerOrder(String orderNumber,
                                             Double amountManiats,
                                             String description,
                                             String returnUrl,
                                             String failUrl,
                                             String clientId,
                                             Integer installmentMonths) {
        String endpoint = getUrl("/register.do");

        long amountInQepik = Math.round(amountManiats * 100);

        Map<String, String> params = new HashMap<>();
        params.put("userName", bobProperties.getUsername());
        params.put("password", bobProperties.getPassword());
        params.put("orderNumber", orderNumber);
        params.put("amount", String.valueOf(amountInQepik));
        params.put("currency", getCurrencyCode(bobProperties.getDefaultCurrency()));
        params.put("returnUrl", returnUrl);
        params.put("failUrl", failUrl);

        if (description != null && !description.isBlank()) {
            params.put("description", description);
        }

        if (clientId != null && !clientId.isBlank()) {
            params.put("clientId", clientId);
        }

        if (installmentMonths != null && installmentMonths >= 1) {
            params.put("jsonParams", "{\"taxit\":" + installmentMonths + "}");
        }

        return sendFormRequest(endpoint, params);
    }

    public Map<String, Object> registerOrder(String orderNumber,
                                             Double amountManiats,
                                             String description,
                                             String returnUrl,
                                             String failUrl,
                                             String clientId) {
        return registerOrder(orderNumber, amountManiats, description, returnUrl, failUrl, clientId, null);
    }

    /**
     * Yadda saxlanılmış kartla (Binding) ödənişi icra etmək (paymentOrderBinding.do).
     *
     * @param mdOrder SmartVista Order ID (register.do ilə alınan)
     * @param bindingId Yadda saxlanılmış kartın tokeni
     * @return Map cavab
     */
    public Map<String, Object> payWithBinding(String mdOrder, String bindingId) {
        String endpoint = getUrl("/paymentOrderBinding.do");

        Map<String, String> params = new HashMap<>();
        params.put("userName", bobProperties.getUsername());
        params.put("password", bobProperties.getPassword());
        params.put("mdOrder", mdOrder);
        params.put("bindingId", bindingId);

        return sendFormRequest(endpoint, params);
    }

    /**
     * Ödəniş statusunu yoxlamaq (getOrderStatusExtended.do).
     *
     * @param orderId SmartVista Order ID
     * @return {@link BobOrderStatusResponse}
     */
    public BobOrderStatusResponse getOrderStatusExtended(String orderId) {
        String endpoint = getUrl("/getOrderStatusExtended.do");

        Map<String, String> params = new HashMap<>();
        params.put("userName", bobProperties.getUsername());
        params.put("password", bobProperties.getPassword());
        params.put("orderId", orderId);

        Map<String, Object> responseMap = sendFormRequest(endpoint, params);
        return objectMapper.convertValue(responseMap, BobOrderStatusResponse.class);
    }

    /**
     * Məbləği geri qaytarmaq (refund.do).
     *
     * @param orderId SmartVista Order ID
     * @param amountManats Qaytarılacaq məbləğ (manat)
     * @return Map cavab
     */
    public Map<String, Object> refund(String orderId, Double amountManats) {
        String endpoint = getUrl("/refund.do");
        long amountInQepik = Math.round(amountManats * 100);

        Map<String, String> params = new HashMap<>();
        params.put("userName", bobProperties.getUsername());
        params.put("password", bobProperties.getPassword());
        params.put("orderId", orderId);
        params.put("amount", String.valueOf(amountInQepik));

        return sendFormRequest(endpoint, params);
    }

    /**
     * Yadda saxlanılmış kartı ləğv etmək (unBindCard.do).
     *
     * @param bindingId Kart tokeni
     * @return Map cavab
     */
    public Map<String, Object> unbindCard(String bindingId) {
        String endpoint = getUrl("/unBindCard.do");

        Map<String, String> params = new HashMap<>();
        params.put("userName", bobProperties.getUsername());
        params.put("password", bobProperties.getPassword());
        params.put("bindingId", bindingId);

        return sendFormRequest(endpoint, params);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Yardımçı daxili metodlar
    // ══════════════════════════════════════════════════════════════════════════

    private String getUrl(String path) {
        String base = bobProperties.getGatewayUrl();
        if (base == null || base.isBlank()) {
            base = "https://epg.bankofbaku.com/payment/rest";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private String getCurrencyCode(String currencyStr) {
        if (currencyStr == null || currencyStr.equalsIgnoreCase("AZN")) {
            return "944";
        }
        if (currencyStr.equalsIgnoreCase("USD")) {
            return "840";
        }
        if (currencyStr.equalsIgnoreCase("EUR")) {
            return "978";
        }
        return currencyStr;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sendFormRequest(String endpoint, Map<String, String> params) {
        try {
            StringJoiner sj = new StringJoiner("&");
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getValue() != null) {
                    sj.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" +
                            URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                }
            }
            String requestBody = sj.toString();

            log.info("[BOB][Client] Sending POST request to endpoint: {}", endpoint);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(bobProperties.getTimeoutMs()))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[BOB][Client] HTTP Error response: status={}, body={}", response.statusCode(), response.body());
                throw new BobPaymentException("BOB_HTTP_ERROR", "Bank of Baku HTTP xətası: status " + response.statusCode());
            }

            return objectMapper.readValue(response.body(), Map.class);
        } catch (BobPaymentException bpe) {
            throw bpe;
        } catch (Exception e) {
            log.error("[BOB][Client] HTTP call failed for endpoint: {}", endpoint, e);
            throw new BobPaymentException("BOB_CONNECTION_ERROR", "Bank of Baku servisi ilə əlaqə qurmaq mümkün olmadı: " + e.getMessage(), e);
        }
    }
}
