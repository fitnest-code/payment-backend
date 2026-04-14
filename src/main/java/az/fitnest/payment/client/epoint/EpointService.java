package az.fitnest.payment.client.epoint;

import az.fitnest.payment.dto.epoint.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import okhttp3.Request;
import okhttp3.Response;

@Service
@RequiredArgsConstructor
public class EpointService {

    private static final Logger log = LoggerFactory.getLogger(EpointService.class);

    private final EpointHttpClient httpClient;
    private final EpointProperties properties;

    public EpointResponse createPayment(EpointPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/request", request);
    }

    public EpointResponse getStatus(String transactionId) {
        EpointStatusRequest request = new EpointStatusRequest(
            properties.getPublicKey(),
            transactionId
        );
        return httpClient.postSigned("/get-status", request);
    }

    public EpointResponse cardRegistration(EpointCardRegistrationRequest request) {
        log.info("EpointService.cardRegistration: request.publicKey={}, properties.publicKey={}, env.EPOINT_PUBLIC_KEY={}", request.publicKey(), properties.getPublicKey(), System.getenv("EPOINT_PUBLIC_KEY"));
        request = fillPublicKey(request);
        log.info("EpointService.cardRegistration after fillPublicKey: request.publicKey={}", request.publicKey());
        return httpClient.postSigned("/card-registration", request);
    }

    public EpointResponse executePay(EpointExecutePayRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/execute-pay", request);
    }

    public EpointResponse cardRegistrationWithPay(EpointPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/card-registration-with-pay", request);
    }

    public EpointResponse refundRequest(EpointExecutePayRequest request) {
        request = fillPublicKey(request);
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
            .successRedirectUrl(getDynamicSuccessUrl(null))
            .errorRedirectUrl(getDynamicErrorUrl(null))
            .build();
        return httpClient.postSigned("/reverse", request);
    }

    public EpointResponse splitRequest(EpointSplitPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/split-request", request);
    }

    public EpointResponse createWidgetUrl(EpointWidgetRequest request) {
        if (request.getPublicKey() == null) {
            request = request.setPublicKey(properties.getPublicKey());
        }
        return httpClient.postSigned("/token/widget", request);
    }

    private EpointPaymentRequest fillPublicKey(EpointPaymentRequest request) {
        if (request.publicKey() == null) {
            return EpointPaymentRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .language(request.language())
                    .orderId(request.orderId())
                    .amount(request.amount())
                    .currency(request.currency())
                    .description(request.description())
                    .resultUrl(request.resultUrl() != null ? request.resultUrl() : properties.getResultUrl())
                    .successRedirectUrl(getDynamicSuccessUrl(request.successRedirectUrl()))
                    .errorRedirectUrl(getDynamicErrorUrl(request.errorRedirectUrl()))
                    .isInstallment(request.isInstallment())
                    .refund(request.refund())
                    .otherAttr(request.otherAttr())
                    .build();
        }
        return request;
    }

    private EpointCardRegistrationRequest fillPublicKey(EpointCardRegistrationRequest request) {
        if (request.publicKey() == null) {
            return EpointCardRegistrationRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .language(request.language())
                    .refund(request.refund())
                    .description(request.description())
                    .resultUrl(request.resultUrl() != null ? request.resultUrl() : properties.getResultUrl())
                    .successRedirectUrl(getDynamicSuccessUrl(request.successRedirectUrl()))
                    .errorRedirectUrl(getDynamicErrorUrl(request.errorRedirectUrl()))
                    .build();
        }
        return request;
    }

    private EpointExecutePayRequest fillPublicKey(EpointExecutePayRequest request) {
        if (request.publicKey() == null) {
            return EpointExecutePayRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .language(request.language())
                    .orderId(request.orderId())
                    .amount(request.amount())
                    .currency(request.currency())
                    .description(request.description())
                    .resultUrl(request.resultUrl())
                    .successRedirectUrl(getDynamicSuccessUrl(request.successRedirectUrl()))
                    .errorRedirectUrl(getDynamicErrorUrl(request.errorRedirectUrl()))
                    .cardId(request.cardId())
                    .isInstallment(request.isInstallment())
                    .build();
        }
        return request;
    }

    private EpointSplitPaymentRequest fillPublicKey(EpointSplitPaymentRequest request) {
        if (request.publicKey() == null) {
            return EpointSplitPaymentRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .language(request.language())
                    .orderId(request.orderId())
                    .amount(request.amount())
                    .currency(request.currency())
                    .description(request.description())
                    .resultUrl(request.resultUrl() != null ? request.resultUrl() : properties.getResultUrl())
                    .successRedirectUrl(getDynamicSuccessUrl(request.successRedirectUrl()))
                    .errorRedirectUrl(getDynamicErrorUrl(request.errorRedirectUrl()))
                    .splitUser(request.splitUser())
                    .splitAmount(request.splitAmount())
                    .build();
        }
        return request;
    }

    private EpointSplitExecutePayRequest fillPublicKey(EpointSplitExecutePayRequest request) {
        if (request.publicKey() == null) {
            return EpointSplitExecutePayRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .language(request.language())
                    .orderId(request.orderId())
                    .amount(request.amount())
                    .currency(request.currency())
                    .description(request.description())
                    .resultUrl(request.resultUrl() != null ? request.resultUrl() : properties.getResultUrl())
                    .successRedirectUrl(getDynamicSuccessUrl(request.successRedirectUrl()))
                    .errorRedirectUrl(getDynamicErrorUrl(request.errorRedirectUrl()))
                    .splitUser(request.splitUser())
                    .splitAmount(request.splitAmount())
                    .cardId(request.cardId())
                    .build();
        }
        return request;
    }

    private EpointPreAuthCompleteRequest fillPublicKey(EpointPreAuthCompleteRequest request) {
        if (request.publicKey() == null) {
            return EpointPreAuthCompleteRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .amount(request.amount())
                    .transaction(request.transaction())
                    .build();
        }
        return request;
    }

    private EpointWalletPaymentRequest fillPublicKey(EpointWalletPaymentRequest request) {
        if (request.publicKey() == null) {
            return EpointWalletPaymentRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .walletId(request.walletId())
                    .amount(request.amount())
                    .currency(request.currency())
                    .orderId(request.orderId())
                    .description(request.description())
                    .language(request.language())
                    .build();
        }
        return request;
    }

    private EpointInvoiceCreateRequest fillPublicKey(EpointInvoiceCreateRequest request) {
        if (request.publicKey() == null) {
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
        return request;
    }

    private EpointInvoiceUpdateRequest fillPublicKey(EpointInvoiceUpdateRequest request) {
        if (request.publicKey() == null) {
            return EpointInvoiceUpdateRequest.builder()
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
                    .id(request.id())
                    .build();
        }
        return request;
    }

    private EpointInvoiceActionRequest fillPublicKey(EpointInvoiceActionRequest request) {
        if (request.publicKey() == null) {
            return EpointInvoiceActionRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .id(request.id())
                    .phone(request.phone())
                    .email(request.email())
                    .type(request.type())
                    .order(request.order())
                    .build();
        }
        return request;
    }

    public EpointResponse splitExecutePay(EpointSplitExecutePayRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/split-execute-pay", request);
    }

    public EpointResponse splitCardRegistrationWithPay(EpointSplitPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/split-card-registration-with-pay", request);
    }

    public EpointResponse preAuthRequest(EpointPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/pre-auth-request", request);
    }

    public EpointResponse preAuthComplete(EpointPreAuthCompleteRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/pre-auth-complete", request);
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

    private String getDynamicSuccessUrl(String explicitUrl) {
        if (explicitUrl != null) return explicitUrl;
        String dynamic = extractUrlFromRequest("/az/payment/success");
        return dynamic != null ? dynamic : properties.getSuccessRedirectUrl();
    }

    private String getDynamicErrorUrl(String explicitUrl) {
        if (explicitUrl != null) return explicitUrl;
        String dynamic = extractUrlFromRequest("/az/payment/error");
        return dynamic != null ? dynamic : properties.getErrorRedirectUrl();
    }

    private String extractUrlFromRequest(String path) {
        try {
            org.springframework.web.context.request.RequestAttributes attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
                jakarta.servlet.http.HttpServletRequest request = servletAttrs.getRequest();
                
                String origin = request.getHeader("Origin");
                if (origin == null || origin.isBlank()) {
                    origin = request.getHeader("Referer");
                    if (origin != null && !origin.isBlank()) {
                        java.net.URL url = new java.net.URL(origin);
                        origin = url.getProtocol() + "://" + url.getHost() + (url.getPort() != -1 ? ":" + url.getPort() : "");
                    }
                }
                
                if (origin != null && !origin.isBlank()) {
                    if (origin.endsWith("/")) {
                        origin = origin.substring(0, origin.length() - 1);
                    }
                    if (origin.contains("dev.fitnest.az") || origin.contains("localhost")) {
                         return origin + path; // support localhost and dev.fitnest.az dynamically
                    }
                }
                
                String host = request.getHeader("X-Forwarded-Host");
                if (host == null || host.isBlank()) {
                    host = request.getHeader("Host");
                }
                if (host != null && host.contains("dev-api.fitnest.az")) {
                    return "https://dev.fitnest.az" + path;
                }
            }
        } catch (Exception e) {
            log.warn("Could not extract dynamic URL from request: {}", e.getMessage());
        }
        return null;
    }
}
