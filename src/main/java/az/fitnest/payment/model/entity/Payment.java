package az.fitnest.payment.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_status", columnList = "status"),
        @Index(name = "idx_payments_transaction_id", columnList = "transaction_id", unique = true),
        @Index(name = "idx_payments_order_id", columnList = "order_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Payment extends BaseAuditableEntity {

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "payment_method_id")
    private String paymentMethodId;

    @Column(name = "payment_intent_client_secret")
    private String paymentIntentClientSecret;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "order_id", unique = true)
    private String orderId;

    @Column(name = "transaction_id", unique = true)
    private String transactionId;

    @Column(name = "amount")
    private Double amount;

    @Column(name = "currency")
    private String currency;

    @Column(name = "rrn")
    private String rrn;

    @Column(name = "card_mask")
    private String cardMask;

    @Column(name = "card_name")
    private String cardName;

    @Column(name = "message")
    private String message;

    @Column(name = "bank_transaction")
    private String bankTransaction;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "description")
    private String description;

    @Column(name = "redirect_url")
    private String redirectUrl;

    @Column(name = "code")
    private String code;

    @Column(name = "bank_response")
    private String bankResponse;

    @Column(name = "operation_code")
    private String operationCode;

    @Column(name = "callback_processed")
    private Boolean callbackProcessed = false;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "card_id")
    private String cardId;

    @Column(name = "type")
    private String type;

    @Column(name = "auto_payment_enabled")
    private Boolean autoPaymentEnabled = false;

    public void setCardId(String cardId) {
        this.cardId = cardId;
    }
}
