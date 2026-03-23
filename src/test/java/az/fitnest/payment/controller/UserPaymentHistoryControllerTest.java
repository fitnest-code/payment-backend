package az.fitnest.payment.controller;

import az.fitnest.payment.dto.common.PaymentResponse;
import az.fitnest.payment.service.UserPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import java.security.Principal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserPaymentHistoryControllerTest {
    @Mock
    private UserPaymentService userPaymentService;

    @InjectMocks
    private UserPaymentHistoryController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new UserPaymentHistoryController(userPaymentService);
    }

    @Test
    void getUserPaymentHistory_returnsPaymentsForAuthenticatedUser() {
        Long userId = 123L;
        List<PaymentResponse> mockPayments = Collections.singletonList(mock(PaymentResponse.class));
        when(userPaymentService.getUserPayments(userId)).thenReturn(mockPayments);
        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        SecurityContextHolder.getContext().setAuthentication(auth);
        ResponseEntity<List<PaymentResponse>> response = controller.getUserPaymentHistory(auth);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(mockPayments, response.getBody());
    }

    @Test
    void getUserPaymentHistory_returnsUnauthorizedIfNoUser() {
        SecurityContextHolder.clearContext();
        ResponseEntity<List<PaymentResponse>> response = controller.getUserPaymentHistory(null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}

