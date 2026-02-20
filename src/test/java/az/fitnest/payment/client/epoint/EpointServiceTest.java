package az.fitnest.payment.client.epoint;

import az.fitnest.payment.client.epoint.dto.EpointPaymentRequest;
import az.fitnest.payment.client.epoint.dto.EpointResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EpointServiceTest {

    @Mock
    private EpointHttpClient httpClient;

    @Mock
    private EpointProperties properties;

    @InjectMocks
    private EpointService epointService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(properties.getPublicKey()).thenReturn("test_pub");
    }

    @Test
    void testCreatePayment() {
        EpointPaymentRequest request = EpointPaymentRequest.builder()
                .orderId("123")
                .amount(10.0)
                .build();
        
        EpointResponse mockResponse = new EpointResponse();
        mockResponse.setStatus("success");
        mockResponse.setTransaction("trans_123");
        
        when(httpClient.postSigned(eq("/request"), any())).thenReturn(mockResponse);
        
        EpointResponse response = epointService.createPayment(request);
        
        assertEquals("success", response.getStatus());
        assertEquals("trans_123", response.getTransaction());
        verify(httpClient).postSigned(eq("/request"), eq(request));
        assertEquals("test_pub", request.getPublicKey());
    }

    @Test
    void testGetStatus() {
        EpointResponse mockResponse = new EpointResponse();
        mockResponse.setStatus("success");
        
        when(httpClient.postSigned(eq("/get-status"), any())).thenReturn(mockResponse);
        
        EpointResponse response = epointService.getStatus("trans_123");
        
        assertEquals("success", response.getStatus());
        verify(httpClient).postSigned(eq("/get-status"), any());
    }
}
