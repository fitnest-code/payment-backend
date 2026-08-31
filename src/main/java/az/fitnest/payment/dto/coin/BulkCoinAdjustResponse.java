package az.fitnest.payment.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkCoinAdjustResponse {
    private int totalRequested;
    private int totalSuccess;
    private int totalFailed;
    private List<Long> successUserIds;
    private List<Long> failedUserIds;
}
