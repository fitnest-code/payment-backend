package az.fitnest.payment.service.bob;

import az.fitnest.payment.dto.bob.BobOrderStatusResponse;
import az.fitnest.payment.model.enums.BobPaymentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BobStatusMapperTest {

    private final BobStatusMapper mapper = new BobStatusMapper();

    @Test
    void approvedWhenOrderStatus2() {
        assertTrue(mapper.isApproved(2));
        assertEquals(BobPaymentStatus.APPROVED, mapper.toBobStatus(2));
    }

    @Test
    void declineMessageNeverUsesBareSuccess() {
        BobOrderStatusResponse response = BobOrderStatusResponse.builder()
                .orderStatus(6)
                .errorMessage("Success")
                .actionCode("-2006")
                .build();

        String message = mapper.declineMessage(response);

        assertFalse(message.equalsIgnoreCase("Success"));
        assertTrue(message.contains("-2006"));
        assertEquals("-2006", mapper.operationCode(response));
    }

    @Test
    void declineMessagePrefersActionCodeDescription() {
        BobOrderStatusResponse response = BobOrderStatusResponse.builder()
                .orderStatus(6)
                .errorMessage("Success")
                .actionCode("-2006")
                .actionCodeDescription("Authorization declined by issuer")
                .build();

        assertEquals("Authorization declined by issuer", mapper.declineMessage(response));
    }
}
