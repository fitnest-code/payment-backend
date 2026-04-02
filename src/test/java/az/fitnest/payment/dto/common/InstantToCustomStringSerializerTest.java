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
        System.out.println(json);

        String expectedDateStr = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.parse("2000-03-05T15:05:00Z"));

        assertTrue(json.contains(expectedDateStr), "Date format should be dd/MM/yyyy HH:mm in system timezone");
    }
}
