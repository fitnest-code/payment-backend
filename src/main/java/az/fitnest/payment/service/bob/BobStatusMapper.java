package az.fitnest.payment.service.bob;

import az.fitnest.payment.dto.bob.BobOrderStatusResponse;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.enums.BobPaymentStatus;
import az.fitnest.payment.util.CardBrandDetector;
import az.fitnest.payment.util.PaymentTypeLabels;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Maps SmartVista status payloads to internal status/messages and enriches DTOs for clients.
 */
@Component
public class BobStatusMapper {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");

    public BobPaymentStatus toBobStatus(Integer orderStatus) {
        return BobPaymentStatus.fromCode(orderStatus != null ? orderStatus : -1);
    }

    public boolean isApproved(Integer orderStatus) {
        return toBobStatus(orderStatus) == BobPaymentStatus.APPROVED;
    }

    /**
     * Non-final states: do not mark FAILED yet (3DS / still open for payment).
     * REGISTERED(0), AUTHORIZED(1) for two-phase hold, AUTHENTICATION_INITIATED(5).
     */
    public boolean isInProgress(Integer orderStatus) {
        BobPaymentStatus status = toBobStatus(orderStatus);
        return status == BobPaymentStatus.REGISTERED
                || status == BobPaymentStatus.AUTHORIZED
                || status == BobPaymentStatus.AUTHENTICATION_INITIATED;
    }

    /** Terminal failure states that should mark the payment FAILED in our DB. */
    public boolean isTerminalFailure(Integer orderStatus) {
        BobPaymentStatus status = toBobStatus(orderStatus);
        return status == BobPaymentStatus.DECLINED
                || status == BobPaymentStatus.REVERSED;
    }

    /**
     * Human-readable decline reason. Never returns bare API "Success" when payment failed.
     */
    public String declineMessage(BobOrderStatusResponse statusResponse) {
        if (statusResponse == null) {
            return "Payment declined";
        }

        String actionDesc = statusResponse.getActionCodeDescription();
        if (actionDesc != null && !actionDesc.isBlank() && !isGenericSuccess(actionDesc)) {
            return actionDesc.trim();
        }

        String actionCode = statusResponse.getActionCode();
        BobPaymentStatus bobStatus = toBobStatus(statusResponse.getOrderStatus());
        if (actionCode != null && !actionCode.isBlank()) {
            String label = bobStatus != BobPaymentStatus.UNKNOWN
                    ? bobStatus.getDescription()
                    : "Payment declined";
            return label + " (actionCode=" + actionCode.trim() + ")";
        }

        String errorMessage = statusResponse.getErrorMessage();
        if (errorMessage != null && !errorMessage.isBlank() && !isGenericSuccess(errorMessage)) {
            return errorMessage.trim();
        }

        if (bobStatus != BobPaymentStatus.UNKNOWN && bobStatus != BobPaymentStatus.APPROVED) {
            return bobStatus.getDescription();
        }
        return "Payment declined";
    }

    public String operationCode(BobOrderStatusResponse statusResponse) {
        if (statusResponse == null || statusResponse.getActionCode() == null) {
            return null;
        }
        String code = statusResponse.getActionCode().trim();
        return code.isEmpty() ? null : code;
    }

    public void enrichStatusResponse(BobOrderStatusResponse statusResponse, Payment payment) {
        enrichStatusResponse(statusResponse, payment, "AZ");
    }

    public void enrichStatusResponse(BobOrderStatusResponse statusResponse, Payment payment, String lang) {
        if (statusResponse == null) {
            return;
        }

        statusResponse.flattenBankPayload();

        // errorCode/errorMessage="Success" only means the status API call succeeded —
        // not that the payment was approved. Replace misleading Success on declines.
        if (isTerminalFailure(statusResponse.getOrderStatus())
                && isGenericSuccess(statusResponse.getErrorMessage())) {
            statusResponse.setErrorMessage(declineMessage(statusResponse));
        }

        String rrn = statusResponse.getRrn();
        if (rrn == null || rrn.isBlank()) {
            rrn = statusResponse.getAuthRefNum();
        }
        if ((rrn == null || rrn.isBlank()) && payment != null && payment.getRrn() != null) {
            rrn = payment.getRrn();
        }
        statusResponse.setRrn(rrn);

        String formattedDate = null;
        if (statusResponse.getAuthDateTime() != null && !statusResponse.getAuthDateTime().isBlank()) {
            formattedDate = formatTimestampOrString(statusResponse.getAuthDateTime());
        } else if (statusResponse.getDate() != null && !statusResponse.getDate().isBlank()) {
            formattedDate = formatTimestampOrString(statusResponse.getDate());
        } else if (payment != null && payment.getCreatedDate() != null) {
            formattedDate = payment.getCreatedDate().format(DISPLAY_FORMAT);
        }
        statusResponse.setFormattedDate(formattedDate);

        String cardMask = statusResponse.getPan();
        if ((cardMask == null || cardMask.isBlank()) && payment != null) {
            cardMask = payment.getCardMask();
        }
        statusResponse.setCardMask(cardMask);

        if ((statusResponse.getCardholderName() == null || statusResponse.getCardholderName().isBlank())
                && payment != null && payment.getCardName() != null) {
            statusResponse.setCardholderName(payment.getCardName());
        }

        if ((statusResponse.getApprovalCode() == null || statusResponse.getApprovalCode().isBlank())
                && payment != null && payment.getCode() != null) {
            statusResponse.setApprovalCode(payment.getCode());
        }

        statusResponse.setCardBrand(resolveCardBrand(statusResponse, cardMask));
        statusResponse.setBank("Bank of Baku");
        statusResponse.setType(resolvePaymentTypeLabel(payment, lang));
    }

    public String resolveCardBrand(BobOrderStatusResponse statusResponse, String cardMask) {
        String paymentSystem = statusResponse != null ? statusResponse.getResolvedPaymentSystem() : null;
        if (paymentSystem != null && !paymentSystem.isBlank()) {
            return normalizePaymentSystem(paymentSystem);
        }
        String detected = CardBrandDetector.detectBrand(cardMask);
        return detected != null ? detected : "UNKNOWN";
    }

    public String resolvePaymentTypeLabel(Payment payment) {
        return resolvePaymentTypeLabel(payment, "AZ");
    }

    public String resolvePaymentTypeLabel(Payment payment, String lang) {
        if (payment == null) {
            return PaymentTypeLabels.translate("ONE_TIME", lang);
        }
        return PaymentTypeLabels.fromPayment(payment.getType(), payment.getAutoPaymentEnabled(), lang);
    }

    private static String normalizePaymentSystem(String paymentSystem) {
        String upper = paymentSystem.trim().toUpperCase();
        if (upper.contains("VISA")) {
            return "VISA";
        }
        if (upper.contains("MASTER")) {
            return "MASTERCARD";
        }
        if (upper.contains("AMEX") || upper.contains("AMERICAN")) {
            return "AMEX";
        }
        if (upper.contains("DISCOVER")) {
            return "DISCOVER";
        }
        return upper;
    }

    private static boolean isGenericSuccess(String value) {
        return value != null && "success".equalsIgnoreCase(value.trim());
    }

    private static String formatTimestampOrString(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        try {
            long ts = Long.parseLong(rawDate.trim());
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), BAKU).format(DISPLAY_FORMAT);
        } catch (Exception e) {
            return rawDate;
        }
    }
}
