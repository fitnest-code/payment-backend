package az.fitnest.payment.event;

/**
 * Event kinds carried by the payment outbox, and where each one is delivered.
 */
public final class OutboxEventType {

    private OutboxEventType() {
    }

    /** Topic carrying payment lifecycle events for other services (notifications, analytics). */
    public static final String TOPIC_PAYMENT_EVENTS = "payment-events";

    /** Topic carrying saved-card lifecycle events. */
    public static final String TOPIC_CARD_EVENTS = "card-events";

    public static final String PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    /**
     * Emitted when a successful payment is fully reversed (BNPL partial reversals that add
     * up to the full amount included). Kept separate from {@code PAYMENT_FAILED} so
     * notifications and analytics do not treat a refund as a failed charge.
     */
    public static final String PAYMENT_REVERSED = "PAYMENT_REVERSED";
    public static final String CARD_REGISTERED = "CARD_REGISTERED";

    /**
     * Delivered over gRPC to order-backend rather than to Kafka.
     *
     * <p>Kept as an outbox event so a failed assignment is retried durably. It used to be a
     * fire-and-forget call inside the callback transaction whose failure was swallowed, which
     * left customers charged but without a subscription.</p>
     */
    public static final String SUBSCRIPTION_ASSIGNMENT_REQUESTED = "SUBSCRIPTION_ASSIGNMENT_REQUESTED";
}
