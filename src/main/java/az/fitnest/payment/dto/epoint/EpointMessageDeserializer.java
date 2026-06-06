package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

public class EpointMessageDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject()) {
            if (node.has("az")) {
                return node.get("az").asText();
            }
            if (node.has("en")) {
                return node.get("en").asText();
            }
            var fields = node.fields();
            if (fields.hasNext()) {
                return fields.next().getValue().asText();
            }
        }
        return node.toString();
    }
}
