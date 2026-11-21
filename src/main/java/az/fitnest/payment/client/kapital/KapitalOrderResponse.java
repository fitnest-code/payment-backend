package az.fitnest.payment.client.kapital;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Collection;
import java.util.Map;

/**
 * GET /order/{id}?tranDetailLevel=2&tokenDetailLevel=2&orderDetailLevel=2 response.
 *
 * <p>Kept as a loose map: the bank nests result codes/RRN inside tran/token detail whose exact
 * shape varies, so helpers search the tree instead of binding a rigid schema.</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KapitalOrderResponse {

    private Map<String, Object> order;

    public String getStatus() {
        return order == null ? null : asString(order.get("status"));
    }

    public String getPmoResultCode() {
        return findFirst(order, "pmoResultCode");
    }

    public String getRrn() {
        return findFirst(order, "rrn");
    }

    public String getApprovalCode() {
        return findFirst(order, "approvalCode");
    }

    /** First non-blank string value of {@code key} anywhere in the tree (depth-first). */
    static String findFirst(Object node, String key) {
        if (node instanceof Map<?, ?> map) {
            Object direct = map.get(key);
            if (direct != null && !asString(direct).isBlank() && !(direct instanceof Map) && !(direct instanceof Collection)) {
                return asString(direct);
            }
            for (Object value : map.values()) {
                String found = findFirst(value, key);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof Collection<?> collection) {
            for (Object value : collection) {
                String found = findFirst(value, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
