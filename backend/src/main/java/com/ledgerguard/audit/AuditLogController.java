package com.ledgerguard.audit;

import com.ledgerguard.common.PageResponse;
import com.ledgerguard.company.CompanyAccessService;
import com.ledgerguard.security.AuthUser;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 조회 전용. 이력의 생성은 서비스 내부(AuditService)에서만 일어난다. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/audit-logs")
public class AuditLogController {

    private final AuditLogRepository repository;
    private final CompanyAccessService access;
    private final ZoneId zone;

    public AuditLogController(AuditLogRepository repository, CompanyAccessService access,
                              @Value("${app.zone:Asia/Seoul}") String zone) {
        this.repository = repository;
        this.access = access;
        this.zone = ZoneId.of(zone);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> list(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable Long companyId,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        access.requireWrite(user.userId(), companyId); // ADMIN, ACCOUNTANT만 조회 가능

        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("companyId"), companyId));
            if (actorId != null) {
                p.add(cb.equal(root.get("actorId"), actorId));
            }
            if (action != null && !action.isBlank()) {
                p.add(cb.equal(root.get("action"), action));
            }
            if (targetType != null && !targetType.isBlank()) {
                p.add(cb.equal(root.get("targetType"), targetType));
            }
            if (targetId != null) {
                p.add(cb.equal(root.get("targetId"), targetId));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt").as(java.time.Instant.class),
                        from.atStartOfDay(zone).toInstant()));
            }
            if (to != null) {
                p.add(cb.lessThan(root.get("createdAt").as(java.time.Instant.class),
                        to.plusDays(1).atStartOfDay(zone).toInstant()));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return PageResponse.from(repository.findAll(spec, pageable).map(AuditLogResponse::from));
    }
}
