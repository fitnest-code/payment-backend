package az.fitnest.payment.service.kapital;

import az.fitnest.payment.model.enums.KapitalPaymentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KapitalStatusMapperTest {

    private final KapitalStatusMapper mapper = new KapitalStatusMapper();

    @Test
    void fullyPaidIsSuccessRegardlessOfCaseAndSpacing() {
        assertEquals(KapitalPaymentStatus.SUCCESS, mapper.map("FullyPaid"));
        assertEquals(KapitalPaymentStatus.SUCCESS, mapper.map("Fully paid"));
        assertEquals(KapitalPaymentStatus.SUCCESS, mapper.map(" FULLY_PAID "));
    }

    @Test
    void preparingAndAuthorizedAreInProgress() {
        assertEquals(KapitalPaymentStatus.IN_PROGRESS, mapper.map("Preparing"));
        assertEquals(KapitalPaymentStatus.IN_PROGRESS, mapper.map("Authorized"));
    }

    @Test
    void partiallyPaidNeedsReview() {
        assertEquals(KapitalPaymentStatus.PARTIALLY_PAID, mapper.map("PartiallyPaid"));
        assertEquals(KapitalPaymentStatus.PARTIALLY_PAID, mapper.map("Partially paid"));
    }

    @Test
    void terminalFailures() {
        for (String s : new String[]{"Declined", "Refused", "Rejected", "Cancelled", "Expired"}) {
            assertEquals(KapitalPaymentStatus.FAILED, mapper.map(s), s);
        }
    }

    @Test
    void refundedAndVoided() {
        assertEquals(KapitalPaymentStatus.REFUNDED, mapper.map("Refunded"));
        assertEquals(KapitalPaymentStatus.REFUNDED, mapper.map("Voided"));
    }

    @Test
    void unknownAndNullNeverFinalize() {
        assertEquals(KapitalPaymentStatus.UNKNOWN, mapper.map("Weird"));
        assertEquals(KapitalPaymentStatus.UNKNOWN, mapper.map(null));
    }
}
