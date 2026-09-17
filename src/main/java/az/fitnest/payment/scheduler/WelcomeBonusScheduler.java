package az.fitnest.payment.scheduler;

import az.fitnest.payment.dto.coin.BulkCoinAdjustResponse;
import az.fitnest.payment.dto.coin.BulkWelcomeBonusRequest;
import az.fitnest.payment.service.CoinWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WelcomeBonusScheduler {

    private final CoinWalletService coinWalletService;

    /**
     * Daily catch-up for users who registered but never received the welcome bonus
     * (missed Kafka event, registered before the feature, etc.).
     */
    @Scheduled(cron = "${payment.welcome-bonus.catchup-cron:0 0 2 * * *}")
    public void grantPendingWelcomeBonuses() {
        log.info("Starting daily welcome bonus catch-up...");
        try {
            BulkCoinAdjustResponse result = coinWalletService.bulkWelcomeBonus(
                    BulkWelcomeBonusRequest.builder()
                            .notificationTitle("Xoş gəldin bonusu")
                            .notificationBody("FitNest Coin hesabınıza əlavə edildi.")
                            .sendNotification(true)
                            .build());
            log.info(
                    "Welcome bonus catch-up completed: requested={}, success={}, failed={}",
                    result.getTotalRequested(),
                    result.getTotalSuccess(),
                    result.getTotalFailed());
        } catch (Exception e) {
            log.error("Error during daily welcome bonus catch-up", e);
        }
    }
}
