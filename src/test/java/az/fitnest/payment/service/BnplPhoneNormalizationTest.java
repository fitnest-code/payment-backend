package az.fitnest.payment.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ABB BNPL expects the phone in international format (+994XXXXXXXXX).
 */
class BnplPhoneNormalizationTest {

    @Test
    void keepsInternationalFormat() {
        assertEquals("+994553210373", BnplIntegrationService.normalizePhone("+994553210373"));
        assertEquals("+994553210373", BnplIntegrationService.normalizePhone("+994 55 321 03 73"));
    }

    @Test
    void addsCountryCodeWhenMissing() {
        assertEquals("+994553210373", BnplIntegrationService.normalizePhone("994553210373"));
        assertEquals("+994553210373", BnplIntegrationService.normalizePhone("0553210373"));
        assertEquals("+994553210373", BnplIntegrationService.normalizePhone("553210373"));
    }

    @Test
    void leavesUnknownShapeUntouched() {
        assertEquals("+1 555 123 4567", BnplIntegrationService.normalizePhone("  +1 555 123 4567  "));
    }
}
