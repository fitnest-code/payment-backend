
import java.util.HashMap;
import java.util.Map;

public class EpointBankResponseCodes {
    private static final Map<String, String> codeToMessage = new HashMap<>();
    static {
        codeToMessage.put("000", "Confirmed");
        codeToMessage.put("100", "Rejected (general)");
        codeToMessage.put("101", "Declined, card expired");
        codeToMessage.put("102", "Suspected fraud");
        codeToMessage.put("103", "Cardholder will contact acquirer");
        codeToMessage.put("104", "Restricted card");
        codeToMessage.put("105", "Card receiver will contact acquirer security");
        codeToMessage.put("106", "PIN attempts exceeded");
        codeToMessage.put("107", "Declined, contact card issuer");
        codeToMessage.put("108", "Refer to issuer's special terms");
        codeToMessage.put("109", "Invalid merchant");
        codeToMessage.put("110", "Incorrect amount");
        codeToMessage.put("111", "Incorrect card number");
        codeToMessage.put("112", "PIN required");
        codeToMessage.put("113", "Inappropriate payment");
        codeToMessage.put("114", "No account of requested type");
        codeToMessage.put("115", "Requested function not supported");
        codeToMessage.put("116", "Insufficient funds");
        codeToMessage.put("117", "Incorrect PIN");
        codeToMessage.put("118", "No card data");
        codeToMessage.put("119", "Transaction not allowed by cardholder");
        codeToMessage.put("120", "Transaction not allowed to terminal");
        codeToMessage.put("121", "Withdrawal limit exceeded");
        codeToMessage.put("122", "Safety violation");
        codeToMessage.put("123", "Withdrawal limit exceeded (duplicate)");
        codeToMessage.put("124", "Violation of law");
        codeToMessage.put("125", "Card not valid");
        codeToMessage.put("126", "Invalid PIN block");
        codeToMessage.put("127", "PIN length error");
        codeToMessage.put("128", "PIN key sync failed");
        codeToMessage.put("129", "Suspected fake card");
        codeToMessage.put("180", "Rejected at cardholder request");
        codeToMessage.put("200", "Pick-up (general)");
        codeToMessage.put("201", "Pick-up, expired card");
        codeToMessage.put("202", "Pick-up, suspected fraud");
        codeToMessage.put("203", "Pick-up, card receiver will contact acquirer");
        codeToMessage.put("204", "Pick-up, restricted card");
        codeToMessage.put("205", "Pick-up, contact acquirer security");
        codeToMessage.put("206", "Pick-up, PIN limit exceeded");
        codeToMessage.put("207", "Pick-up, special conditions");
        codeToMessage.put("208", "Pick-up, lost card");
        codeToMessage.put("209", "Pick-up, stolen card");
        codeToMessage.put("210", "Pick-up, suspected fake card");
        codeToMessage.put("400", "Accepted (for cancellation)");
        codeToMessage.put("499", "Confirmed, no original message");
        codeToMessage.put("950", "Business agreement violation");
    }
    public static String getMessage(String code) {
        return codeToMessage.getOrDefault(code, "Unknown bank response code");
    }
}
