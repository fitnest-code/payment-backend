package az.fitnest.payment.dto.coin;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkWelcomeBonusRequest {

    @NotBlank(message = "Bildiriş başlığı boş ola bilməz")
    private String notificationTitle;

    @NotBlank(message = "Bildiriş mətni boş ola bilməz")
    private String notificationBody;

    @Builder.Default
    private Boolean sendNotification = true;
}
