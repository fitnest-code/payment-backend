package az.fitnest.payment.service;

import az.fitnest.payment.dto.common.PaymentResponse;
import az.fitnest.payment.dto.common.UserCardResponse;
import az.fitnest.payment.exception.ForbiddenException;
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

    // ─── Card operations ───────────────────────────────────────────────

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
            throw new ForbiddenException("You do not have permission to access this card");
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
            throw new ForbiddenException("You do not have permission to access this card");
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

    // ─── Payment operations (user-scoped) ──────────────────────────────

    /**
     * Get all payments for the authenticated user
     */
    public List<PaymentResponse> getUserPayments(Long userId) {
        log.info("Fetching all payments for user: {}", userId);
        return paymentRepository.findAllByUserId(userId)
                .stream()
                .map(this::mapToPaymentResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get payment by ID — verifies it belongs to the given user
     */
    public PaymentResponse getPaymentById(Long paymentId, Long userId) {
        log.info("Fetching payment with id: {} for user: {}", paymentId, userId);
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
        verifyOwnership(payment, userId);
        return mapToPaymentResponse(payment);
    }

    /**
     * Get payment by order ID — verifies it belongs to the given user
     */
    public PaymentResponse getPaymentByOrderId(String orderId, Long userId) {
        log.info("Fetching payment with order id: {} for user: {}", orderId, userId);
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with order id: " + orderId));
        verifyOwnership(payment, userId);
        return mapToPaymentResponse(payment);
    }

    /**
     * Get payment by transaction ID — verifies it belongs to the given user
     */
    public PaymentResponse getPaymentByTransactionId(String transactionId, Long userId) {
        log.info("Fetching payment with transaction id: {} for user: {}", transactionId, userId);
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with transaction id: " + transactionId));
        verifyOwnership(payment, userId);
        return mapToPaymentResponse(payment);
    }

    // ─── Payment operations (admin — no ownership check) ───────────────

    /**
     * Get all payments (admin only — authorization enforced at controller)
     */
    public List<PaymentResponse> getAllPayments() {
        log.info("Admin: fetching all payments");
        return paymentRepository.findAll()
                .stream()
                .map(this::mapToPaymentResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get any payment by ID (admin only — authorization enforced at controller)
     */
    public PaymentResponse getPaymentByIdAdmin(Long paymentId) {
        log.info("Admin: fetching payment with id: {}", paymentId);
        return paymentRepository.findById(paymentId)
                .map(this::mapToPaymentResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
    }

    /**
     * Get any payment by order ID (admin only — authorization enforced at controller)
     */
    public PaymentResponse getPaymentByOrderIdAdmin(String orderId) {
        log.info("Admin: fetching payment with order id: {}", orderId);
        return paymentRepository.findByOrderId(orderId)
                .map(this::mapToPaymentResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with order id: " + orderId));
    }

    /**
     * Get any payment by transaction ID (admin only — authorization enforced at controller)
     */
    public PaymentResponse getPaymentByTransactionIdAdmin(String transactionId) {
        log.info("Admin: fetching payment with transaction id: {}", transactionId);
        return paymentRepository.findByTransactionId(transactionId)
                .map(this::mapToPaymentResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with transaction id: " + transactionId));
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private void verifyOwnership(Payment payment, Long userId) {
        if (!userId.equals(payment.getUserId())) {
            log.warn("User {} attempted to access payment {} owned by user {}",
                    userId, payment.getPaymentId(), payment.getUserId());
            throw new ForbiddenException("You do not have permission to access this payment");
        }
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
                .code(payment.getCode())
                .bankResponse(payment.getBankResponse())
                .operationCode(payment.getOperationCode())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }
}
