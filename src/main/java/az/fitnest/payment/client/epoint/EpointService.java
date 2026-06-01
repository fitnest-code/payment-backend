package az.fitnest.payment.client.epoint;

import az.fitnest.payment.dto.epoint.*;
import az.fitnest.payment.client.UserGrpcClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URL;

@Service
@RequiredArgsConstructor
public class EpointService {

    private static final Logger log = LoggerFactory.getLogger(EpointService.class);

    private final EpointHttpClient httpClient;
    private final EpointProperties properties;
    private final UserGrpcClient userGrpcClient;
    private final StringRedisTemplate redisTemplate;

    // ─────────────────────────────────────────────────────────────────
    // Public API metodları
    // ─────────────────────────────────────────────────────────────────

    public EpointResponse createPayment(EpointPaymentRequest request) {
        request = fillPublicKey(request, request.orderId());
        return httpClient.postSigned("/request", request);
    }

    public EpointResponse getStatus(String transactionId) {
        EpointStatusRequest request = new EpointStatusRequest(
                properties.getPublicKey(),
                transactionId
        );
        return httpClient.postSigned("/get-status", request);
    }

    public EpointResponse cardRegistration(EpointCardRegistrationRequest request, String registrationId) {
        log.info("EpointService.cardRegistration: request.publicKey={}, properties.publicKey={}, env.EPOINT_PUBLIC_KEY={}",
                request.publicKey(), properties.getPublicKey(), System.getenv("EPOINT_PUBLIC_KEY"));
        request = fillPublicKey(request, registrationId);
        log.info("EpointService.cardRegistration after fillPublicKey: request.publicKey={}", request.publicKey());
        return httpClient.postSigned("/card-registration", request);
    }

    public EpointResponse executePay(EpointExecutePayRequest request) {
        request = fillPublicKey(request, request.orderId());
        return httpClient.postSigned("/execute-pay", request);
    }

    public EpointResponse cardRegistrationWithPay(EpointPaymentRequest request) {
        request = fillPublicKey(request, request.orderId());
        return httpClient.postSigned("/card-registration-with-pay", request);
    }

    public EpointResponse refundRequest(EpointExecutePayRequest request) {
        request = fillPublicKey(request, request.orderId());
        return httpClient.postSigned("/refund-request", request);
    }

    public EpointResponse refundRequest(EpointRefundRequest request) {
        if (request.getPublicKey() == null) {
            request.setPublicKey(properties.getPublicKey());
        }
        return httpClient.postSigned("/refund-request", request);
    }

    public EpointResponse reverse(String transactionId, Double amount, String currency) {
        EpointPaymentRequest request = EpointPaymentRequest.builder()
                .publicKey(properties.getPublicKey())
                .orderId(null)
                .amount(amount)
                .currency(currency)
                .description(null)
                .resultUrl(properties.getResultUrl())
                .successRedirectUrl(properties.getSuccessRedirectUrl())
                .errorRedirectUrl(properties.getErrorRedirectUrl())
                .build();
        return httpClient.postSigned("/reverse", request);
    }

    public EpointResponse splitRequest(EpointSplitPaymentRequest request) {
        request = fillPublicKey(request, request.orderId());
        return httpClient.postSigned("/split-request", request);
    }

    public EpointResponse splitExecutePay(EpointSplitExecutePayRequest request) {
        request = fillPublicKey(request, request.orderId());
        return httpClient.postSigned("/split-execute-pay", request);
    }

    public EpointResponse splitCardRegistrationWithPay(EpointSplitPaymentRequest request) {
        request = fillPublicKey(request, request.orderId());
        return httpClient.postSigned("/split-card-registration-with-pay", request);
    }

    public EpointResponse preAuthRequest(EpointPaymentRequest request) {
        request = fillPublicKey(request, request.orderId());
        return httpClient.postSigned("/pre-auth-request", request);
    }

    public EpointResponse preAuthComplete(EpointPreAuthCompleteRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/pre-auth-complete", request);
    }

    public EpointResponse createWidgetUrl(EpointWidgetRequest request) {
        request = fillPublicKey(request, request.orderId());
        return httpClient.postSigned("/token/widget", request);
    }

    public EpointResponse walletStatus() {
        EpointRequestPayload request = EpointRequestPayload.builder()
                .publicKey(properties.getPublicKey())
                .build();
        return httpClient.postSigned("/wallet/status", request);
    }

    public EpointResponse walletStatusDirect(EpointRequestPayload request) {
        return httpClient.postDirect("/wallet/status", request);
    }

    public EpointResponse walletPayment(EpointWalletPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/wallet/payment", request);
    }

    public EpointResponse createInvoice(EpointInvoiceCreateRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/create", request);
    }

    public EpointResponse updateInvoice(EpointInvoiceUpdateRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/update", request);
    }

    public EpointResponse viewInvoice(Long id) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder().id(id).build();
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/view", request);
    }

    public EpointResponse listInvoices(String type, String order) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder()
                .type(type)
                .order(order)
                .build();
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/list", request);
    }

    public EpointResponse sendInvoiceSms(Long id, String phone) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder()
                .id(id)
                .phone(phone)
                .build();
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/send-sms", request);
    }

    public EpointResponse sendInvoiceEmail(Long id, String email) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder()
                .id(id)
                .email(email)
                .build();
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/send-email", request);
    }

    public EpointResponse heartbeat() {
        return httpClient.get("/heartbeat");
    }

    // ─────────────────────────────────────────────────────────────────
    // fillPublicKey — hər request tipi üçün ayrı overload
    // ─────────────────────────────────────────────────────────────────

    /**
     * Ümumi qayda:
     *  - resultUrl     → həmişə properties-dən (sabit, bizim callback endpoint)
     *  - successRedirectUrl → getDynamicSuccessUrl() ilə (dil prefiksi + Origin/Referer)
     *  - errorRedirectUrl   → getDynamicErrorUrl()   ilə (dil prefiksi + Origin/Referer)
     *
     * errorRedirectUrl-ə kod ƏLAVƏ EDİLMİR — çünki fillPublicKey zamanı
     * bank cavabı hələ məlum deyil. Kod yalnız callback-dən sonra bilinir.
     * Frontend orderId ilə /api/v1/payments/{orderId}/status sorğusu edib kodu oxuya bilər.
     */
    private EpointPaymentRequest fillPublicKey(EpointPaymentRequest request, String key) {
        if (request.publicKey() != null) {
            return request;
        }
        return EpointPaymentRequest.builder()
                .publicKey(properties.getPublicKey())
                .language(request.language() != null ? request.language() : "az")
                .orderId(request.orderId())
                .amount(request.amount())
                .currency(request.currency())
                .description(request.description())
                // result_url — həmişə sabit, bizim server-side callback endpoint
                .resultUrl(properties.getResultUrl())
                // success/error — istifadəçi brauzeri üçün, dil prefiksi ilə
                .successRedirectUrl(getDynamicSuccessUrl(request.successRedirectUrl(), key))
                .errorRedirectUrl(getDynamicErrorUrl(request.errorRedirectUrl(), key))
                .isInstallment(request.isInstallment() != null ? request.isInstallment() : 0)
                .refund(request.refund() != null ? request.refund() : 0)
                .otherAttr(request.otherAttr())
                .autoPaymentEnabled(request.autoPaymentEnabled())
                .build();
    }

    private EpointCardRegistrationRequest fillPublicKey(EpointCardRegistrationRequest request, String key) {
        if (request.publicKey() != null) {
            return request;
        }
        return EpointCardRegistrationRequest.builder()
                .publicKey(properties.getPublicKey())
                .language(request.language() != null ? request.language() : "az")
                .refund(request.refund())
                .description(request.description())
                .resultUrl(properties.getResultUrl())
                .successRedirectUrl(getDynamicSuccessUrl(request.successRedirectUrl(), key))
                .errorRedirectUrl(getDynamicErrorUrl(request.errorRedirectUrl(), key))
                .build();
    }

    private EpointExecutePayRequest fillPublicKey(EpointExecutePayRequest request, String key) {
        if (request.publicKey() != null) {
            return request;
        }
        return EpointExecutePayRequest.builder()
                .publicKey(properties.getPublicKey())
                .language(request.language() != null ? request.language() : "az")
                .orderId(request.orderId())
                .amount(request.amount())
                .currency(request.currency())
                .description(request.description())
                .resultUrl(properties.getResultUrl())
                .successRedirectUrl(getDynamicSuccessUrl(request.successRedirectUrl(), key))
                .errorRedirectUrl(getDynamicErrorUrl(request.errorRedirectUrl(), key))
                .cardId(request.cardId())
                .isInstallment(request.isInstallment() != null ? request.isInstallment() : 0)
                .autoPaymentEnabled(request.autoPaymentEnabled())
                .build();
    }

    private EpointSplitPaymentRequest fillPublicKey(EpointSplitPaymentRequest request, String key) {
        if (request.publicKey() != null) {
            return request;
        }
        return EpointSplitPaymentRequest.builder()
                .publicKey(properties.getPublicKey())
                .language(request.language() != null ? request.language() : "az")
                .orderId(request.orderId())
                .amount(request.amount())
                .currency(request.currency())
                .description(request.description())
                .resultUrl(properties.getResultUrl())
                .successRedirectUrl(getDynamicSuccessUrl(request.successRedirectUrl(), key))
                .errorRedirectUrl(getDynamicErrorUrl(request.errorRedirectUrl(), key))
                .splitUser(request.splitUser())
                .splitAmount(request.splitAmount())
                .build();
    }

    private EpointSplitExecutePayRequest fillPublicKey(EpointSplitExecutePayRequest request, String key) {
        if (request.publicKey() != null) {
            return request;
        }
        return EpointSplitExecutePayRequest.builder()
                .publicKey(properties.getPublicKey())
                .language(request.language() != null ? request.language() : "az")
                .orderId(request.orderId())
                .amount(request.amount())
                .currency(request.currency())
                .description(request.description())
                .resultUrl(properties.getResultUrl())
                .successRedirectUrl(getDynamicSuccessUrl(request.successRedirectUrl(), key))
                .errorRedirectUrl(getDynamicErrorUrl(request.errorRedirectUrl(), key))
                .splitUser(request.splitUser())
                .splitAmount(request.splitAmount())
                .cardId(request.cardId())
                .build();
    }

    private EpointPreAuthCompleteRequest fillPublicKey(EpointPreAuthCompleteRequest request) {
        if (request.publicKey() != null) {
            return request;
        }
        return EpointPreAuthCompleteRequest.builder()
                .publicKey(properties.getPublicKey())
                .amount(request.amount())
                .transaction(request.transaction())
                .build();
    }

    private EpointWidgetRequest fillPublicKey(EpointWidgetRequest request, String key) {
        if (request.publicKey() != null) {
            return request;
        }
        return EpointWidgetRequest.builder()
                .publicKey(properties.getPublicKey())
                .amount(request.amount())
                .currency(request.currency())
                .orderId(request.orderId())
                .description(request.description())
                .successRedirectUrl(getDynamicSuccessUrl(request.successRedirectUrl(), key))
                .errorRedirectUrl(getDynamicErrorUrl(request.errorRedirectUrl(), key))
                .resultUrl(properties.getResultUrl())
                .autoPaymentEnabled(request.autoPaymentEnabled())
                .build();
    }

    private EpointWalletPaymentRequest fillPublicKey(EpointWalletPaymentRequest request) {
        if (request.publicKey() != null) {
            return request;
        }
        return EpointWalletPaymentRequest.builder()
                .publicKey(properties.getPublicKey())
                .walletId(request.walletId())
                .amount(request.amount())
                .currency(request.currency())
                .orderId(request.orderId())
                .description(request.description())
                .language(request.language() != null ? request.language() : "az")
                .build();
    }

    private EpointInvoiceCreateRequest fillPublicKey(EpointInvoiceCreateRequest request) {
        if (request.publicKey() != null) {
            return request;
        }
        return EpointInvoiceCreateRequest.builder()
                .publicKey(properties.getPublicKey())
                .sum(request.sum())
                .display(request.display())
                .saveAsTemplate(request.saveAsTemplate())
                .statusInstallment(request.statusInstallment())
                .name(request.name())
                .description(request.description())
                .phone(request.phone())
                .email(request.email())
                .inn(request.inn())
                .contractNumber(request.contractNumber())
                .merchantOrderId(request.merchantOrderId())
                .periodFrom(request.periodFrom())
                .periodTo(request.periodTo())
                .invoiceImages(request.invoiceImages())
                .build();
    }

    private EpointInvoiceUpdateRequest fillPublicKey(EpointInvoiceUpdateRequest request) {
        if (request.publicKey() != null) {
            return request;
        }
        return EpointInvoiceUpdateRequest.builder()
                .publicKey(properties.getPublicKey())
                .id(request.id())
                .sum(request.sum())
                .display(request.display())
                .saveAsTemplate(request.saveAsTemplate())
                .statusInstallment(request.statusInstallment())
                .name(request.name())
                .description(request.description())
                .phone(request.phone())
                .email(request.email())
                .inn(request.inn())
                .contractNumber(request.contractNumber())
                .merchantOrderId(request.merchantOrderId())
                .periodFrom(request.periodFrom())
                .periodTo(request.periodTo())
                .invoiceImages(request.invoiceImages())
                .build();
    }

    private EpointInvoiceActionRequest fillPublicKey(EpointInvoiceActionRequest request) {
        if (request.publicKey() != null) {
            return request;
        }
        return EpointInvoiceActionRequest.builder()
                .publicKey(properties.getPublicKey())
                .id(request.id())
                .phone(request.phone())
                .email(request.email())
                .type(request.type())
                .order(request.order())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────
    // Redirect URL həlli — dil prefiksi + Origin/Referer header-dən
    // ─────────────────────────────────────────────────────────────────

    public String getApiBaseUrl() {
        try {
            String resultUrl = properties.getResultUrl();
            if (resultUrl != null && !resultUrl.isBlank()) {
                URL url = new URL(resultUrl);
                String protocol = url.getProtocol();
                String authority = url.getAuthority();
                return protocol + "://" + authority;
            }
        } catch (Exception e) {
            log.error("[Redirection] Failed to parse resultUrl", e);
        }
        return "";
    }

    /**
     * Success redirect URL-i dinamik həll edir.
     * Əgər request-də açıq URL verilib onu qaytarır.
     * Əks halda Origin/Referer header-dən base URL çıxarır,
     * istifadəçinin dilinə uyğun path qurur.
     * Heç biri yoxdursa — application.yml-dakı default qaytarılır.
     */
    private String getDynamicSuccessUrl(String explicitUrl, String key) {
        String targetUrl = explicitUrl;
        if (targetUrl == null) {
            Long userId = getCurrentUserId();
            String lang = resolveLanguage(userId);
            String path = "/" + lang + "/payment/success";
            String dynamic = extractUrlFromRequest(path);
            targetUrl = dynamic != null ? dynamic : properties.getSuccessRedirectUrl();
            log.info("[Redirection] Resolved target success URL: {} (userId: {}, lang: {})", targetUrl, userId, lang);
        }

        if (key != null && !key.isBlank()) {
            String redisKey = "payment-redirect:success:" + key;
            try {
                redisTemplate.opsForValue().set(redisKey, targetUrl, 1, java.util.concurrent.TimeUnit.HOURS);
                log.info("[Redirection] Cached success URL under key: {}", redisKey);
            } catch (Exception e) {
                log.error("[Redirection] Failed to cache success URL in Redis", e);
            }

            String apiBaseUrl = getApiBaseUrl();
            if (!apiBaseUrl.isBlank()) {
                return apiBaseUrl + "/payment/redirect/success/" + key;
            }
        }
        return targetUrl;
    }

    /**
     * Error redirect URL-i dinamik həll edir.
     */
    private String getDynamicErrorUrl(String explicitUrl, String key) {
        String targetUrl = explicitUrl;
        if (targetUrl == null) {
            Long userId = getCurrentUserId();
            String lang = resolveLanguage(userId);
            String path = "/" + lang + "/payment/error";
            String dynamic = extractUrlFromRequest(path);
            targetUrl = dynamic != null ? dynamic : properties.getErrorRedirectUrl();
            log.info("[Redirection] Resolved target error URL: {} (userId: {}, lang: {})", targetUrl, userId, lang);
        }

        if (key != null && !key.isBlank()) {
            String redisKey = "payment-redirect:error:" + key;
            try {
                redisTemplate.opsForValue().set(redisKey, targetUrl, 1, java.util.concurrent.TimeUnit.HOURS);
                log.info("[Redirection] Cached error URL under key: {}", redisKey);
            } catch (Exception e) {
                log.error("[Redirection] Failed to cache error URL in Redis", e);
            }

            String apiBaseUrl = getApiBaseUrl();
            if (!apiBaseUrl.isBlank()) {
                return apiBaseUrl + "/payment/redirect/error/" + key;
            }
        }
        return targetUrl;
    }

    /**
     * İstifadəçinin dilini gRPC ilə alır.
     * Xəta olduqda və ya userId null olduqda "az" qaytarılır.
     */
    private String resolveLanguage(Long userId) {
        try {
            String lang = userGrpcClient.getUserLanguage(userId);
            return (lang != null && !lang.isBlank()) ? lang : "az";
        } catch (Exception e) {
            log.warn("[Redirection] Could not fetch language for userId={}: {}", userId, e.getMessage());
            return "az";
        }
    }

    /**
     * SecurityContext-dən cari autentifikasiya olunmuş userId-i alır.
     */
    private Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Long userId) {
                return userId;
            }
        } catch (Exception e) {
            log.warn("[Redirection] Could not get userId from SecurityContext: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Gelen HTTP sorğusunun Origin və ya Referer header-indən
     * frontend base URL-ini çıxarır, verilmiş path ilə birləşdirir.
     *
     * Nümunə:
     *   Origin: https://fitnest.az  + path: /az/payment/success
     *   → https://fitnest.az/az/payment/success
     *
     * Əgər header tapılmasa və ya tanınmayan host olarsa null qaytarır —
     * çağıran metod application.yml default-una düşür.
     */
    private String extractUrlFromRequest(String path) {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
                return null;
            }

            HttpServletRequest request = servletAttrs.getRequest();

            String origin       = request.getHeader("Origin");
            String referer      = request.getHeader("Referer");
            String hostHeader   = request.getHeader("Host");
            String forwardedHost = request.getHeader("X-Forwarded-Host");

            log.debug("[Redirection] Headers — Origin: {}, Referer: {}, Host: {}, X-Forwarded-Host: {}",
                    origin, referer, hostHeader, forwardedHost);

            // 1. Origin header-dən birbaşa al
            String baseUrl = origin;

            // 2. Origin yoxdursa Referer-dən scheme+host çıxar
            if (baseUrl == null || baseUrl.isBlank()) {
                if (referer != null && !referer.isBlank()) {
                    URL url = new URL(referer);
                    int port = url.getPort();
                    baseUrl = url.getProtocol() + "://" + url.getHost()
                            + (port != -1 ? ":" + port : "");
                }
            }

            // 3. Tanınan frontend domain-dirsə istifadə et
            if (baseUrl != null && !baseUrl.isBlank()) {
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }
                if (baseUrl.contains("fitnest.az") || baseUrl.contains("localhost")) {
                    log.debug("[Redirection] Using base URL from Origin/Referer: {}", baseUrl);
                    return baseUrl + path;
                }
            }

            // 4. Host header-dən mühit müəyyən et (API gateway arxasındayıq)
            String targetHost = (forwardedHost != null && !forwardedHost.isBlank())
                    ? forwardedHost
                    : hostHeader;

            if (targetHost != null) {
                if (targetHost.contains("api.fitnest.az")) {
                    log.debug("[Redirection] API host detected → redirecting to fitnest.az");
                    return "https://fitnest.az" + path;
                }
                if (targetHost.contains("dev-api.fitnest.az")) {
                    log.debug("[Redirection] DEV API host detected → redirecting to dev.fitnest.az");
                    return "https://dev.fitnest.az" + path;
                }
            }

        } catch (Exception e) {
            log.warn("[Redirection] Could not extract dynamic URL from request: {}", e.getMessage());
        }
        return null;
    }
}