package az.fitnest.payment.service;

import az.fitnest.payment.dto.common.PaymentResponse;
import az.fitnest.payment.dto.common.UserCardResponse;
import az.fitnest.payment.exception.ResourceNotFoundException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserPaymentService {

    private final UserCardRepository userCardRepository;
    private final PaymentRepository paymentRepository;

    /**
     * Get all saved cards for a user
     */
    public List<UserCardResponse> getUserCards(Long userId) {
        log.info("Fetching all cards for user: {}", userId);
        return userCardRepository.findAllByUserId(userId)
                .stream()
                .map(this::mapToCardResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get default card for a user
     */
    public UserCardResponse getDefaultCard(Long userId) {
        log.info("Fetching default card for user: {}", userId);
        return userCardRepository.findByUserIdAndIsDefaultTrue(userId)
                .map(this::mapToCardResponse)
                .orElse(null);
    }

    /**
     * Set a card as default
     */
    @Transactional
    public UserCardResponse setDefaultCard(Long userId, Long cardId) {
        log.info("Setting card {} as default for user: {}", cardId, userId);

        UserCard card = userCardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card not found with id: " + cardId));

        if (!card.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Card does not belong to user");
        }

        // Remove default from other cards
        userCardRepository.findAllByUserId(userId).forEach(c -> {
            if (c.isDefault()) {
                c.setDefault(false);
                userCardRepository.save(c);
            }
        });

        // Set this card as default
        card.setDefault(true);
        UserCard savedCard = userCardRepository.save(card);

        return mapToCardResponse(savedCard);
    }

    /**
     * Delete a saved card
     */
    @Transactional
    public void deleteCard(Long userId, Long cardId) {
        log.info("Deleting card {} for user: {}", cardId, userId);

        UserCard card = userCardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card not found with id: " + cardId));

        if (!card.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Card does not belong to user");
        }

        boolean wasDefault = card.isDefault();
        userCardRepository.delete(card);

        // If deleted card was default, set another card as default
        if (wasDefault) {
            List<UserCard> remainingCards = userCardRepository.findAllByUserId(userId);
            if (!remainingCards.isEmpty()) {
                UserCard newDefault = remainingCards.get(0);
                newDefault.setDefault(true);
                userCardRepository.save(newDefault);
            }
        }
    }

    /**
     * Get all payments for a user
     */
    public List<PaymentResponse> getUserPayments(Long userId) {
        log.info("Fetching all payments for user: {}", userId);
        return paymentRepository.findAllByUserId(userId)
                .stream()
                .map(this::mapToPaymentResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get all payments (admin only)
     */
    public List<PaymentResponse> getAllPayments() {
        log.info("Fetching all payments");
        return paymentRepository.findAll()
                .stream()
                .map(this::mapToPaymentResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get payment by ID
     */
    public PaymentResponse getPaymentById(Long paymentId) {
        log.info("Fetching payment with id: {}", paymentId);
        return paymentRepository.findById(paymentId)
                .map(this::mapToPaymentResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
    }

    /**
     * Get payment by order ID
     */
    public PaymentResponse getPaymentByOrderId(String orderId) {
        log.info("Fetching payment with order id: {}", orderId);
        return paymentRepository.findByOrderId(orderId)
                .map(this::mapToPaymentResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with order id: " + orderId));
    }

    /**
     * Get payment by transaction ID
     */
    public PaymentResponse getPaymentByTransactionId(String transactionId) {
        log.info("Fetching payment with transaction id: {}", transactionId);
        return paymentRepository.findByTransactionId(transactionId)
                .map(this::mapToPaymentResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with transaction id: " + transactionId));
    }

    private UserCardResponse mapToCardResponse(UserCard card) {
        return UserCardResponse.builder()
                .id(card.getId())
                .cardId(card.getCardId())
                .cardMask(card.getCardMask())
                .cardName(card.getCardName())
                .brand(card.getBrand())
                .isDefault(card.isDefault())
                .createdAt(card.getCreatedDate() != null ?
                    card.getCreatedDate().atZone(java.time.ZoneId.systemDefault()).toInstant() : null)
                .updatedAt(card.getLastModifiedDate() != null ?
                    card.getLastModifiedDate().atZone(java.time.ZoneId.systemDefault()).toInstant() : null)
                .build();
    }

    private PaymentResponse mapToPaymentResponse(Payment payment) {
        return PaymentResponse.builder()
                .paymentId(payment.getPaymentId())
                .provider(payment.getProvider())
                .status(payment.getStatus())
                .orderId(payment.getOrderId())
                .transactionId(payment.getTransactionId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .cardMask(payment.getCardMask())
                .cardName(payment.getCardName())
                .message(payment.getMessage())
                .userId(payment.getUserId())
                .description(payment.getDescription())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }
}


