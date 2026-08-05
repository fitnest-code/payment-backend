package az.fitnest.payment.service.bob;

import az.fitnest.payment.dto.bob.BobOrderStatusResponse;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.enums.BobPaymentStatus;
import az.fitnest.payment.util.CardBrandDetector;
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
        if (statusResponse == null) {
            return;
        }

        String rrn = statusResponse.getRrn();
        if (rrn == null || rrn.isBlank()) {
            rrn = statusResponse.getAuthRefNum();
        }
        if ((rrn == null || rrn.isBlank()) && payment != null) {
            rrn = payment.getTransactionId();
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

        String cardBrand = CardBrandDetector.detectBrand(cardMask);
        statusResponse.setCardBrand(cardBrand != null ? cardBrand : "UNKNOWN");
        statusResponse.setBank("Bank of Baku");
        statusResponse.setType(resolvePaymentTypeLabel(payment));
    }

    public String resolvePaymentTypeLabel(Payment payment) {
        if (payment == null || payment.getType() == null) {
            return "Birdəfəlik";
        }
        String t = payment.getType();
        if (t.toUpperCase().contains("INSTALLMENT") || "BOB_INSTALLMENT".equalsIgnoreCase(t)) {
            return "Taksitli ödəniş";
        }
        if ("AUTO_RENEWAL".equalsIgnoreCase(t)) {
            return "Avtomatik uzadılma";
        }
        if ("CARD_BIND".equalsIgnoreCase(t)) {
            return "Kartın bağlanması";
        }
        if ("SAVED_CARD".equalsIgnoreCase(t)) {
            return "Yadda saxlanılmış kart";
        }
        return "Birdəfəlik";
    }

    private static boolean isGenericSuccess(String value) {
        return "success".equalsIgnoreCase(value.trim());
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
