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
import java.util.stream.Collectors;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

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
            log.info("[FetchCards] Card: id={}, cardId={}, cardMask={}, cardName={}, brand={}, isDefault={}, createdDate={}, lastModifiedDate={}",
                card.getId(), card.getCardId(), card.getCardMask(), card.getCardName(), card.getBrand(), card.isDefault(), card.getCreatedDate(), card.getLastModifiedDate());
        }
        return cards.stream()
                .map(this::mapToCardResponse)
                .collect(Collectors.toList());
    }

    public UserCardResponse getDefaultCard(Long userId) {
        log.info("Fetching default card for user: {}", userId);
        return userCardRepository.findByUserIdAndIsDefaultTrue(userId)
                .map(this::mapToCardResponse)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage("error.card.default.not.found", null, Locale.getDefault())));
    }

    @Transactional
    public UserCardResponse setDefaultCard(Long userId, Long cardId) {
        log.info("Setting card {} as default for user: {}", cardId, userId);

        UserCard card = userCardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage("error.card.not.found", null, Locale.getDefault())));

        if (!card.getUserId().equals(userId)) {
            throw new ForbiddenException(messageSource.getMessage("error.forbidden", null, Locale.getDefault()));
        }

        userCardRepository.findAllByUserId(userId).forEach(c -> {
            if (c.isDefault()) {
                c.setDefault(false);
                userCardRepository.save(c);
            }
        });

        card.setDefault(true);
        UserCard savedCard = userCardRepository.save(card);

        return mapToCardResponse(savedCard);
    }

    @Transactional
    public void deleteCard(Long userId, Long cardId) {
        log.info("Deleting card {} for user: {}", cardId, userId);

        UserCard card = userCardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage("error.card.not.found", null, Locale.getDefault())));

        if (!card.getUserId().equals(userId)) {
            throw new ForbiddenException(messageSource.getMessage("error.forbidden", null, Locale.getDefault()));
        }

        boolean wasDefault = card.isDefault();
        userCardRepository.delete(card);

        if (wasDefault) {
            List<UserCard> remainingCards = userCardRepository.findAllByUserId(userId);
            if (!remainingCards.isEmpty()) {
                UserCard newDefault = remainingCards.get(0);
                newDefault.setDefault(true);
                userCardRepository.save(newDefault);
            }
        }
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

    public Page<PaymentResponse> getUserPaymentHistory(Long userId, Pageable pageable, String range, Integer month, Integer year) {
        List<Payment> allPayments = paymentRepository.findAllByUserId(userId);
        List<Payment> filtered = allPayments;
        if (range != null) {
            LocalDate now = LocalDate.now();
            final LocalDate fromDate;
            switch (range) {
                case "LAST_1_MONTH":
                    fromDate = now.minusMonths(1);
                    break;
                case "LAST_3_MONTHS":
                    fromDate = now.minusMonths(3);
                    break;
                default:
                    fromDate = null;
                    break;
            }
            if (fromDate != null) {
                filtered = filtered.stream()
                        .filter(p -> p.getCreatedDate() != null && p.getCreatedDate().toLocalDate().isAfter(fromDate))
                        .toList();
            }
        } else if (month != null && year != null) {
            filtered = filtered.stream()
                    .filter(p -> {
                        if (p.getCreatedDate() == null) return false;
                        LocalDate date = p.getCreatedDate().toLocalDate();
                        return date.getMonthValue() == month && date.getYear() == year;
                    })
                    .toList();
        }
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filtered.size());
        List<PaymentResponse> responses = filtered.stream().map(this::mapToPaymentResponse).toList();
        List<PaymentResponse> pageContent = responses.subList(Math.min(start, responses.size()), Math.min(end, responses.size()));
        return new PageImpl<>(pageContent, pageable, responses.size());
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
            card.getId(),
            card.getCardId(),
            card.getCardMask(),
            card.getCardName(),
            card.getBrand(),
            card.isDefault(),
            card.getCreatedDate() != null ? card.getCreatedDate().atZone(java.time.ZoneId.systemDefault()).toInstant() : null,
            card.getLastModifiedDate() != null ? card.getLastModifiedDate().atZone(java.time.ZoneId.systemDefault()).toInstant() : null
        );
    }

    private PaymentResponse mapToPaymentResponse(Payment payment) {
        return new PaymentResponse(
            payment.getId(),
            payment.getProvider(),
            payment.getStatus(),
            payment.getOrderId(),
            payment.getTransactionId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getCardMask(),
            payment.getCardName(),
            payment.getMessage(),
            payment.getUserId(),
            payment.getDescription(),
            payment.getCode(),
            payment.getBankResponse(),
            payment.getOperationCode(),
            payment.getCreatedDate() != null ? payment.getCreatedDate().atZone(java.time.ZoneId.systemDefault()).toInstant() : null,
            payment.getLastModifiedDate() != null ? payment.getLastModifiedDate().atZone(java.time.ZoneId.systemDefault()).toInstant() : null
        );
    }
}
