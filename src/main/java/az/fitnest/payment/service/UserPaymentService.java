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
import az.fitnest.payment.dto.epoint.EpointResponse;

@Service
@RequiredArgsConstructor
public class UserPaymentService {

    private static final Logger log = LoggerFactory.getLogger(UserPaymentService.class);

    private final UserCardRepository userCardRepository;
    private final PaymentRepository paymentRepository;
    private final EpointIntegrationService integrationService;
    private final AbbIntegrationService abbIntegrationService;
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

    private List<Payment> deduplicatePayments(List<Payment> payments) {
        List<Payment> deduplicated = new java.util.ArrayList<>();
        for (Payment p : payments) {
            if ("WIDGET_PAYMENT".equalsIgnoreCase(p.getType())) {
                boolean hasSpecificPayment = payments.stream()
                    .anyMatch(other -> !other.getId().equals(p.getId())
                        && !"WIDGET_PAYMENT".equalsIgnoreCase(other.getType())
                        && p.getAmount() != null && p.getAmount().equals(other.getAmount())
                        && other.getCreatedDate() != null && p.getCreatedDate() != null
                        && Math.abs(java.time.Duration.between(p.getCreatedDate(), other.getCreatedDate()).toMinutes()) <= 5);
                if (hasSpecificPayment) {
                    continue;
                }
            }
            deduplicated.add(p);
        }
        return deduplicated;
    }

    public List<PaymentResponse> getUserPayments(Long userId) {
        log.info("Fetching all payments for user: {}", userId);
        String userLanguage = resolveUserLanguage();
        List<Payment> allPayments = paymentRepository.findAllByUserId(userId);
        List<Payment> deduplicated = deduplicatePayments(allPayments);
        return deduplicated.stream()
                .map(payment -> mapToPaymentResponse(payment, userLanguage))
                .collect(Collectors.toList());
    }

    public List<az.fitnest.payment.dto.admin.AdminUserPaymentHistoryResponse> getUserPaymentHistoryAdmin(Long userId) {
        log.info("Admin: Fetching payment history for user: {}", userId);
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        
        List<Payment> allPayments = paymentRepository.findAllByUserId(userId);
        List<Payment> deduplicated = deduplicatePayments(allPayments);
        return deduplicated.stream()
                .map(payment -> {
                    String brand;
                    if ("GOOGLE_PAY".equals(payment.getType())) {
                        brand = "Google Pay";
                    } else if ("APPLE_PAY".equals(payment.getType())) {
                        brand = "Apple Pay";
                    } else if ("ABB_INSTALLMENT".equals(payment.getType())) {
                        brand = "ABB installment";
                    } else if ("ABB_PAYMENT".equals(payment.getType())) {
                        brand = "ABB";
                    } else {
                        brand = CardBrandDetector.detectBrand(payment.getCardMask());
                    }
                    if (brand == null || brand.isEmpty()) brand = "N/A";

                    String status = payment.getStatus();
                    if ("SUCCESS".equalsIgnoreCase(status)) status = "Uğurlu";
                    else if ("FAILED".equalsIgnoreCase(status)) status = "Uğursuz";
                    else if ("PENDING".equalsIgnoreCase(status)) status = "Gözləmədə";
                    else if ("PENDING_USER_ACTION".equalsIgnoreCase(status)) status = "Gözləmədə";
                    else if ("REVERSED".equalsIgnoreCase(status)) status = "Geri qaytarıldı";
                    else if ("REFUNDED".equalsIgnoreCase(status)) status = "İadə edildi";
                    else if (status != null) {
                        status = status.substring(0, 1).toUpperCase() + status.substring(1).toLowerCase();
                    }

                    return az.fitnest.payment.dto.admin.AdminUserPaymentHistoryResponse.builder()
                            .transactionId(payment.getTransactionId())
                            .orderId(payment.getOrderId())
                            .dateTime(payment.getCreatedDate() != null ? payment.getCreatedDate().format(formatter) : "N/A")
                            .amount(payment.getAmount() + " " + (payment.getCurrency() != null ? payment.getCurrency() : "AZN"))
                            .paymentMethod(brand)
                            .status(status)
                            .build();
                })
                .sorted((a, b) -> b.dateTime().compareTo(a.dateTime())) // Newest first
                .collect(Collectors.toList());
    }

    public PaymentResponse getPaymentById(Long paymentId, Long userId) {
        log.info("Fetching payment with id: {} for user: {}", paymentId, userId);
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
        verifyOwnership(payment, userId);
        return mapToPaymentResponse(payment, resolveUserLanguage());
    }

    public PaymentResponse getPaymentByOrderId(String orderId, Long userId) {
        log.info("Fetching payment with order id: {} for user: {}", orderId, userId);
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with order id: " + orderId));
        verifyOwnership(payment, userId);
        return mapToPaymentResponse(payment, resolveUserLanguage());
    }

    public PaymentResponse getPaymentByTransactionId(String transactionId, Long userId) {
        log.info("Fetching payment with transaction id: {} for user: {}", transactionId, userId);
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .or(() -> paymentRepository.findByOrderId(transactionId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with transaction/order id: " + transactionId));
        verifyOwnership(payment, userId);
        return mapToPaymentResponse(payment, resolveUserLanguage());
    }

    public String getRawPaymentStatusByOrderId(String orderId, Long userId) {
        log.info("Fetching raw payment status with order id: {} for user: {}", orderId, userId);
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with order id: " + orderId));
        verifyOwnership(payment, userId);
        
        String status = payment.getStatus() != null ? payment.getStatus().toUpperCase() : "PENDING";
        
        // If status is pending, actively query Epoint to sync status
        if ("PENDING".equals(status) || "PENDING_USER_ACTION".equals(status) || "PENDING_3DS".equals(status) || "NEW".equals(status)) {
            try {
                boolean isAbb = "ABB".equalsIgnoreCase(payment.getProvider())
                        || (payment.getType() != null && payment.getType().startsWith("ABB"));
                if (isAbb) {
                    log.info("[StatusSync] Actively querying ABB status for orderId: {}", orderId);
                    abbIntegrationService.getTransactionStatus(orderId, "1");
                } else {
                    log.info("[StatusSync] Actively querying Epoint status for orderId: {}", orderId);
                    integrationService.getStatus(orderId);
                }
                payment = paymentRepository.findByOrderId(orderId).orElse(payment);
                status = payment.getStatus() != null ? payment.getStatus().toUpperCase() : "PENDING";
                log.info("[StatusSync] Synchronized status: {} for orderId: {}", status, orderId);
            } catch (Exception e) {
                log.error("[StatusSync] Failed to actively sync status for orderId: {}", orderId, e);
            }
        }
        
        return switch (status) {
            case "SUCCESS" -> "SUCCESS";
            case "FAILED", "ERROR", "SERVER_ERROR" -> "FAILED";
            case "PENDING_USER_ACTION", "NEW", "PENDING", "PENDING_3DS" -> "PENDING";
            case "REVERSED", "REFUNDED", "RETURNED", "CANCELLED" -> "CANCELLED";
            default -> "PENDING";
        };
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
                    .map(payment -> mapToPaymentResponse(payment, "AZ"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Admin: error fetching payments", e);
            throw e;
        }
    }

    public PaymentResponse getPaymentByIdAdmin(Long paymentId) {
        log.info("Admin: fetching payment with id: {}", paymentId);
        return paymentRepository.findById(paymentId)
                .map(payment -> mapToPaymentResponse(payment, "AZ"))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
    }

    public PaymentResponse getPaymentByOrderIdAdmin(String orderId) {
        log.info("Admin: fetching payment with order id: {}", orderId);
        return paymentRepository.findByOrderId(orderId)
                .map(payment -> mapToPaymentResponse(payment, "AZ"))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with order id: " + orderId));
    }

    public PaymentResponse getPaymentByTransactionIdAdmin(String transactionId) {
        log.info("Admin: fetching payment with transaction id: {}", transactionId);
        return paymentRepository.findByTransactionId(transactionId)
                .map(payment -> mapToPaymentResponse(payment, "AZ"))
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
            .filter(p -> {
                String t = p.getType();
                return "PAYMENT".equalsIgnoreCase(t)
                    || "WIDGET_PAYMENT".equalsIgnoreCase(t)
                    || "ABB_PAYMENT".equalsIgnoreCase(t)
                    || "ABB_INSTALLMENT".equalsIgnoreCase(t);
            })
            .filter(p -> {
                if (p.getCreatedDate() == null) return false;
                java.time.LocalDate date = p.getCreatedDate().toLocalDate();
                return !date.isBefore(startDate) && !date.isAfter(endDate) && date.getYear() == currentYear;
            })
            .toList();
        List<Payment> deduplicated = deduplicatePayments(filtered);
        int startIdx = (int) pageable.getOffset();
        int endIdx = Math.min((startIdx + pageable.getPageSize()), deduplicated.size());
        String userLanguage = resolveUserLanguage();
        List<PaymentResponse> responses = deduplicated.stream().map(p -> mapToPaymentResponse(p, userLanguage)).toList();
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
            card.getBrand()
        );
    }

    private PaymentResponse mapToPaymentResponse(Payment payment) {
        return mapToPaymentResponse(payment, "AZ");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private az.fitnest.payment.client.UserGrpcClient userGrpcClient;

    private String resolveUserLanguage() {
        // 1. First priority: Authenticated users (from UserContext / Database lookup via gRPC)
        Long userId = az.fitnest.payment.util.UserContext.getCurrentUserId();
        if (userId != null) {
            try {
                String lang = userGrpcClient.getUserLanguage(userId);
                if (lang != null && !lang.trim().isEmpty()) {
                    return lang.toUpperCase();
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Second priority: Anonymous users (Accept-Language header)
        try {
            org.springframework.web.context.request.RequestAttributes requestAttributes = 
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (requestAttributes instanceof org.springframework.web.context.request.ServletRequestAttributes) {
                jakarta.servlet.http.HttpServletRequest request = 
                        ((org.springframework.web.context.request.ServletRequestAttributes) requestAttributes).getRequest();
                String acceptLanguage = request.getHeader("Accept-Language");
                if (acceptLanguage != null && !acceptLanguage.trim().isEmpty()) {
                    String localeLang = org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage()
                            .toUpperCase();
                    if (localeLang.equals("EN") || localeLang.equals("RU") || localeLang.equals("AZ")) {
                        return localeLang;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return "AZ";
    }

    private String translateStatus(String status, String lang) {
        if (status == null || status.isEmpty()) return status;
        String upperStatus = status.toUpperCase().trim();
        if ("AZ".equalsIgnoreCase(lang)) {
            if ("SUCCESS".equals(upperStatus) || "UĞURLU".equals(upperStatus)) return "Uğurlu";
            if ("FAILED".equals(upperStatus) || "UĞURSUZ".equals(upperStatus)) return "Uğursuz";
            if ("PENDING".equals(upperStatus) || "GÖZLƏMƏDƏ".equals(upperStatus)) return "Gözləmədə";
        } else if ("RU".equalsIgnoreCase(lang)) {
            if ("SUCCESS".equals(upperStatus) || "UĞURLU".equals(upperStatus)) return "Успешно";
            if ("FAILED".equals(upperStatus) || "UĞURSUZ".equals(upperStatus)) return "Неуспешно";
            if ("PENDING".equals(upperStatus) || "GÖZLƏMƏDƏ".equals(upperStatus)) return "В ожидании";
        } else { // EN
            if ("SUCCESS".equals(upperStatus) || "UĞURLU".equals(upperStatus)) return "Success";
            if ("FAILED".equals(upperStatus) || "UĞURSUZ".equals(upperStatus)) return "Failed";
            if ("PENDING".equals(upperStatus) || "GÖZLƏMƏDƏ".equals(upperStatus)) return "Pending";
        }
        try {
            return translateWithGoogle(status, lang);
        } catch (Exception e) {
            return status;
        }
    }

    private String translatePaymentType(String type, String lang) {
        if (type == null || type.isEmpty()) return type;
        String upperType = type.toUpperCase().trim();
        if ("AZ".equalsIgnoreCase(lang)) {
            if ("AUTO_RENEWAL".equals(upperType)) return "Avtomatik uzadılma";
            if ("ONE_TIME".equals(upperType)) return "Birdəfəlik";
        } else if ("RU".equalsIgnoreCase(lang)) {
            if ("AUTO_RENEWAL".equals(upperType)) return "Автопродление";
            if ("ONE_TIME".equals(upperType)) return "Разовый";
        } else { // EN
            if ("AUTO_RENEWAL".equals(upperType)) return "Auto renewal";
            if ("ONE_TIME".equals(upperType)) return "One time";
        }
        try {
            return translateWithGoogle(type, lang);
        } catch (Exception e) {
            return type;
        }
    }

    private String translateWithGoogle(String text, String targetLanguage) {
        try {
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            restTemplate.getMessageConverters().add(0, new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
            java.net.URI uri = org.springframework.web.util.UriComponentsBuilder
                .fromUriString("https://translate.googleapis.com/translate_a/single")
                .queryParam("client", "gtx")
                .queryParam("sl", "auto")
                .queryParam("tl", targetLanguage.toLowerCase())
                .queryParam("dt", "t")
                .queryParam("q", text)
                .build()
                .toUri();

            String response = restTemplate.getForObject(uri, String.class);
            if (response != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(response);
                if (rootNode.isArray() && rootNode.size() > 0) {
                    com.fasterxml.jackson.databind.JsonNode firstArray = rootNode.get(0);
                    if (firstArray.isArray() && firstArray.size() > 0) {
                        com.fasterxml.jackson.databind.JsonNode translationPair = firstArray.get(0);
                        if (translationPair.isArray() && translationPair.size() > 0) {
                            return translationPair.get(0).asText();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Google Translation API failed for text '{}' to '{}': {}", text, targetLanguage, e.getMessage());
        }
        return text;
    }

    private PaymentResponse mapToPaymentResponse(Payment payment, String userLanguage) {
        String formattedStatus = payment.getStatus();
        formattedStatus = translateStatus(formattedStatus, userLanguage);

        String brand;
        if ("GOOGLE_PAY".equals(payment.getType())) {
            brand = "Google Pay";
        } else if ("APPLE_PAY".equals(payment.getType())) {
            brand = "Apple Pay";
        } else if ("ABB_INSTALLMENT".equals(payment.getType())) {
            brand = "ABB installment";
        } else if ("ABB_PAYMENT".equals(payment.getType())) {
            brand = "ABB";
        } else {
            brand = CardBrandDetector.detectBrand(payment.getCardMask());
        }

        String rawType = Boolean.TRUE.equals(payment.getAutoPaymentEnabled()) ? "AUTO_RENEWAL" : "ONE_TIME";
        String actualType = translatePaymentType(rawType, userLanguage);

        return new PaymentResponse(
            payment.getId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getCreatedDate() != null ? payment.getCreatedDate().atZone(java.time.ZoneId.systemDefault()).toInstant() : null,
            brand,
            maskCardToLast4(payment.getCardMask()),
            actualType,
            formattedStatus,
            payment.getCode(),
            payment.getTransactionId()
        );
    }

    private String maskCardToLast4(String cardMask) {
        if (cardMask == null || cardMask.isBlank()) {
            return cardMask;
        }
        String clean = cardMask.replaceAll("\\s+", "");
        if (clean.length() < 4) {
            return clean;
        }
        return "************" + clean.substring(clean.length() - 4);
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
