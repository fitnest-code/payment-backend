package az.fitnest.payment.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WelcomeBonusRequest {
    private String phone;
    private String email;
    private String notificationTitle;
    private String notificationBody;
    private Boolean sendNotification;
}
