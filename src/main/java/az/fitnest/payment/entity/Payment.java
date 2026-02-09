package az.fitnest.payment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payments")
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
}
