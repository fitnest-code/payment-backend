package az.fitnest.payment.service.kapital.bolode;

import az.fitnest.payment.dto.kapital.bolode.*;
import az.fitnest.payment.exception.BadRequestException;
import az.fitnest.payment.exception.BolOdeActiveApplicationException;
import az.fitnest.payment.exception.ConflictException;
import az.fitnest.payment.exception.ResourceNotFoundException;
import az.fitnest.payment.model.entity.BolOdeApplication;
import az.fitnest.payment.model.entity.BolOdeApplicationEvent;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.enums.BolOdeApplicationStatus;
import az.fitnest.payment.repository.BolOdeApplicationEventRepository;
import az.fitnest.payment.repository.BolOdeApplicationRepository;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.service.checkout.SubscriptionCheckoutService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Manual Kapital "Böl ödə" application workflow (provider KAPITAL_BOL_ODE).
 *
 * <p>Why separate: the bank has no API yet, so FitNest admins relay applications by hand.
 * This must not touch ABB BNPL or Kapital HPP/Taksit flows. PII (FIN/DOB) is never logged;
 * log lines carry only displayId / ids.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BolOdeApplicationService {

    public static final String PROVIDER = "KAPITAL_BOL_ODE";
    public static final String DISPLAY_PREFIX = "BÖ-";
    /** Customer-facing promise: bank/admin answers within 15 minutes. */
    public static final Duration WAIT_SLA = Duration.ofMinutes(15);

    private static final Pattern FIN_PATTERN = Pattern.compile("^[A-Z0-9]{7}$");
    private static final int MIN_AGE_YEARS = 18;

    private final BolOdeApplicationRepository applicationRepository;
    private final BolOdeApplicationEventRepository eventRepository;
    private final PaymentRepository paymentRepository;
    private final SubscriptionCheckoutService checkoutService;

    // ------------------------------------------------------------------ user

    @Transactional
    public BolOdeInitResponse init(Long userId, BolOdeInitRequest request) {
        requireUser(userId);
        requireActiveAbsent(userId);

        String fin = normalizeFin(request.getFinCode());
        validateBirthDate(request.getBirthDate());
        String contactPhone = normalizePhone(request.getContactPhone(), "contactPhone", true);
        String extra1 = normalizePhone(request.getExtraPhone1(), "extraPhone1", false);
        String extra2 = normalizePhone(request.getExtraPhone2(), "extraPhone2", false);
        if (!Boolean.TRUE.equals(request.getBirbankConsentAccepted())
                || !Boolean.TRUE.equals(request.getMygovConsentAccepted())) {
            throw new BadRequestException("Birbank və mygov razılıqları məcburidir");
        }

        // Price always comes from the server quote; coins are not applied to manual applications.
        SubscriptionCheckoutService.CheckoutQuote quote = checkoutService.quote(
                userId, request.getPackageId(), request.getOptionId(), false);
        if (request.getDurationMonths() != null && request.getDurationMonths() != quote.durationMonths()) {
            throw new BadRequestException("durationMonths seçilmiş paketə uyğun deyil");
        }

        long seq = applicationRepository.nextDisplaySeq();
        String displayId = DISPLAY_PREFIX + seq;
        String reference = "BOLODE-" + seq;
        BigDecimal amount = BigDecimal.valueOf(quote.chargeAmountAzn()).setScale(2, RoundingMode.HALF_UP);

        // Payment row keeps the application visible in the common payments table; it stays
        // PENDING until the bank decision (no activation/outbox here: that is a later phase).
        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setProvider(PROVIDER);
        payment.setType(PROVIDER);
        payment.setTransactionId(reference);
        payment.setOrderId(reference);
        payment.setAmount(amount.doubleValue());
        payment.setCurrency(quote.currency());
        payment.setStatus("PENDING");
        payment.setDescription(quote.packageRefDescription());
        payment.setCallbackProcessed(false);
        payment.setAutoPaymentEnabled(false);
        payment.setInstallmentMonths(quote.durationMonths());
        Payment savedPayment = paymentRepository.save(payment);

        BolOdeApplication app = new BolOdeApplication();
        app.setDisplayId(displayId);
        app.setPaymentId(savedPayment.getId());
        app.setPaymentReference(reference);
        app.setUserId(userId);
        app.setFinCode(fin);
        app.setBirthDate(request.getBirthDate());
        app.setContactPhone(contactPhone);
        app.setExtraPhone1(extra1);
        app.setExtraPhone2(extra2);
        app.setBirbankConsentAccepted(true);
        app.setMygovConsentAccepted(true);
        app.setPackageName(request.getPackageName());
        app.setDurationMonths(quote.durationMonths());
        app.setFinalAmount(amount);
        app.setOptionId(quote.optionId());
        app.setPackageId(quote.packageId());
        app.setStatus(BolOdeApplicationStatus.NEW);

        BolOdeApplication saved;
        try {
            saved = applicationRepository.saveAndFlush(app);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent init: the partial unique index fired.
            BolOdeApplication existing = applicationRepository
                    .findFirstByUserIdAndStatusInOrderByCreatedAtDesc(userId, BolOdeApplicationStatus.ACTIVE)
                    .orElseThrow(() -> e);
            throw new BolOdeActiveApplicationException(existing.getId(), existing.getDisplayId());
        }
        recordEvent(saved.getId(), BolOdeApplicationEvent.ACTOR_SYSTEM, null,
                null, BolOdeApplicationStatus.NEW, "Müraciət yaradıldı", null);

        log.info("[BOL_ODE] created displayId={} appId={} userId={}", displayId, saved.getId(), userId);
        return BolOdeInitResponse.builder()
                .id(saved.getId())
                .displayId(displayId)
                .status(saved.getStatus().name())
                .paymentReference(reference)
                .finalAmount(amount)
                .durationMonths(saved.getDurationMonths())
                .createdAt(saved.getCreatedAt())
                .waitRemainingSeconds(waitRemainingSeconds(saved))
                .build();
    }

    /** Latest application of the user (any status) so a decline reason stays visible after DECLINED. */
    @Transactional(readOnly = true)
    public Optional<BolOdeApplicationResponse> findMine(Long userId) {
        requireUser(userId);
        return applicationRepository.findFirstByUserIdOrderByCreatedAtDesc(userId).map(this::toUserResponse);
    }

    /** All applications for "BNPL müraciətlərim" (newest first). */
    @Transactional(readOnly = true)
    public java.util.List<BolOdeApplicationResponse> listMine(Long userId) {
        requireUser(userId);
        return applicationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toUserResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BolOdeApplicationResponse getMine(Long userId, Long id) {
        return toUserResponse(loadOwned(userId, id));
    }

    /** Polling lookup by numeric id, displayId ("BÖ-1001") or payment reference. */
    @Transactional(readOnly = true)
    public BolOdeApplicationSummary getStatus(Long userId, String idOrRef) {
        requireUser(userId);
        BolOdeApplication app = resolve(idOrRef)
                .filter(a -> userId.equals(a.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Böl ödə müraciəti tapılmadı"));
        return BolOdeApplicationSummary.builder()
                .id(app.getId())
                .displayId(app.getDisplayId())
                .status(app.getStatus().name())
                .declineReason(customerVisibleReason(app))
                .waitRemainingSeconds(waitRemainingSeconds(app))
                .build();
    }

    @Transactional
    public BolOdeApplicationResponse cancel(Long userId, Long id) {
        requireUser(userId);
        BolOdeApplication app = applicationRepository.findByIdForUpdate(id)
                .filter(a -> userId.equals(a.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Böl ödə müraciəti tapılmadı"));
        if (!BolOdeApplicationStatus.CANCELLABLE.contains(app.getStatus())) {
            throw new ConflictException("Müraciət statusu " + app.getStatus() + " olduğu üçün ləğv edilə bilməz");
        }
        BolOdeApplicationStatus from = app.getStatus();
        app.setStatus(BolOdeApplicationStatus.CANCELLED);
        BolOdeApplication saved = applicationRepository.save(app);
        syncPayment(saved);
        recordEvent(saved.getId(), BolOdeApplicationEvent.ACTOR_USER, userId,
                from, BolOdeApplicationStatus.CANCELLED, "İstifadəçi tərəfindən ləğv edildi", null);
        log.info("[BOL_ODE] cancelled displayId={} userId={}", saved.getDisplayId(), userId);
        return toUserResponse(saved);
    }

    // ----------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    public Page<BolOdeAdminListItem> adminList(BolOdeApplicationStatus status,
                                               String query,
                                               Long userId,
                                               Long assignedAdminUserId,
                                               int page,
                                               int size) {
        Specification<BolOdeApplication> spec = (root, cq, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (userId != null) {
                p.add(cb.equal(root.get("userId"), userId));
            }
            if (assignedAdminUserId != null) {
                p.add(cb.equal(root.get("assignedAdminUserId"), assignedAdminUserId));
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase() + "%";
                // FIN is matched exactly (never partially) so search cannot be used to enumerate PII.
                p.add(cb.or(
                        cb.like(cb.lower(root.get("displayId")), like),
                        cb.like(cb.lower(root.get("paymentReference")), like),
                        cb.like(cb.lower(root.get("contactPhone")), like),
                        cb.equal(root.get("finCode"), query.trim().toUpperCase())));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return applicationRepository.findAll(spec, pageable).map(this::toAdminListItem);
    }

    @Transactional(readOnly = true)
    public BolOdeAdminDetail adminDetail(Long id) {
        return toAdminDetail(loadById(id));
    }

    @Transactional
    public BolOdeAdminDetail adminUpdate(Long id, Long adminUserId, BolOdeAdminUpdateRequest req) {
        BolOdeApplication app = applicationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Böl ödə müraciəti tapılmadı"));

        BolOdeApplicationStatus from = app.getStatus();
        BolOdeApplicationStatus target = req.getStatus() != null ? req.getStatus() : from;
        boolean statusChanged = target != from;

        // Terminal states are final: a cancelled/declined application must be re-submitted by the user.
        if (statusChanged && from.isTerminal()) {
            throw new ConflictException("Status " + from + " olan müraciət dəyişdirilə bilməz");
        }

        String reason = req.getDeclineReason() != null ? req.getDeclineReason() : app.getDeclineReason();
        if (target == BolOdeApplicationStatus.DECLINED && (reason == null || reason.isBlank())) {
            throw new BadRequestException("DECLINED statusu üçün declineReason məcburidir");
        }

        List<String> changes = new ArrayList<>();
        if (statusChanged) {
            app.setStatus(target);
            changes.add("status");
        }
        if (req.getAssignedAdminUserId() != null
                && !req.getAssignedAdminUserId().equals(app.getAssignedAdminUserId())) {
            app.setAssignedAdminUserId(req.getAssignedAdminUserId());
            changes.add("assignedAdminUserId");
        }
        if (req.getDeclineReason() != null && !req.getDeclineReason().equals(app.getDeclineReason())) {
            app.setDeclineReason(req.getDeclineReason().isBlank() ? null : req.getDeclineReason().trim());
            changes.add("declineReason");
        }
        if (req.getDeclineReasonVisibleToCustomer() != null
                && !req.getDeclineReasonVisibleToCustomer().equals(app.getDeclineReasonVisibleToCustomer())) {
            app.setDeclineReasonVisibleToCustomer(req.getDeclineReasonVisibleToCustomer());
            changes.add("declineReasonVisibleToCustomer");
        }
        if (req.getInternalNote() != null && !req.getInternalNote().equals(app.getInternalNote())) {
            app.setInternalNote(req.getInternalNote());
            changes.add("internalNote");
        }

        if (changes.isEmpty()) {
            return toAdminDetail(app);
        }
        BolOdeApplication saved = applicationRepository.save(app);
        if (statusChanged) {
            syncPayment(saved);
        }
        // The note text itself is not copied into the audit message (only that it changed).
        recordEvent(saved.getId(), BolOdeApplicationEvent.ACTOR_ADMIN, adminUserId, from, saved.getStatus(),
                "Admin yeniləməsi: " + String.join(", ", changes),
                target == BolOdeApplicationStatus.DECLINED ? saved.getDeclineReason() : null);
        log.info("[BOL_ODE] admin update displayId={} adminId={} changes={}",
                saved.getDisplayId(), adminUserId, changes);
        return toAdminDetail(saved);
    }

    @Transactional(readOnly = true)
    public List<BolOdeHistoryItem> adminHistory(Long id) {
        loadById(id);
        return eventRepository.findByApplicationIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(e -> BolOdeHistoryItem.builder()
                        .id(e.getId())
                        .actorType(e.getActorType())
                        .actorUserId(e.getActorUserId())
                        .fromStatus(e.getFromStatus() != null ? e.getFromStatus().name() : null)
                        .toStatus(e.getToStatus() != null ? e.getToStatus().name() : null)
                        .message(e.getMessage())
                        .declineReason(e.getDeclineReason())
                        .createdAt(e.getCreatedAt())
                        .build())
                .toList();
    }

    // --------------------------------------------------------------- helpers

    /** Seconds left of the SLA; 0 for decided/terminal applications or once exceeded. */
    public long waitRemainingSeconds(BolOdeApplication app) {
        if (app.getCreatedAt() == null || !BolOdeApplicationStatus.AWAITING_DECISION.contains(app.getStatus())) {
            return 0;
        }
        long left = WAIT_SLA.minus(Duration.between(app.getCreatedAt(), Instant.now())).getSeconds();
        return Math.max(0, left);
    }

    static String normalizeFin(String raw) {
        String fin = raw == null ? "" : raw.trim().toUpperCase();
        if (!FIN_PATTERN.matcher(fin).matches()) {
            throw new BadRequestException("FIN 7 simvoldan (hərf/rəqəm) ibarət olmalıdır");
        }
        return fin;
    }

    static void validateBirthDate(LocalDate dob) {
        if (dob == null) {
            throw new BadRequestException("Doğum tarixi məcburidir");
        }
        LocalDate today = LocalDate.now();
        if (dob.isAfter(today) || dob.isBefore(LocalDate.of(1900, 1, 1))
                || Period.between(dob, today).getYears() < MIN_AGE_YEARS) {
            throw new BadRequestException("Doğum tarixi etibarsızdır (minimum yaş " + MIN_AGE_YEARS + ")");
        }
    }

    /** Accepts +994XXXXXXXXX, 994XXXXXXXXX, 0XXXXXXXXX or XXXXXXXXX; stores +994XXXXXXXXX. */
    static String normalizePhone(String raw, String field, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new BadRequestException(field + " məcburidir");
            }
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("994") && digits.length() == 12) {
            digits = digits.substring(3);
        } else if (digits.startsWith("0") && digits.length() == 10) {
            digits = digits.substring(1);
        }
        if (digits.length() != 9) {
            throw new BadRequestException(field + " etibarsız telefon nömrəsidir");
        }
        return "+994" + digits;
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new az.fitnest.payment.exception.UnauthorizedException("Authentication required");
        }
    }

    private void requireActiveAbsent(Long userId) {
        applicationRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(userId, BolOdeApplicationStatus.ACTIVE)
                .ifPresent(existing -> {
                    throw new BolOdeActiveApplicationException(existing.getId(), existing.getDisplayId());
                });
    }

    private BolOdeApplication loadById(Long id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Böl ödə müraciəti tapılmadı"));
    }

    /** 404 (not 403) for foreign ids so ids of other users cannot be probed. */
    private BolOdeApplication loadOwned(Long userId, Long id) {
        requireUser(userId);
        return applicationRepository.findById(id)
                .filter(a -> userId.equals(a.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Böl ödə müraciəti tapılmadı"));
    }

    private Optional<BolOdeApplication> resolve(String idOrRef) {
        if (idOrRef == null || idOrRef.isBlank()) {
            return Optional.empty();
        }
        String key = idOrRef.trim();
        if (key.chars().allMatch(Character::isDigit)) {
            try {
                return applicationRepository.findById(Long.parseLong(key));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        Optional<BolOdeApplication> byDisplay = applicationRepository.findByDisplayId(key);
        return byDisplay.isPresent() ? byDisplay : applicationRepository.findByPaymentReference(key);
    }

    /** Keeps the common payments row aligned for terminal outcomes; APPROVED stays PENDING until a later settlement phase. */
    private void syncPayment(BolOdeApplication app) {
        if (app.getPaymentId() == null || !app.getStatus().isTerminal()) {
            return;
        }
        paymentRepository.findById(app.getPaymentId()).ifPresent(p -> {
            p.setStatus(app.getStatus() == BolOdeApplicationStatus.CANCELLED ? "CANCELLED" : "FAILED");
            p.setCallbackProcessed(true);
            paymentRepository.save(p);
        });
    }

    private void recordEvent(Long applicationId, String actorType, Long actorUserId,
                             BolOdeApplicationStatus from, BolOdeApplicationStatus to,
                             String message, String declineReason) {
        BolOdeApplicationEvent e = new BolOdeApplicationEvent();
        e.setApplicationId(applicationId);
        e.setActorType(actorType);
        e.setActorUserId(actorUserId);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setMessage(message);
        e.setDeclineReason(declineReason);
        eventRepository.save(e);
    }

    private String customerVisibleReason(BolOdeApplication app) {
        return Boolean.FALSE.equals(app.getDeclineReasonVisibleToCustomer()) ? null : app.getDeclineReason();
    }

    private BolOdeApplicationResponse toUserResponse(BolOdeApplication app) {
        return BolOdeApplicationResponse.builder()
                .id(app.getId())
                .displayId(app.getDisplayId())
                .status(app.getStatus().name())
                .paymentReference(app.getPaymentReference())
                .packageName(app.getPackageName())
                .durationMonths(app.getDurationMonths())
                .finalAmount(app.getFinalAmount())
                .finCode(app.getFinCode())
                .maskedFin(BolOdeMasking.maskFin(app.getFinCode()))
                .birthDate(app.getBirthDate())
                .contactPhone(app.getContactPhone())
                .extraPhone1(app.getExtraPhone1())
                .extraPhone2(app.getExtraPhone2())
                .declineReason(customerVisibleReason(app))
                .cancellable(BolOdeApplicationStatus.CANCELLABLE.contains(app.getStatus()))
                .waitRemainingSeconds(waitRemainingSeconds(app))
                .createdAt(app.getCreatedAt())
                .updatedAt(app.getUpdatedAt())
                .build();
    }

    private BolOdeAdminListItem toAdminListItem(BolOdeApplication app) {
        return BolOdeAdminListItem.builder()
                .id(app.getId())
                .displayId(app.getDisplayId())
                .userId(app.getUserId())
                .maskedFin(BolOdeMasking.maskFin(app.getFinCode()))
                .contactPhone(app.getContactPhone())
                .packageName(app.getPackageName())
                .durationMonths(app.getDurationMonths())
                .finalAmount(app.getFinalAmount())
                .status(app.getStatus().name())
                .assignedAdminUserId(app.getAssignedAdminUserId())
                .waitRemainingSeconds(waitRemainingSeconds(app))
                .createdAt(app.getCreatedAt())
                .updatedAt(app.getUpdatedAt())
                .build();
    }

    private BolOdeAdminDetail toAdminDetail(BolOdeApplication app) {
        return BolOdeAdminDetail.builder()
                .id(app.getId())
                .displayId(app.getDisplayId())
                .paymentId(app.getPaymentId())
                .paymentReference(app.getPaymentReference())
                .userId(app.getUserId())
                .finCode(app.getFinCode())
                .birthDate(app.getBirthDate())
                .contactPhone(app.getContactPhone())
                .extraPhone1(app.getExtraPhone1())
                .extraPhone2(app.getExtraPhone2())
                .birbankConsentAccepted(app.isBirbankConsentAccepted())
                .mygovConsentAccepted(app.isMygovConsentAccepted())
                .packageId(app.getPackageId())
                .optionId(app.getOptionId())
                .packageName(app.getPackageName())
                .durationMonths(app.getDurationMonths())
                .finalAmount(app.getFinalAmount())
                .status(app.getStatus().name())
                .declineReason(app.getDeclineReason())
                .declineReasonVisibleToCustomer(app.getDeclineReasonVisibleToCustomer())
                .assignedAdminUserId(app.getAssignedAdminUserId())
                .internalNote(app.getInternalNote())
                .waitRemainingSeconds(waitRemainingSeconds(app))
                .createdAt(app.getCreatedAt())
                .updatedAt(app.getUpdatedAt())
                .build();
    }
}
