package az.fitnest.payment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_status", columnList = "status"),
        @Index(name = "idx_payments_transaction_id", columnList = "transaction_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Payment {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;
    
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

    @Column(name = "transaction_id")
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
}
