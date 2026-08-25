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
     * @param bindingId optional — when set, order is payable only via that binding and
     *                  formUrl asks the payer for CVC (see SmartVista register.do docs).
     */
    public Map<String, Object> registerOrder(String orderNumber,
                                             Double amountManiats,
                                             String description,
                                             String returnUrl,
                                             String failUrl,
                                             String clientId,
                                             Integer installmentMonths) {
        return registerOrder(orderNumber, amountManiats, description, returnUrl, failUrl,
                clientId, installmentMonths, null);
    }

    public Map<String, Object> registerOrder(String orderNumber,
                                             Double amountManiats,
                                             String description,
                                             String returnUrl,
                                             String failUrl,
                                             String clientId,
                                             Integer installmentMonths,
                                             String bindingId) {
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

        String language = bobProperties.getDefaultLanguage();
        if (language != null && !language.isBlank()) {
            params.put("language", language.trim().toLowerCase());
        }

        if (clientId != null && !clientId.isBlank()) {
            // Binding is created on successful payment when clientId is present.
            // Do not send features=BINDING: register.do features only accepts FORCE_SSL / FORCE_TDS.
            params.put("clientId", clientId);
        }

        if (bindingId != null && !bindingId.isBlank()) {
            params.put("bindingId", bindingId);
        }

        if (installmentMonths != null && installmentMonths >= 1) {
            // Bank of Baku installment key used by the merchant terminal.
            params.put("jsonParams", "{\"taxit\":" + installmentMonths + "}");
        }

        log.warn("[BOB][Client] register.do orderNumber={}, amountQepik={}, clientIdPresent={}, bindingIdPresent={}, installmentMonths={}",
                orderNumber, amountInQepik, clientId != null && !clientId.isBlank(),
                bindingId != null && !bindingId.isBlank(), installmentMonths);
        log.warn("[BOB][Client] register.do request params={}", maskParams(params));

        return sendFormRequest(endpoint, params);
    }

    public Map<String, Object> registerOrder(String orderNumber,
                                             Double amountManiats,
                                             String description,
                                             String returnUrl,
                                             String failUrl,
                                             String clientId) {
        return registerOrder(orderNumber, amountManiats, description, returnUrl, failUrl, clientId, null, null);
    }

    /**
     * Yadda saxlanılmış kartla (Binding) ödənişi icra etmək (paymentOrderBinding.do).
     *
     * <p>Merchant-də "Can pay by binding without CVV2/CVC2" yoxdursa {@code cvc} məcburidir.</p>
     */
    public Map<String, Object> payWithBinding(String mdOrder, String bindingId) {
        return payWithBinding(mdOrder, bindingId, null);
    }

    public Map<String, Object> payWithBinding(String mdOrder, String bindingId, String cvc) {
        String endpoint = getUrl("/paymentOrderBinding.do");

        Map<String, String> params = new HashMap<>();
        params.put("userName", bobProperties.getUsername());
        params.put("password", bobProperties.getPassword());
        params.put("mdOrder", mdOrder);
        params.put("bindingId", bindingId);
        if (cvc != null && !cvc.isBlank()) {
            params.put("cvc", cvc.trim());
        }
        String language = bobProperties.getDefaultLanguage();
        if (language != null && !language.isBlank()) {
            params.put("language", language.trim().toLowerCase());
        }

        return sendFormRequest(endpoint, params);
    }

    /** Prefer bank redirect URL when 3DS / ACS challenge is required after binding pay. */
    public static String extractRedirectUrl(Map<String, Object> bindingPayResponse) {
        if (bindingPayResponse == null) {
            return null;
        }
        for (String key : new String[]{"redirect", "formUrl", "acsUrl", "redirectUrl"}) {
            Object value = bindingPayResponse.get(key);
            if (value != null) {
                String url = String.valueOf(value).trim();
                if (!url.isEmpty() && !"null".equalsIgnoreCase(url) && url.startsWith("http")) {
                    return url;
                }
            }
        }
        return null;
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
        logBankStatusDiagnostics(orderId, responseMap);
        BobOrderStatusResponse response = objectMapper.convertValue(responseMap, BobOrderStatusResponse.class);
        response.flattenBankPayload();
        return response;
    }

    @SuppressWarnings("unchecked")
    private void logBankStatusDiagnostics(String orderId, Map<String, Object> responseMap) {
        if (responseMap == null) {
            log.warn("[BOB][Client] getOrderStatusExtended empty response orderId={}", orderId);
            return;
        }
        Object orderStatus = responseMap.get("orderStatus");
        Object actionCode = responseMap.get("actionCode");
        Object actionDesc = responseMap.get("actionCodeDescription");
        Object errorMessage = responseMap.get("errorMessage");
        Object errorCode = responseMap.get("errorCode");
        Object bindingId = responseMap.get("bindingId");
        Object bindingInfo = responseMap.get("bindingInfo");
        Object cardAuthInfo = responseMap.get("cardAuthInfo");
        Object paymentAmountInfo = responseMap.get("paymentAmountInfo");
        Object authRefNum = responseMap.get("authRefNum");
        Object rrn = responseMap.get("rrn");
        Object pan = null;
        Object paymentSystem = null;
        if (cardAuthInfo instanceof Map<?, ?> cardAuth) {
            pan = cardAuth.get("pan");
            paymentSystem = cardAuth.get("paymentSystem");
        }
        Object paymentState = paymentAmountInfo instanceof Map<?, ?> amountInfo
                ? amountInfo.get("paymentState")
                : null;
        Object bindingInfoId = bindingInfo instanceof Map<?, ?> info
                ? info.get("bindingId")
                : bindingInfo;

        log.warn("[BOB][Client] status orderId={} errorCode={} errorMessage={} orderStatus={} actionCode={} actionCodeDescription={} paymentState={} paymentSystem={} pan={} rrn={} authRefNum={} bindingId={} bindingInfoId={}",
                orderId, errorCode, errorMessage, orderStatus, actionCode, actionDesc, paymentState,
                paymentSystem, pan, rrn, authRefNum, bindingId, bindingInfoId);
        try {
            log.warn("[BOB][Client] status rawBody orderId={} {}", orderId, objectMapper.writeValueAsString(responseMap));
        } catch (Exception e) {
            log.warn("[BOB][Client] status rawBody serialize failed orderId={}: {}", orderId, responseMap);
        }
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

    private Map<String, String> maskParams(Map<String, String> params) {
        Map<String, String> masked = new HashMap<>(params);
        if (masked.containsKey("password")) {
            masked.put("password", "***");
        }
        if (masked.containsKey("cvc")) {
            masked.put("cvc", "***");
        }
        return masked;
    }

    private String maskSensitiveBody(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            return requestBody;
        }
        return requestBody
                .replaceAll("(?i)(password=)[^&]*", "$1***")
                .replaceAll("(?i)(cvc=)[^&]*", "$1***");
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
            String maskedBody = maskSensitiveBody(requestBody);

            log.warn("[BOB][Client] Sending POST request to endpoint: {}, body={}", endpoint, maskedBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(bobProperties.getTimeoutMs()))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            log.warn("[BOB][Client] Response from {}: status={}, body={}",
                    endpoint, response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                log.error("[BOB][Client] HTTP Error response: status={}, requestBody={}, body={}",
                        response.statusCode(), maskedBody, response.body());
                throw new BobPaymentException("BOB_HTTP_ERROR", "Bank of Baku HTTP xətası: status " + response.statusCode());
            }

            Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
            Object errorCode = responseMap.get("errorCode");
            if (errorCode != null && !"0".equals(String.valueOf(errorCode))) {
                log.error("[BOB][Client] Bank error from {}: errorCode={}, requestBody={}, responseBody={}",
                        endpoint, errorCode, maskedBody, response.body());
            }
            return responseMap;
        } catch (BobPaymentException bpe) {
            throw bpe;
        } catch (Exception e) {
            log.error("[BOB][Client] HTTP call failed for endpoint: {}", endpoint, e);
            throw new BobPaymentException("BOB_CONNECTION_ERROR", "Bank of Baku servisi ilə əlaqə qurmaq mümkün olmadı: " + e.getMessage(), e);
        }
    }
}
