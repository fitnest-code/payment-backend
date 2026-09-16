package az.fitnest.payment.util;

import az.fitnest.payment.dto.epoint.EpointResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EpointTransactionIdsTest {

    @Test
    void widgetTokenMatchesLiveNineDigitTeShape() {
        assertEquals("tw022240181", EpointTransactionIds.fromWidgetToken("22240181"));
        assertEquals("tw022240181", EpointTransactionIds.fromWidgetToken("022240181"));
    }

    @Test
    void historicTenDigitWidgetIdStillProducesNineDigitCandidate() {
        List<String> candidates = EpointTransactionIds.lookupCandidates("tw0022240181");
        assertTrue(candidates.contains("tw0022240181"));
        assertTrue(candidates.contains("tw022240181"));
        assertEquals("tw0022240181", candidates.get(0));
        assertEquals("tw022240181", candidates.get(1));
    }

    @Test
    void uuidIsNotTreatedAsNumericToken() {
        List<String> candidates = EpointTransactionIds.lookupCandidates(
                "tw0022240181", "3874721e-3adb-4761-bf85-6b32c5f1990a");
        assertTrue(candidates.contains("tw0022240181"));
        assertTrue(candidates.contains("tw022240181"));
        assertTrue(candidates.contains("3874721e-3adb-4761-bf85-6b32c5f1990a"));
        assertFalse(candidates.stream().anyMatch(id -> id.length() > 20 && !id.contains("-")));
    }

    @Test
    void callbackWithoutPrefixStillMatchesStoredWidgetId() {
        List<String> candidates = EpointTransactionIds.lookupCandidates("22240181");
        assertTrue(candidates.contains("tw022240181"));
        assertTrue(candidates.contains("tw0022240181"));
    }

    @Test
    void widgetHistoryQueryIncludesTeFormEpointStoresAfterCallback() {
        List<String> candidates = EpointTransactionIds.lookupCandidates("tw022241588");
        assertTrue(candidates.contains("tw022241588"));
        assertTrue(candidates.contains("te022241588"));
    }

    @Test
    void preferredStoredIdKeepsWidgetPrefixWhenEpointReturnsTe() {
        assertEquals("tw022241588",
                EpointTransactionIds.preferredStoredId("tw022241588", "te022241588"));
        assertEquals("tw022240181",
                EpointTransactionIds.preferredStoredId("tw0022240181", "te022240181"));
        assertEquals("te022240154",
                EpointTransactionIds.preferredStoredId("te022240154", "te022240154"));
        assertEquals("te999",
                EpointTransactionIds.preferredStoredId("tw022241588", "te999"));
    }

    @Test
    void sameNumericTokenIgnoresPrefixAndPadding() {
        assertTrue(EpointTransactionIds.sameNumericToken("tw022241588", "te022241588"));
        assertTrue(EpointTransactionIds.sameNumericToken("tw022241588", "tw0022241588"));
        assertFalse(EpointTransactionIds.sameNumericToken("tw022241588", "te022240154"));
    }

    @Test
    void prefixSwitchIsOnlyCompatibleForWidgetTypes() {
        assertTrue(EpointTransactionIds.isAliasCompatible("tw022241588", "te022241588", "WIDGET_PAYMENT"));
        assertTrue(EpointTransactionIds.isAliasCompatible("tw022241588", "te022241588", "APPLE_PAY"));
        assertFalse(EpointTransactionIds.isAliasCompatible("tw022241588", "te022241588", "PAYMENT"));
        assertTrue(EpointTransactionIds.isAliasCompatible("tw022241588", "tw0022241588", "WIDGET_PAYMENT"));
        assertTrue(EpointTransactionIds.isAliasCompatible("te022240154", "te0022240154", "PAYMENT"));
    }

    @Test
    void selectAliasMatchPicksWidgetTeWhenClientSendsTw() {
        Row widget = new Row("te022241588", "WIDGET_PAYMENT", 27L);
        Optional<Row> picked = EpointTransactionIds.selectAliasMatch(
                "tw022241588", List.of(widget), 27L, Row::tx, Row::type, Row::userId);
        assertEquals(widget, picked.orElseThrow());
    }

    @Test
    void selectAliasMatchDoesNotReturnCardPaymentForTwQuery() {
        Row card = new Row("te022241588", "PAYMENT", 27L);
        Optional<Row> picked = EpointTransactionIds.selectAliasMatch(
                "tw022241588", List.of(card), 27L, Row::tx, Row::type, Row::userId);
        assertTrue(picked.isEmpty());
    }

    @Test
    void selectAliasMatchKeepsTwAndTeAsDifferentPaymentsWhenBothExist() {
        Row widget = new Row("tw022241588", "WIDGET_PAYMENT", 27L);
        Row card = new Row("te022241588", "PAYMENT", 27L);
        List<Row> both = List.of(widget, card);

        assertEquals(widget, EpointTransactionIds.selectAliasMatch(
                "tw022241588", both, 27L, Row::tx, Row::type, Row::userId).orElseThrow());
        assertEquals(card, EpointTransactionIds.selectAliasMatch(
                "te022241588", both, 27L, Row::tx, Row::type, Row::userId).orElseThrow());
    }

    @Test
    void selectAliasMatchIgnoresAnotherUsersRow() {
        Row otherUser = new Row("te022241588", "WIDGET_PAYMENT", 99L);
        Optional<Row> picked = EpointTransactionIds.selectAliasMatch(
                "tw022241588", List.of(otherUser), 27L, Row::tx, Row::type, Row::userId);
        assertTrue(picked.isEmpty());
    }

    @Test
    void selectAliasMatchPrefersSamePrefixWhenPaddingDuplicatesExist() {
        Row tenDigit = new Row("tw0022241588", "WIDGET_PAYMENT", 27L);
        Row teAlias = new Row("te022241588", "WIDGET_PAYMENT", 27L);
        Optional<Row> picked = EpointTransactionIds.selectAliasMatch(
                "tw022241588", List.of(tenDigit, teAlias), 27L, Row::tx, Row::type, Row::userId);
        assertEquals(tenDigit, picked.orElseThrow());
    }

    private record Row(String tx, String type, Long userId) {}

    @Test
    void serverErrorWithoutAttemptIsNotDefinitive() {
        EpointResponse unknown = EpointResponse.builder()
                .status("server_error")
                .code("500")
                .build();
        assertFalse(EpointTransactionIds.isDefinitiveStatus(unknown));
        assertFalse(EpointTransactionIds.hasBankAttempt(unknown));
    }

    @Test
    void newAndSuccessAreDefinitive() {
        assertTrue(EpointTransactionIds.isDefinitiveStatus(
                EpointResponse.builder().status("new").build()));
        assertTrue(EpointTransactionIds.isDefinitiveStatus(
                EpointResponse.builder().status("success").rrn("123").build()));
    }
}
