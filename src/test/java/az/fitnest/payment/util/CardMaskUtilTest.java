package az.fitnest.payment.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CardMaskUtilTest {

    @Test
    void toLast4FromBobPan() {
        assertEquals("************0454", CardMaskUtil.toLast4("521097******0454"));
    }

    @Test
    void toLast4FromSpacedMask() {
        assertEquals("************1234", CardMaskUtil.toLast4("4111 11** **** 1234"));
    }

    @Test
    void toLast4Idempotent() {
        assertEquals("************0454", CardMaskUtil.toLast4("************0454"));
    }

    @Test
    void toLast4NullAndShort() {
        assertNull(CardMaskUtil.toLast4(null));
        assertEquals("", CardMaskUtil.toLast4(""));
        assertEquals(CardMaskUtil.EMPTY_DISPLAY, CardMaskUtil.toLast4("12"));
    }

    @Test
    void hasMoreThanLast4() {
        assertTrue(CardMaskUtil.hasMoreThanLast4("521097******0454"));
        assertFalse(CardMaskUtil.hasMoreThanLast4("************0454"));
    }
}
