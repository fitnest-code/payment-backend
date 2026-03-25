package az.fitnest.payment.dto.common;

import az.fitnest.payment.config.JacksonConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InstantToCustomStringSerializerTest {
    @Test
    void testCustomFormat() throws JsonProcessingException {
        ObjectMapper mapper = new JacksonConfig().objectMapper();
        PaymentResponse response = new PaymentResponse(
                1L, 100.0, "USD", Instant.parse("2000-03-05T15:05:00Z"), "VISA", "****1234", "PAYMENT", "SUCCESS", null
        );
        String json = mapper.writeValueAsString(response);
        System.out.println(json); // For debug
        // Should contain formatted date: 05/03/2000 15:05
        assertTrue(json.contains("05/03/2000 15:05"), "Date format should be dd/MM/yyyy HH:mm");
    }
}
