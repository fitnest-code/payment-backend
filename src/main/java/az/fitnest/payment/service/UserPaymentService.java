package az.fitnest.payment.service;

import az.fitnest.payment.dto.common.PaymentResponse;
import az.fitnest.payment.dto.common.UserCardResponse;
import az.fitnest.payment.dto.common.PaginatedResponse;
import az.fitnest.payment.exception.ForbiddenException;
import az.fitnest.payment.exception.ResourceNotFoundException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import az.fitnest.payment.util.CardBrandDetector;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import az.fitnest.payment.dto.epoint.EpointResponse;

@Service
@RequiredArgsConstructor
public class UserPaymentService {

    private static final Logger log = LoggerFactory.getLogger(UserPaymentService.class);

    private final UserCardRepository userCardRepository;
    private final PaymentRepository paymentRepository;
    @Autowired
    private MessageSource messageSource;

    public List<UserCardResponse> getUserCards(Long userId) {
        log.info("[FetchCards] Fetching all cards for user: {}", userId);
        List<UserCard> cards = userCardRepository.findAllByUserId(userId);
        log.info("[FetchCards] Found {} cards for user {}", cards.size(), userId);
        for (UserCard card : cards) {
            log.info("[FetchCards] Card: id={}, cardId={}, cardMask={}, cardName={}, brand={}, createdDate={}, lastModifiedDate={}",
                card.getId(), card.getCardId(), card.getCardMask(), card.getCardName(), card.getBrand(), card.getCreatedDate(), card.getLastModifiedDate());
        }
        return cards.stream()
                .map(this::mapToCardResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteCard(Long userId, String cardId) {
        log.info("Deleting card {} for user: {}", cardId, userId);

        UserCard card = userCardRepository.findByUserIdAndCardId(userId, cardId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage("error.card.not.found", null, Locale.getDefault())));

        userCardRepository.delete(card);
    }

    public List<PaymentResponse> getUserPayments(Long userId) {
        log.info("Fetching all payments for user: {}", userId);
        return paymentRepository.findAllByUserId(userId)
                .stream()
                .map(this::mapToPaymentResponse)
                .collect(Collectors.toList());
    }

    public PaymentResponse getPaymentById(Long paymentId, Long userId) {
        log.info("Fetching payment with id: {} for user: {}", paymentId, userId);
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
        verifyOwnership(payment, userId);
        return mapToPaymentResponse(payment);
    }

    public PaymentResponse getPaymentByOrderId(String orderId, Long userId) {
        log.info("Fetching payment with order id: {} for user: {}", orderId, userId);
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with order id: " + orderId));
        verifyOwnership(payment, userId);
        return mapToPaymentResponse(payment);
    }

    public PaymentResponse getPaymentByTransactionId(String transactionId, Long userId) {
        log.info("Fetching payment with transaction id: {} for user: {}", transactionId, userId);
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with transaction id: " + transactionId));
        verifyOwnership(payment, userId);
        return mapToPaymentResponse(payment);
    }

    public List<PaymentResponse> getAllPayments() {
        log.info("Admin: fetching all payments");
        try {
            List<Payment> payments = paymentRepository.findAll();
            log.info("Admin: found {} payments", payments.size());
            for (Payment payment : payments) {
                log.info("Admin: Payment: id={}, provider={}, status={}, orderId={}, transactionId={}, amount={}, currency={}, cardMask={}, cardName={}, userId={}, description={}, code={}, bankResponse={}, operationCode={}, createdDate={}, lastModifiedDate={}",
                    payment.getId(), payment.getProvider(), payment.getStatus(), payment.getOrderId(), payment.getTransactionId(), payment.getAmount(), payment.getCurrency(), payment.getCardMask(), payment.getCardName(), payment.getUserId(), payment.getDescription(), payment.getCode(), payment.getBankResponse(), payment.getOperationCode(), payment.getCreatedDate(), payment.getLastModifiedDate());
            }
            return payments.stream()
                    .map(this::mapToPaymentResponse)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Admin: error fetching payments", e);
            throw e;
        }
    }

    public PaymentResponse getPaymentByIdAdmin(Long paymentId) {
        log.info("Admin: fetching payment with id: {}", paymentId);
        return paymentRepository.findById(paymentId)
                .map(this::mapToPaymentResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
    }

    public PaymentResponse getPaymentByOrderIdAdmin(String orderId) {
        log.info("Admin: fetching payment with order id: {}", orderId);
        return paymentRepository.findByOrderId(orderId)
                .map(this::mapToPaymentResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with order id: " + orderId));
    }

    public PaymentResponse getPaymentByTransactionIdAdmin(String transactionId) {
        log.info("Admin: fetching payment with transaction id: {}", transactionId);
        return paymentRepository.findByTransactionId(transactionId)
                .map(this::mapToPaymentResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with transaction id: " + transactionId));
    }

    public PaginatedResponse<PaymentResponse> getUserPaymentHistory(Long userId, Pageable pageable, Integer fromMonth) {
        List<Payment> allPayments = paymentRepository.findAllByUserId(userId);
        java.time.LocalDate startDate;
        java.time.LocalDate endDate = java.time.LocalDate.now();
        int currentYear = endDate.getYear();
        if (fromMonth != null && fromMonth >= 1 && fromMonth <= 12) {
            startDate = java.time.LocalDate.of(currentYear, fromMonth, 1);
        } else {
            startDate = java.time.LocalDate.of(currentYear, 1, 1);
        }
        List<Payment> filtered = allPayments.stream()
            .filter(p -> "PAYMENT".equalsIgnoreCase(p.getType()) || "WIDGET_PAYMENT".equalsIgnoreCase(p.getType()))
            .filter(p -> {
                if (p.getCreatedDate() == null) return false;
                java.time.LocalDate date = p.getCreatedDate().toLocalDate();
                return !date.isBefore(startDate) && !date.isAfter(endDate) && date.getYear() == currentYear;
            })
            .toList();
        int startIdx = (int) pageable.getOffset();
        int endIdx = Math.min((startIdx + pageable.getPageSize()), filtered.size());
        List<PaymentResponse> responses = filtered.stream().map(this::mapToPaymentResponse).toList();
        List<PaymentResponse> pageContent = responses.subList(Math.min(startIdx, responses.size()), Math.min(endIdx, responses.size()));
        Page<PaymentResponse> page = new PageImpl<>(pageContent, pageable, responses.size());
        return PaginatedResponse.of(page);
    }

    private void verifyOwnership(Payment payment, Long userId) {
        if (!userId.equals(payment.getUserId())) {
            log.warn("User {} attempted to access payment {} owned by user {}",
                    userId, payment.getId(), payment.getUserId());
            throw new ForbiddenException("error.forbidden");
        }
    }

    private UserCardResponse mapToCardResponse(UserCard card) {
        return new UserCardResponse(
            card.getCardId(),
            card.getCardName(),
            card.getCardMask(),
            card.getBrand(),
            card.getReccPmntExpiry()
        );
    }

    private PaymentResponse mapToPaymentResponse(Payment payment) {
        String formattedStatus = payment.getStatus();
        if (formattedStatus != null && !formattedStatus.isEmpty()) {
            formattedStatus = formattedStatus.substring(0, 1).toUpperCase() + formattedStatus.substring(1).toLowerCase();
        }

        String brand = CardBrandDetector.detectBrand(payment.getCardMask());
        if ("WIDGET_PAYMENT".equals(payment.getType()) && payment.getDescription() != null) {
            if (payment.getDescription().contains("device:iOS")) {
                brand = "Apple Pay";
            } else if (payment.getDescription().contains("device:Android")) {
                brand = "Google Pay";
            }
        }

        return new PaymentResponse(
            payment.getId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getCreatedDate() != null ? payment.getCreatedDate().atZone(java.time.ZoneId.systemDefault()).toInstant() : null,
            brand,
            payment.getCardMask(),
            payment.getType(),
            formattedStatus,
            payment.getCode()
        );
    }

    private void upsertCardFromCallback(Long userId, EpointResponse callbackData) {
        log.info("[CardSave] (ENTRY) upsertCardFromCallback: userId={}, cardId={}, cardMask={}, cardName={}, callbackData={}", userId, callbackData.cardId(), callbackData.cardMask(), callbackData.cardName(), callbackData);

        if (callbackData.cardId() == null || callbackData.cardId().isBlank()) {
            log.warn("[CardSave] No cardId in callbackData, skipping card save.");
            return;
        }

        Optional<UserCard> existingCard = userCardRepository.findByUserIdAndCardId(userId, callbackData.cardId());
        if (existingCard.isPresent()) {
            UserCard card = existingCard.get();
            card.setCardMask(callbackData.cardMask());
            card.setCardName(callbackData.cardName());
            card.setBankTransaction(callbackData.bankTransaction());
            card.setBankResponse(callbackData.bankResponse());
            card.setOperationCode(callbackData.operationCode());
            card.setRrn(callbackData.rrn());
            userCardRepository.save(card);
            log.info("[CardSave] Updated existing card {} for user {}", callbackData.cardId(), userId);
        } else {
            UserCard userCard = UserCard.builder()
                    .userId(userId)
                    .cardId(callbackData.cardId())
                    .cardMask(callbackData.cardMask())
                    .cardName(callbackData.cardName())
                    .bankTransaction(callbackData.bankTransaction())
                    .bankResponse(callbackData.bankResponse())
                    .operationCode(callbackData.operationCode())
                    .rrn(callbackData.rrn())
                    .build();
            userCardRepository.save(userCard);
            log.info("[CardSave] Created new card {} for user {}", callbackData.cardId(), userId);
        }
        List<UserCard> allCards = userCardRepository.findAllByUserId(userId);
        log.info("[CardSave] (EXIT) All cards for user {} after upsert: {}", userId, allCards);
    }
}
