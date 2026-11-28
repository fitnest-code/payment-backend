package az.fitnest.payment.service.kapital;

import az.fitnest.payment.dto.kapital.bolode.BolOdeAdminUpdateRequest;
import az.fitnest.payment.dto.kapital.bolode.BolOdeInitRequest;
import az.fitnest.payment.dto.kapital.bolode.BolOdeInitResponse;
import az.fitnest.payment.exception.BadRequestException;
import az.fitnest.payment.exception.BolOdeActiveApplicationException;
import az.fitnest.payment.exception.ConflictException;
import az.fitnest.payment.model.entity.BolOdeApplication;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.enums.BolOdeApplicationStatus;
import az.fitnest.payment.repository.BolOdeApplicationEventRepository;
import az.fitnest.payment.repository.BolOdeApplicationRepository;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.service.checkout.SubscriptionCheckoutService;
import az.fitnest.payment.service.kapital.bolode.BolOdeApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BolOdeApplicationServiceTest {

    private static final Long USER = 7L;

    @Mock
    private BolOdeApplicationRepository applicationRepository;
    @Mock
    private BolOdeApplicationEventRepository eventRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private SubscriptionCheckoutService checkoutService;
    @InjectMocks
    private BolOdeApplicationService service;

    private BolOdeApplication app(BolOdeApplicationStatus status) {
        BolOdeApplication a = new BolOdeApplication();
        a.setId(11L);
        a.setDisplayId("BÖ-1001");
        a.setUserId(USER);
        a.setStatus(status);
        a.setCreatedAt(Instant.now().minusSeconds(60));
        return a;
    }

    private BolOdeInitRequest validRequest() {
        BolOdeInitRequest r = new BolOdeInitRequest();
        r.setPackageId(1L);
        r.setOptionId(2L);
        r.setFinCode("5abc12d");
        r.setBirthDate(LocalDate.now().minusYears(30));
        r.setContactPhone("050 123 45 67");
        r.setBirbankConsentAccepted(true);
        r.setMygovConsentAccepted(true);
        return r;
    }

    @BeforeEach
    void noExistingActive() {
        lenient().when(applicationRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER), any()))
                .thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------ init

    @Test
    void initFailsWith409DetailsWhenActiveApplicationExists() {
        when(applicationRepository.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER), any()))
                .thenReturn(Optional.of(app(BolOdeApplicationStatus.IN_REVIEW)));

        BolOdeActiveApplicationException ex = assertThrows(BolOdeActiveApplicationException.class,
                () -> service.init(USER, validRequest()));

        assertEquals(11L, ex.getApplicationId());
        assertEquals("BÖ-1001", ex.getDisplayId());
        verifyNoInteractions(checkoutService);
    }

    @Test
    void initRejectsMissingConsent() {
        BolOdeInitRequest r = validRequest();
        r.setMygovConsentAccepted(false);
        assertThrows(BadRequestException.class, () -> service.init(USER, r));
        verify(applicationRepository, never()).saveAndFlush(any());
    }

    @Test
    void initRejectsInvalidFin() {
        BolOdeInitRequest r = validRequest();
        r.setFinCode("AB-12");
        assertThrows(BadRequestException.class, () -> service.init(USER, r));
    }

    @Test
    void initCreatesNewApplicationWithGeneratedDisplayId() {
        when(checkoutService.quote(eq(USER), eq(1L), eq(2L), eq(false)))
                .thenReturn(new SubscriptionCheckoutService.CheckoutQuote(
                        1L, 2L, 120.0, 120.0, "AZN", 6, BigDecimal.ZERO, "ref"));
        when(applicationRepository.nextDisplaySeq()).thenReturn(1042L);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            p.setId(99L);
            return p;
        });
        when(applicationRepository.saveAndFlush(any(BolOdeApplication.class))).thenAnswer(i -> {
            BolOdeApplication a = i.getArgument(0);
            a.setId(5L);
            a.setCreatedAt(Instant.now());
            return a;
        });

        BolOdeInitResponse res = service.init(USER, validRequest());

        assertEquals("BÖ-1042", res.getDisplayId());
        assertEquals("NEW", res.getStatus());
        assertEquals(6, res.getDurationMonths());
        assertTrue(res.getWaitRemainingSeconds() > 800 && res.getWaitRemainingSeconds() <= 900);
        verify(applicationRepository).saveAndFlush(argThat(a ->
                "5ABC12D".equals(a.getFinCode()) && "+994501234567".equals(a.getContactPhone())
                        && Long.valueOf(99L).equals(a.getPaymentId())));
        verify(eventRepository).save(argThat(e -> "SYSTEM".equals(e.getActorType())));
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void cancelAllowedFromNewPendingInReview() {
        for (BolOdeApplicationStatus s : new BolOdeApplicationStatus[]{
                BolOdeApplicationStatus.NEW, BolOdeApplicationStatus.PENDING, BolOdeApplicationStatus.IN_REVIEW}) {
            BolOdeApplication a = app(s);
            when(applicationRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(a));
            when(applicationRepository.save(a)).thenReturn(a);

            service.cancel(USER, 11L);

            assertEquals(BolOdeApplicationStatus.CANCELLED, a.getStatus(), "from " + s);
        }
    }

    @Test
    void cancelRejectedFromApprovedDeclinedCancelled() {
        for (BolOdeApplicationStatus s : new BolOdeApplicationStatus[]{
                BolOdeApplicationStatus.APPROVED, BolOdeApplicationStatus.DECLINED, BolOdeApplicationStatus.CANCELLED}) {
            when(applicationRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(app(s)));
            assertThrows(ConflictException.class, () -> service.cancel(USER, 11L), "from " + s);
        }
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void cancelOfAnotherUsersApplicationLooksNotFound() {
        when(applicationRepository.findByIdForUpdate(11L))
                .thenReturn(Optional.of(app(BolOdeApplicationStatus.NEW)));
        assertThrows(az.fitnest.payment.exception.ResourceNotFoundException.class,
                () -> service.cancel(999L, 11L));
    }

    // ----------------------------------------------------------- admin patch

    @Test
    void declineWithoutReasonIsBadRequest() {
        when(applicationRepository.findByIdForUpdate(11L))
                .thenReturn(Optional.of(app(BolOdeApplicationStatus.IN_REVIEW)));
        BolOdeAdminUpdateRequest req = new BolOdeAdminUpdateRequest();
        req.setStatus(BolOdeApplicationStatus.DECLINED);
        req.setDeclineReason("   ");

        assertThrows(BadRequestException.class, () -> service.adminUpdate(11L, 1L, req));
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void declineWithReasonSucceedsAndIsAudited() {
        BolOdeApplication a = app(BolOdeApplicationStatus.IN_REVIEW);
        when(applicationRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(a));
        when(applicationRepository.save(a)).thenReturn(a);
        BolOdeAdminUpdateRequest req = new BolOdeAdminUpdateRequest();
        req.setStatus(BolOdeApplicationStatus.DECLINED);
        req.setDeclineReason("Kredit tarixçəsi uyğun deyil");

        service.adminUpdate(11L, 1L, req);

        assertEquals(BolOdeApplicationStatus.DECLINED, a.getStatus());
        verify(eventRepository).save(argThat(e -> "ADMIN".equals(e.getActorType())
                && "Kredit tarixçəsi uyğun deyil".equals(e.getDeclineReason())));
    }

    @Test
    void terminalApplicationCannotChangeStatus() {
        when(applicationRepository.findByIdForUpdate(11L))
                .thenReturn(Optional.of(app(BolOdeApplicationStatus.CANCELLED)));
        BolOdeAdminUpdateRequest req = new BolOdeAdminUpdateRequest();
        req.setStatus(BolOdeApplicationStatus.IN_REVIEW);

        assertThrows(ConflictException.class, () -> service.adminUpdate(11L, 1L, req));
    }

    // ---------------------------------------------------------------- SLA

    @Test
    void waitRemainingIsZeroForDecidedApplications() {
        assertEquals(0, service.waitRemainingSeconds(app(BolOdeApplicationStatus.APPROVED)));
        assertTrue(service.waitRemainingSeconds(app(BolOdeApplicationStatus.NEW)) > 0);
    }
}
