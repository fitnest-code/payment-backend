package az.fitnest.payment.controller;

import az.fitnest.payment.dto.kapital.bolode.*;
import az.fitnest.payment.model.enums.BolOdeApplicationStatus;
import az.fitnest.payment.service.kapital.bolode.BolOdeApplicationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Admin back-office for manually relaying Böl ödə applications to Kapital Bank. */
@RestController
@RequestMapping("/api/v1/admin/bol-ode")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Kapital Böl ödə (admin)")
public class BolOdeAdminController {

    private final BolOdeApplicationService service;

    @GetMapping
    public Page<BolOdeAdminListItem> list(
            @RequestParam(required = false) BolOdeApplicationStatus status,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long assignedAdminUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.adminList(status, query, userId, assignedAdminUserId, page, size);
    }

    @GetMapping("/{id}")
    public BolOdeAdminDetail detail(@PathVariable Long id) {
        return service.adminDetail(id);
    }

    @PatchMapping("/{id}")
    public BolOdeAdminDetail update(@PathVariable Long id,
                                    @RequestBody BolOdeAdminUpdateRequest request,
                                    Authentication authentication) {
        Long adminId = authentication != null && authentication.getPrincipal() instanceof Long l ? l : null;
        return service.adminUpdate(id, adminId, request);
    }

    @GetMapping("/{id}/history")
    public List<BolOdeHistoryItem> history(@PathVariable Long id) {
        return service.adminHistory(id);
    }
}
