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

            Payment payment2 = new Payment();
            payment2.setProvider("STRIPE");
            payment2.setPaymentIntentClientSecret("pi_test_secret_124");
            payment2.setStatus("PENDING");
            payment2.setOrderId("ORD998");
            payment2.setTransactionId("txn_test_124");
            payment2.setAmount(19.99);
            payment2.setCurrency("AZN");
            payment2.setCardMask("555555****4444");
            payment2.setCardName("John Doe");
            payment2.setMessage("Awaiting confirmation");

            paymentRepository.save(payment2);

            Payment payment3 = new Payment();
            payment3.setProvider("STRIPE");
            payment3.setPaymentIntentClientSecret("pi_test_secret_125");
            payment3.setStatus("FAILED");
            payment3.setOrderId("ORD997");
            payment3.setTransactionId("txn_test_125");
            payment3.setAmount(99.99);
            payment3.setCurrency("AZN");
            payment3.setCardMask("411111****1111");
            payment3.setCardName("Jane Smith");
            payment3.setMessage("Insufficient funds");

            paymentRepository.save(payment3);
        }
    }
}
