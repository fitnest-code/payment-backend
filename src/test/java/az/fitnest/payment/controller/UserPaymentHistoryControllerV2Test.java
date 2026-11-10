package az.fitnest.payment.controller;

import az.fitnest.payment.dto.common.PaymentOrderStatusV2Response;
import az.fitnest.payment.service.UserPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class UserPaymentHistoryControllerV2Test {

    @Mock
    private UserPaymentService userPaymentService;

    @InjectMocks
    private UserPaymentHistoryControllerV2 controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new UserPaymentHistoryControllerV2(userPaymentService);
    }

    @Test
    void getPaymentStatusByOrderId_returnsDtoForAuthenticatedUser() {
        Long userId = 55L;
        PaymentOrderStatusV2Response dto = PaymentOrderStatusV2Response.of("SUCCESS", "te022241588");
        when(userPaymentService.getPaymentOrderStatusByOrderId("ord_1", userId)).thenReturn(dto);

        Authentication auth = new UsernamePasswordAuthenticationToken(userId, null);
        SecurityContextHolder.getContext().setAuthentication(auth);

        ResponseEntity<PaymentOrderStatusV2Response> response =
                controller.getPaymentStatusByOrderId(auth, "ord_1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(dto, response.getBody());
    }

    @Test
    void getPaymentStatusByOrderId_unauthorizedWithoutUser() {
        SecurityContextHolder.clearContext();
        ResponseEntity<PaymentOrderStatusV2Response> response =
                controller.getPaymentStatusByOrderId(null, "ord_1");
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
