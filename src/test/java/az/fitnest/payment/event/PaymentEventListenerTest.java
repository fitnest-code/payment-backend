package az.fitnest.payment.event;

import az.fitnest.payment.dto.coin.WelcomeBonusRequest;
import az.fitnest.payment.dto.coin.CoinWalletResponse;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import az.fitnest.payment.service.CoinWalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentEventListenerTest {

    @Mock
    private UserCardRepository userCardRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private CoinWalletService coinWalletService;

    @InjectMocks
    private PaymentEventListener paymentEventListener;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Inject real ObjectMapper since InjectMocks won't auto-create it
        paymentEventListener = new PaymentEventListener(userCardRepository, paymentRepository, coinWalletService, objectMapper);
    }

    @Test
    @DisplayName("REGISTRATION_COMPLETED - Welcome Bonus avtomatik verilir, client müraciəti tələb olunmur")
    void testRegistrationCompleted_AwardsWelcomeBonus() throws Exception {
        String event = """
                {
                  "eventType": "REGISTRATION_COMPLETED",
                  "userId": 100,
                  "phone": "+994501234567",
                  "email": "user@fitnest.az"
                }
                """;

        CoinWalletResponse mockResponse = CoinWalletResponse.builder()
                .totalBalance(new BigDecimal("50.00"))
                .build();
        when(coinWalletService.awardWelcomeBonus(eq(100L), any(WelcomeBonusRequest.class)))
                .thenReturn(mockResponse);

        paymentEventListener.consumeUserEvent(event);

        ArgumentCaptor<WelcomeBonusRequest> captor = ArgumentCaptor.forClass(WelcomeBonusRequest.class);
        verify(coinWalletService, times(1)).awardWelcomeBonus(eq(100L), captor.capture());
        assertEquals("+994501234567", captor.getValue().getPhone());
        assertEquals("user@fitnest.az", captor.getValue().getEmail());
    }

    @Test
    @DisplayName("REGISTRATION_COMPLETED - Bonus artıq verilibsə (idempotent) xəta atmır")
    void testRegistrationCompleted_DuplicateBonusIsIdempotent() throws Exception {
        String event = """
                {
                  "eventType": "REGISTRATION_COMPLETED",
                  "userId": 100
                }
                """;

        when(coinWalletService.awardWelcomeBonus(eq(100L), any()))
                .thenThrow(new RuntimeException("Welcome bonus bu istifadəçiyə artıq verilib"));

        // Should not throw — handled gracefully
        paymentEventListener.consumeUserEvent(event);

        verify(coinWalletService, times(1)).awardWelcomeBonus(eq(100L), any());
    }

    @Test
    @DisplayName("USER_HARD_DELETED - User kartları və ödənişlər silinir")
    void testUserHardDeleted_DeletesUserData() throws Exception {
        String event = """
                {
                  "eventType": "USER_HARD_DELETED",
                  "userId": 200
                }
                """;

        paymentEventListener.consumeUserEvent(event);

        verify(userCardRepository, times(1)).deleteByUserId(200L);
        verify(paymentRepository, times(1)).deleteByUserId(200L);
        verifyNoInteractions(coinWalletService);
    }

    @Test
    @DisplayName("Bilinməyən event tipi - heç bir əməliyyat baş vermir")
    void testUnknownEventType_IsIgnored() throws Exception {
        String event = """
                {
                  "eventType": "SOME_OTHER_EVENT",
                  "userId": 300
                }
                """;

        paymentEventListener.consumeUserEvent(event);

        verifyNoInteractions(coinWalletService);
        verifyNoInteractions(userCardRepository);
        verifyNoInteractions(paymentRepository);
    }
}
