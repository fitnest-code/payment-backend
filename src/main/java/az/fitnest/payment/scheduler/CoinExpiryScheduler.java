package az.fitnest.payment.scheduler;

import az.fitnest.payment.service.CoinWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoinExpiryScheduler {

    private final CoinWalletService coinWalletService;

    /**
     * Daily Cron job running at 01:00 AM to process expired coins (BR-16, BR-42).
     */
    @Scheduled(cron = "0 0 1 * * *")
    public void processExpiredCoins() {
        log.info("Starting daily Coin expiry scheduler task...");
        try {
            coinWalletService.expireOutdatedCoins();
            log.info("Daily Coin expiry scheduler task completed successfully.");
        } catch (Exception e) {
            log.error("Error during daily Coin expiry scheduler execution", e);
        }
    }
}
