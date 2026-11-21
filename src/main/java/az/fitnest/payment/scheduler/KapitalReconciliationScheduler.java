package az.fitnest.payment.scheduler;

import az.fitnest.payment.client.kapital.KapitalProperties;
import az.fitnest.payment.service.KapitalIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finalizes KAPITAL payments whose payer never returned to the result URL (closed tab, lost network).
 *
 * <p>Each pending payment older than {@code kapital.pending-min-age-ms} is re-read via GET /order and
 * finalized idempotently; orders still Preparing after {@code kapital.preparing-timeout-ms} are failed.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KapitalReconciliationScheduler {

    private final KapitalIntegrationService kapitalIntegrationService;
    private final KapitalProperties properties;

    @Scheduled(
            fixedDelayString = "${kapital.reconcile-interval-ms:180000}",
            initialDelayString = "${kapital.reconcile-initial-delay-ms:60000}")
    public void reconcile() {
        if (!properties.isReconcileEnabled() || properties.isMaintenanceMode()) {
            return;
        }
        try {
            int processed = kapitalIntegrationService.reconcilePending();
            if (processed > 0) {
                log.info("[KAPITAL][Reconcile] Processed {} pending payment(s)", processed);
            }
        } catch (Exception e) {
            log.warn("[KAPITAL][Reconcile] Run failed: {}", e.getMessage());
        }
    }
}
