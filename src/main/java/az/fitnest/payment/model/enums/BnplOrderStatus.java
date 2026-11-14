package az.fitnest.payment.model.enums;

import java.util.Locale;
import java.util.Set;

/**
 * ABB BNPL order status values from partner integration doc.
 */
public enum BnplOrderStatus {
    INIT,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    CLOSED,
    REJECTED,
    EXPIRED,
    FAILED,
    REVERSED;

    private static final Set<BnplOrderStatus> TERMINAL = Set.of(
            COMPLETED, CANCELLED, CLOSED, REJECTED, EXPIRED, FAILED, REVERSED
    );

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isSuccess() {
        return this == COMPLETED;
    }

    public static BnplOrderStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BnplOrderStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * FitNest Payment.status mapping.
     *
     * <p>{@link #CLOSED} is one of ABB's credit-application statuses and must not be
     * collapsed into {@code FAILED}: doing so downgraded already successful payments.
     * It gets its own {@code CLOSED} payment status; callers keep a prior {@code SUCCESS}
     * untouched (see {@code BnplContractRules#resolvePaymentStatus}).</p>
     */
    public String toPaymentStatus() {
        return switch (this) {
            case INIT, IN_PROGRESS -> "PENDING_USER_ACTION";
            case COMPLETED -> "SUCCESS";
            case CLOSED -> "CLOSED";
            case CANCELLED, REJECTED, EXPIRED, FAILED -> "FAILED";
            case REVERSED -> "REVERSED";
        };
    }

    /** Mobile-facing Azerbaijani label (PO mapping). */
    public String toUserMessageAz() {
        return switch (this) {
            case INIT -> "Sorğu yaradıldı";
            case IN_PROGRESS -> "Sorğu nəzərdən keçirilir";
            case COMPLETED -> "Ödəniş uğurla tamamlandı";
            case CANCELLED -> "Sorğu ləğv edildi";
            case CLOSED -> "Sorğu bağlandı";
            case REJECTED -> "Sorğu təsdiqlənmədi";
            case EXPIRED -> "Sorğunun müddəti bitdi";
            case FAILED -> "Əməliyyat baş tutmadı";
            case REVERSED -> "Ödəniş geri qaytarıldı";
        };
    }
}
