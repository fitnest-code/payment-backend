package az.fitnest.payment.scheduler;

import az.fitnest.payment.client.abb.bnpl.AbbBnplProperties;
import az.fitnest.payment.service.BnplIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps local BNPL orders aligned with ABB.
 *
 * <p>ABB expires INIT orders 15-20 minutes after creation (5-minute job over a 15-minute
 * threshold) and only retries a callback twice. Without this job the local row stayed
 * {@code PENDING_USER_ACTION} forever unless the customer polled the status endpoint.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbbBnplReconciliationScheduler {

    private final BnplIntegrationService bnplIntegrationService;
    private final AbbBnplProperties properties;

    @Scheduled(
            fixedDelayString = "${abb-bnpl.reconcile-interval-ms:60000}",
            initialDelayString = "${abb-bnpl.reconcile-initial-delay-ms:45000}")
    public void reconcile() {
        if (!properties.isReconcileEnabled()) {
            return;
        }
        try {
            bnplIntegrationService.reconcileStaleOrders();
            bnplIntegrationService.retryOrphanCallbacks();
        } catch (Exception e) {
            log.warn("[BNPL][Reconcile] Run failed: {}", e.getMessage());
        }
    }
}
