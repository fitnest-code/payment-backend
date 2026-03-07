package az.fitnest.payment.config;

import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DataInitializer {

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

    private void initUserCards() {
        if (userCardRepository.findAllByUserId(1L).isEmpty()) {
            log.info("No cards found for user 1. Seeding default cards...");
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
        } else {
            log.info("User 1 already has cards. Skipping card seeding.");
        }
    }
}
