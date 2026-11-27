package az.fitnest.payment.model.enums;

/** Internal classification of Kapital Bank order statuses. */
public enum KapitalPaymentStatus {
    /** FullyPaid — only state that grants a subscription. */
    SUCCESS,
    /** Preparing / Authorized — payer may still be on the HPP. */
    IN_PROGRESS,
    /** PartiallyPaid — money moved but not the full amount; needs a human. */
    PARTIALLY_PAID,
    /** Declined / Refused / Rejected / Cancelled / Expired. */
    FAILED,
    /** Refunded / Voided (reversed after payment). */
    REFUNDED,
    /** Unrecognised bank status; never finalize on it. */
    UNKNOWN
}
