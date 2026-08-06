package az.fitnest.payment.service.bob;

import az.fitnest.payment.dto.bob.BobOrderStatusResponse;
import az.fitnest.payment.model.entity.Payment;
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
    void terminalFailureForDeclinedAndReversed() {
        assertTrue(mapper.isTerminalFailure(6));
        assertTrue(mapper.isTerminalFailure(3));
        assertFalse(mapper.isTerminalFailure(2));
        assertFalse(mapper.isTerminalFailure(0));
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

    @Test
    void enrichFlattensCardAuthInfoAndUsesPaymentSystemBrand() {
        BobOrderStatusResponse response = BobOrderStatusResponse.builder()
                .orderStatus(2)
                .errorCode("0")
                .errorMessage("Success")
                .authRefNum("123456789012")
                .cardAuthInfo(new BobOrderStatusResponse.CardAuthInfo(
                        "521097******0454",
                        "202301",
                        "JOHN DOE",
                        "MASTERCARD",
                        "ABC123",
                        null))
                .build();

        Payment payment = new Payment();
        payment.setType("BOB_INSTALLMENT");

        mapper.enrichStatusResponse(response, payment);

        assertEquals("521097******0454", response.getPan());
        assertEquals("521097******0454", response.getCardMask());
        assertEquals("JOHN DOE", response.getCardholderName());
        assertEquals("ABC123", response.getApprovalCode());
        assertEquals("123456789012", response.getRrn());
        assertEquals("MASTERCARD", response.getCardBrand());
        assertEquals("Bank of Baku", response.getBank());
        assertEquals("Taksitli ödəniş", response.getType());
    }

    @Test
    void enrichReplacesMisleadingSuccessOnDecline() {
        BobOrderStatusResponse response = BobOrderStatusResponse.builder()
                .orderStatus(6)
                .errorCode("0")
                .errorMessage("Success")
                .actionCode("-2006")
                .build();

        mapper.enrichStatusResponse(response, null);

        assertFalse(response.getErrorMessage().equalsIgnoreCase("Success"));
        assertTrue(response.getErrorMessage().contains("-2006"));
        assertNull(response.getRrn());
    }

    @Test
    void installmentTypeLabel() {
        Payment installment = new Payment();
        installment.setType("BOB_INSTALLMENT");
        assertEquals("Taksitli ödəniş", mapper.resolvePaymentTypeLabel(installment, "AZ"));
        assertEquals("Installment", mapper.resolvePaymentTypeLabel(installment, "EN"));
        assertEquals("Рассрочка", mapper.resolvePaymentTypeLabel(installment, "RU"));

        Payment oneTime = new Payment();
        oneTime.setType("BOB_PAYMENT");
        assertEquals("Birdəfəlik", mapper.resolvePaymentTypeLabel(oneTime, "AZ"));
        assertEquals("One-time", mapper.resolvePaymentTypeLabel(oneTime, "EN"));
    }

    @Test
    void enrichUsesLanguageForType() {
        BobOrderStatusResponse response = BobOrderStatusResponse.builder()
                .orderStatus(2)
                .errorCode("0")
                .errorMessage("Success")
                .build();
        Payment payment = new Payment();
        payment.setType("BOB_INSTALLMENT");

        mapper.enrichStatusResponse(response, payment, "EN");
        assertEquals("Installment", response.getType());
    }
}
