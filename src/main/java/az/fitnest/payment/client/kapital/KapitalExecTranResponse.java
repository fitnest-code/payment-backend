package az.fitnest.payment.client.kapital;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** exec-tran response; shape is bank-defined so keep it raw. HTTP success already means accepted. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KapitalExecTranResponse {

    private final Map<String, Object> raw = new LinkedHashMap<>();

    @JsonAnySetter
    public void put(String key, Object value) {
        raw.put(key, value);
    }
}
