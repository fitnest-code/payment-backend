package az.fitnest.payment.config;

import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final PaymentRepository paymentRepository;
    private final UserCardRepository userCardRepository;

    @Bean
    public CommandLineRunner initPaymentData() {
        return args -> {
            log.info("Running DataInitializer...");
            initPayments();
            initUserCards();
            log.info("DataInitializer completed.");
        };
    }

    private void initPayments() {
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
        payment.setType("PAYMENT");
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
        payment2.setType("CARD_REGISTRATION");
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
        payment3.setType("PAYMENT");
        paymentRepository.save(payment3);

        log.info("Seeding payment history for user 1...");
        Payment p1 = new Payment();
        p1.setProvider("STRIPE");
        p1.setPaymentIntentClientSecret("pi_user1_001");
        p1.setStatus("SUCCEEDED");
        p1.setOrderId("U1_ORD001");
        p1.setTransactionId("u1_txn_001");
        p1.setAmount(49.99);
        p1.setCurrency("AZN");
        p1.setCardMask("424242****4242");
        p1.setCardName("Visa Gold");
        p1.setUserId(1L);
        p1.setMessage("Payment successful");
        p1.setDescription("Monthly subscription");
        p1.setCreatedDate(java.time.LocalDateTime.now().minusDays(10));
        p1.setType("PAYMENT");
        paymentRepository.save(p1);

        Payment p2 = new Payment();
        p2.setProvider("STRIPE");
        p2.setPaymentIntentClientSecret("pi_user1_002");
        p2.setStatus("FAILED");
        p2.setOrderId("U1_ORD002");
        p2.setTransactionId("u1_txn_002");
        p2.setAmount(19.99);
        p2.setCurrency("AZN");
        p2.setCardMask("555555****4444");
        p2.setCardName("Mastercard Platinum");
        p2.setUserId(1L);
        p2.setMessage("Insufficient funds");
        p2.setDescription("One-time purchase");
        p2.setCode("INSUFFICIENT_BALANCE");
        p2.setCreatedDate(java.time.LocalDateTime.now().minusMonths(2));
        p2.setType("CARD_REGISTRATION");
        paymentRepository.save(p2);

        Payment p3 = new Payment();
        p3.setProvider("STRIPE");
        p3.setPaymentIntentClientSecret("pi_user1_003");
        p3.setStatus("REFUNDED");
        p3.setOrderId("U1_ORD003");
        p3.setTransactionId("u1_txn_003");
        p3.setAmount(49.99);
        p3.setCurrency("AZN");
        p3.setCardMask("424242****4242");
        p3.setCardName("Visa Gold");
        p3.setUserId(1L);
        p3.setMessage("Refund processed");
        p3.setDescription("Refund for subscription");
        p3.setCreatedDate(java.time.LocalDateTime.now().minusDays(5));
        p3.setType("PAYMENT");
        paymentRepository.save(p3);

        Payment p4 = new Payment();
        p4.setProvider("STRIPE");
        p4.setPaymentIntentClientSecret("pi_user1_004");
        p4.setStatus("SUCCEEDED");
        p4.setOrderId("U1_ORD004");
        p4.setTransactionId("u1_txn_004");
        p4.setAmount(9.99);
        p4.setCurrency("USD");
        p4.setCardMask("411111****1111");
        p4.setCardName("Visa Classic");
        p4.setUserId(1L);
        p4.setMessage("Payment successful");
        p4.setDescription("E-book purchase");
        p4.setCreatedDate(java.time.LocalDateTime.now().minusMonths(3).plusDays(2));
        p4.setType("PAYMENT");
        paymentRepository.save(p4);

        log.info("Seeded payment history for user 1.");
    }

    private void initUserCards() {
        // Always seed user cards for user 1 on startup
        UserCard card1 = UserCard.builder()
                .userId(1L)
                .cardId("card_001")
                .cardMask("424242****4242")
                .cardName("Visa Gold")
                .brand("VISA")
                .isDefault(true)
                .build();
        userCardRepository.save(card1);

        UserCard card2 = UserCard.builder()
                .userId(1L)
                .cardId("card_002")
                .cardMask("555555****4444")
                .cardName("Mastercard Platinum")
                .brand("MASTERCARD")
                .isDefault(false)
                .build();
        userCardRepository.save(card2);

        UserCard card3 = UserCard.builder()
                .userId(1L)
                .cardId("card_003")
                .cardMask("411111****1111")
                .cardName("Visa Classic")
                .brand("VISA")
                .isDefault(false)
                .build();
        userCardRepository.save(card3);
        log.info("Seeded 3 default cards for user 1.");
    }
}
