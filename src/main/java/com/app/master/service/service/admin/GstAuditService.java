package com.app.master.service.service.admin;

import com.app.master.service.core.entity.GstAccountingExceptionEntity;
import com.app.master.service.core.entity.GstAuditLogEntity;
import com.app.master.service.repository.admin.GstAccountingExceptionRepository;
import com.app.master.service.repository.admin.GstAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit trail and accounting-exception recording.
 *
 * Exception recording runs in its own transaction (REQUIRES_NEW) so that a
 * failure recorded while the caller's transaction is rolling back still
 * survives — the whole point is that GST failures stop being invisible.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstAuditService {

    private final GstAuditLogRepository auditRepo;
    private final GstAccountingExceptionRepository exceptionRepo;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

    private Long orgId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::organizationId)
                .orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String entityType, Long entityId, String entityReference,
                    String action, String taxPeriod, String performedBy) {
        log(entityType, entityId, entityReference, action, null, null, null, taxPeriod, performedBy);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String entityType, Long entityId, String entityReference,
                    String action, String fieldName, String oldValue, String newValue,
                    String taxPeriod, String performedBy) {
        try {
            auditRepo.save(GstAuditLogEntity.builder()
                    .entityType(entityType)
                    .entityId(entityId)
                    .entityReference(entityReference)
                    .action(action)
                    .fieldName(fieldName)
                    .oldValue(oldValue)
                    .newValue(newValue)
                    .taxPeriod(taxPeriod)
                    .performedBy(performedBy != null ? performedBy : securityContext.actor())
                    .organizationId(orgId())
                    .build());
        } catch (Exception e) {
            // Audit must never break the operation it is recording.
            log.error("Failed to write GST audit log for {} {} action {}: {}",
                    entityType, entityId, action, e.getMessage());
        }
    }

    /**
     * Records a GST operation that could not complete. The caller's business
     * operation may still succeed, but this makes the gap reviewable.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordException(String operation, String sourceType, Long sourceId,
                                String sourceDocumentNumber, Exception cause) {
        try {
            exceptionRepo.save(GstAccountingExceptionEntity.builder()
                    .operation(operation)
                    .sourceType(sourceType)
                    .sourceId(sourceId)
                    .sourceDocumentNumber(sourceDocumentNumber)
                    .status("PENDING_REVIEW")
                    .errorMessage(cause != null ? cause.getMessage() : "Unknown error")
                    .errorDetail(cause != null ? stackHead(cause) : null)
                    .organizationId(orgId())
                    .build());
            log.error("GST accounting exception recorded: operation={} source={}#{} doc={} error={}",
                    operation, sourceType, sourceId, sourceDocumentNumber,
                    cause != null ? cause.getMessage() : "unknown");
        } catch (Exception e) {
            log.error("Could not persist GST accounting exception for {} {}: {}",
                    operation, sourceDocumentNumber, e.getMessage());
        }
    }

    public long pendingExceptionCount() {
        return exceptionRepo.countByStatus("PENDING_REVIEW");
    }

    private String stackHead(Exception e) {
        StringBuilder sb = new StringBuilder(e.getClass().getName()).append(": ").append(e.getMessage());
        StackTraceElement[] st = e.getStackTrace();
        for (int i = 0; i < Math.min(8, st.length); i++) {
            sb.append("\n\tat ").append(st[i]);
        }
        return sb.toString();
    }
}
