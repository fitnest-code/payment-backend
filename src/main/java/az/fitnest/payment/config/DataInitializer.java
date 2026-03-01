package az.fitnest.payment.config;

import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private final PaymentRepository paymentRepository;

    @Bean
    public CommandLineRunner initPaymentData() {
        return args -> {
            initPayments();
        };
    }

    private void initPayments() {
        if (paymentRepository.count() == 0) {
            Payment payment = new Payment();
            payment.setProvider("STRIPE");
            payment.setPaymentIntentClientSecret("pi_test_secret_123");
            payment.setStatus("SUCCEEDED");
            payment.setOrderId("ORD999");
            payment.setTransactionId("txn_test_123");
            payment.setAmount(29.99);
            payment.setCurrency("AZN");
            payment.setCardMask("424242****4242");
            payment.setCardName("Test Card");
            payment.setMessage("Payment successful");

            paymentRepository.save(payment);
        }
    }
}
