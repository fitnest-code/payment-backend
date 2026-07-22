package az.fitnest.payment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/transfers")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payment Transfers Admin", description = "Köçürmə sorğuları və tarixçə ucluqları")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class PaymentTransferController {

    public record TransferRequestPayload(
        Double amount
    ) {}

    public record TransferRequestResponse(
        String requestId,
        Double amount,
        Double commission,
        Double netAmount,
        String status,
        LocalDateTime createdAt
    ) {}

    @Operation(summary = "Yeni köçürmə sorğusu yarat")
    @PostMapping("/request")
    public ResponseEntity<TransferRequestResponse> requestTransfer(@RequestBody TransferRequestPayload payload) {
        if (payload.amount() == null || payload.amount() <= 0) {
            throw new IllegalArgumentException("Köçürmə məbləği 0-dan böyük olmalıdır");
        }

        double commission = 0.0;
        double net = payload.amount() - commission;
        String reqId = "TR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        log.info("New transfer request created: id={}, amount={}, net={}", reqId, payload.amount(), net);

        return ResponseEntity.ok(new TransferRequestResponse(
            reqId,
            payload.amount(),
            commission,
            net,
            "PENDING",
            LocalDateTime.now()
        ));
    }

    @Operation(summary = "Köçürmələr tarixçəsini gətir")
    @GetMapping("/history")
    public ResponseEntity<List<TransferRequestResponse>> getTransferHistory() {
        return ResponseEntity.ok(Collections.emptyList());
    }
}
