package az.fitnest.payment.service;

import az.fitnest.payment.exception.BnplPaymentException;
import az.fitnest.payment.model.enums.BnplOrderStatus;
import az.fitnest.payment.service.bnpl.BnplContractRules;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract rules agreed with ABB (Oct 2026 mail exchange).
 */
class BnplContractRulesTest {

    // ------------------------------------------------------------------- term

    @Test
    void termIsAlwaysSentAsNumber() {
        for (int term : new int[]{1, 3, 4, 6, 9, 12, 18, 24}) {
            assertEquals(String.valueOf(term), BnplContractRules.formatTerm(term),
                    "ABB rejects \"N ay\" with 'The selected credit term is not available'");
        }
    }

    @Test
    void termValidationUsesConfiguredList() {
        List<Integer> testClientTerms = List.of(1, 4, 12);
        assertTrue(BnplContractRules.isConfiguredTerm(1, testClientTerms));
        assertTrue(BnplContractRules.isConfiguredTerm(12, testClientTerms));
        assertFalse(BnplContractRules.isConfiguredTerm(3, testClientTerms));
        assertFalse(BnplContractRules.isConfiguredTerm(24, testClientTerms));
        assertFalse(BnplContractRules.isConfiguredTerm(null, testClientTerms));
    }

    @Test
    void termValidationFallsBackToDocumentedUnionWhenUnconfigured() {
        assertTrue(BnplContractRules.isConfiguredTerm(9, List.of()));
        assertFalse(BnplContractRules.isConfiguredTerm(7, List.of()));
    }

    // ------------------------------------------------------------------ phone

    @Test
    void phoneIsNormalisedToInternationalFormat() {
        assertEquals("+994553210373", BnplContractRules.normalizePhone("+994553210373"));
        assertEquals("+994553210373", BnplContractRules.normalizePhone("+994 55 321 03 73"));
        assertEquals("+994553210373", BnplContractRules.normalizePhone("994553210373"));
        assertEquals("+994553210373", BnplContractRules.normalizePhone("0553210373"));
        assertEquals("+994553210373", BnplContractRules.normalizePhone("553210373"));
        assertEquals("+994773006679", BnplContractRules.normalizePhone("773006679"));
    }

    @Test
    void invalidPhoneIsRejectedInsteadOfForwarded() {
        assertThrows(BnplPaymentException.class, () -> BnplContractRules.normalizePhone(null));
        assertThrows(BnplPaymentException.class, () -> BnplContractRules.normalizePhone(" "));
        assertThrows(BnplPaymentException.class, () -> BnplContractRules.normalizePhone("+1 555 123 4567"));
        assertThrows(BnplPaymentException.class, () -> BnplContractRules.normalizePhone("12345"));
        assertThrows(BnplPaymentException.class, () -> BnplContractRules.normalizePhone("+9941234567"));
    }

    // ----------------------------------------------------------------- status

    @Test
    void closedIsNoOpAfterCompleted() {
        assertNull(BnplContractRules.resolveStatus(BnplOrderStatus.COMPLETED, BnplOrderStatus.CLOSED));
    }

    @Test
    void closedAppliesWhilePending() {
        assertEquals(BnplOrderStatus.CLOSED,
                BnplContractRules.resolveStatus(BnplOrderStatus.INIT, BnplOrderStatus.CLOSED));
        assertEquals("CLOSED", BnplOrderStatus.CLOSED.toPaymentStatus());
    }

    @Test
    void terminalStatusesNeverRegress() {
        assertNull(BnplContractRules.resolveStatus(BnplOrderStatus.COMPLETED, BnplOrderStatus.INIT));
        assertNull(BnplContractRules.resolveStatus(BnplOrderStatus.REVERSED, BnplOrderStatus.COMPLETED));
        assertNull(BnplContractRules.resolveStatus(BnplOrderStatus.CANCELLED, BnplOrderStatus.IN_PROGRESS));
    }

    @Test
    void onlyCompletedCanBecomeReversed() {
        assertEquals(BnplOrderStatus.REVERSED,
                BnplContractRules.resolveStatus(BnplOrderStatus.COMPLETED, BnplOrderStatus.REVERSED));
        assertNull(BnplContractRules.resolveStatus(BnplOrderStatus.INIT, BnplOrderStatus.REVERSED));
    }

    @Test
    void successfulPaymentIsNotDowngradedByClosed() {
        assertEquals("SUCCESS",
                BnplContractRules.resolvePaymentStatus("SUCCESS", BnplOrderStatus.CLOSED));
        assertEquals("CLOSED",
                BnplContractRules.resolvePaymentStatus("PENDING_USER_ACTION", BnplOrderStatus.CLOSED));
        assertEquals("REVERSED",
                BnplContractRules.resolvePaymentStatus("SUCCESS", BnplOrderStatus.REVERSED));
    }

    // ----------------------------------------------------------------- errors

    @Test
    void duplicateSubmitIsDetectedFromDetailText() {
        assertTrue(BnplContractRules.isDuplicateSubmit("Order already exists"));
        assertTrue(BnplContractRules.isDuplicateSubmit("ORDER ALREADY EXISTS for this customer"));
        assertFalse(BnplContractRules.isDuplicateSubmit("The selected credit term is not available: 12 ay"));
        assertFalse(BnplContractRules.isDuplicateSubmit(null));
    }

    @Test
    void ambiguousFailuresAreNeverTreatedAsDefinitive() {
        assertTrue(BnplContractRules.isAmbiguousSubmitFailure("BNPL_CONNECTION_ERROR", null));
        assertTrue(BnplContractRules.isAmbiguousSubmitFailure("BNPL_TIMEOUT", null));
        assertTrue(BnplContractRules.isAmbiguousSubmitFailure("BNPL_HTTP_504", null));
        assertTrue(BnplContractRules.isAmbiguousSubmitFailure("BNPL_RATE_LIMIT", null));
        assertFalse(BnplContractRules.isAmbiguousSubmitFailure("BNPL_BAD_REQUEST",
                "The selected credit term is not available"));
        assertFalse(BnplContractRules.isAmbiguousSubmitFailure(null, null));
    }

    // ---------------------------------------------------------------- reverse

    @Test
    void partialReversalsAccumulateUntilFullAmount() {
        BigDecimal total = BigDecimal.valueOf(300);
        assertEquals(BnplContractRules.ReverseOutcome.PARTIAL,
                BnplContractRules.reverseOutcome(total, BigDecimal.ZERO, BigDecimal.valueOf(100)));
        assertEquals(BnplContractRules.ReverseOutcome.PARTIAL,
                BnplContractRules.reverseOutcome(total, BigDecimal.valueOf(100), BigDecimal.valueOf(100)));
        assertEquals(BnplContractRules.ReverseOutcome.COMPLETE,
                BnplContractRules.reverseOutcome(total, BigDecimal.valueOf(200), BigDecimal.valueOf(100)));
        assertEquals(BnplContractRules.ReverseOutcome.EXCEEDS,
                BnplContractRules.reverseOutcome(total, BigDecimal.valueOf(250), BigDecimal.valueOf(100)));
    }

    @Test
    void reverseWindowCountsFromRequestDate() {
        assertTrue(BnplContractRules.isWithinReverseWindow(LocalDateTime.now().minusDays(29), 30));
        assertFalse(BnplContractRules.isWithinReverseWindow(LocalDateTime.now().minusDays(31), 30));
        assertFalse(BnplContractRules.isWithinReverseWindow(null, 30));
    }

    // -------------------------------------------------------------- signature

    @Test
    void hmacAcceptsHexAndBase64() throws Exception {
        String secret = "s3cret";
        byte[] body = "{\"orderId\":2830,\"status\":\"COMPLETED\"}".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(body);

        String hex = java.util.HexFormat.of().formatHex(raw);
        String base64 = Base64.getEncoder().encodeToString(raw);

        assertTrue(BnplContractRules.isValidSignature(secret, body, hex));
        assertTrue(BnplContractRules.isValidSignature(secret, body, hex.toUpperCase()));
        assertTrue(BnplContractRules.isValidSignature(secret, body, "sha256=" + hex));
        assertTrue(BnplContractRules.isValidSignature(secret, body, base64));
        assertFalse(BnplContractRules.isValidSignature(secret, body, "deadbeef"));
        assertFalse(BnplContractRules.isValidSignature(secret, body, null));
        assertFalse(BnplContractRules.isValidSignature("other", body, hex));
        assertFalse(BnplContractRules.isValidSignature(secret, "tampered".getBytes(StandardCharsets.UTF_8), hex));
    }

    @Test
    void basicAuthComparison() {
        String header = "Basic " + Base64.getEncoder()
                .encodeToString("fitnest:pass".getBytes(StandardCharsets.UTF_8));
        assertTrue(BnplContractRules.isValidBasicAuth(header, "fitnest", "pass"));
        assertFalse(BnplContractRules.isValidBasicAuth(header, "fitnest", "wrong"));
        assertFalse(BnplContractRules.isValidBasicAuth("Bearer x", "fitnest", "pass"));
        assertFalse(BnplContractRules.isValidBasicAuth(null, "fitnest", "pass"));
    }

    @Test
    void timestampParsingAndFreshness() {
        Instant now = Instant.now();
        assertTrue(BnplContractRules.isFresh(now, 300));
        assertFalse(BnplContractRules.isFresh(now.minusSeconds(600), 300));
        assertEquals(now.getEpochSecond(),
                BnplContractRules.parseTimestamp(String.valueOf(now.getEpochSecond())).getEpochSecond());
        assertNull(BnplContractRules.parseTimestamp("not-a-date"));
        assertNull(BnplContractRules.parseTimestamp(null));
    }
}
