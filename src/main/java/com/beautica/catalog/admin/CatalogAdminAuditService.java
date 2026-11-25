package com.beautica.catalog.admin;

import com.beautica.catalog.admin.entity.CatalogAdminAudit;
import com.beautica.catalog.admin.repository.CatalogAdminAuditRepository;
import com.beautica.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes the append-only catalog-admin audit row (B501, SVC-53). {@link Propagation#MANDATORY}: it joins the
 * caller's write transaction, so the row exists if and only if the catalog change commits; a call outside a
 * transaction throws {@code IllegalTransactionStateException}. {@code before}/{@code after} carry catalog fields only
 * — a requester's user id is fine, their e-mail or phone is not.
 */
@Service
@RequiredArgsConstructor
public class CatalogAdminAuditService {

    private static final Pattern ACTION = Pattern.compile("^[A-Z][A-Z_]*[A-Z]$");

    private final CatalogAdminAuditRepository repository;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AdminActor actor, String action, CatalogAuditTargetType targetType, String targetId,
                       Map<String, Object> before, Map<String, Object> after) {
        requireValid(actor, action, targetType, targetId);
        repository.save(CatalogAdminAudit.of(actor.email(), action, targetType, targetId.strip(),
                before, after, clock.instant()));
    }

    private static void requireValid(AdminActor actor, String action, CatalogAuditTargetType targetType,
                                     String targetId) {
        if (actor == null || targetType == null) {
            throw invalid("actor and target type are required");
        }
        if (action == null || action.length() > CatalogAdminAudit.ACTION_MAX_LENGTH || !ACTION.matcher(action).matches()) {
            throw invalid("action must be upper snake case, at most " + CatalogAdminAudit.ACTION_MAX_LENGTH + " chars");
        }
        if (targetId == null || targetId.isBlank() || targetId.strip().length() > CatalogAdminAudit.TARGET_ID_MAX_LENGTH) {
            throw invalid("target id must be non-blank, at most " + CatalogAdminAudit.TARGET_ID_MAX_LENGTH + " chars");
        }
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "Invalid catalog audit entry: " + detail);
    }
}
