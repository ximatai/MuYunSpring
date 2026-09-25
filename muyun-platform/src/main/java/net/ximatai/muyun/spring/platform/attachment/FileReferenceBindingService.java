package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.Objects;

/** Shared admission and ownership boundary for business file fields and record attachments. */
@Service
public class FileReferenceBindingService {
    private static final Logger log = LoggerFactory.getLogger(FileReferenceBindingService.class);
    private final ObjectProvider<FileTransferClient> clients;
    private final FileReferenceOwnershipDao ownership;

    public FileReferenceBindingService(ObjectProvider<FileTransferClient> clients,
                                       FileReferenceOwnershipDao ownership) {
        this.clients = Objects.requireNonNull(clients);
        this.ownership = Objects.requireNonNull(ownership);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public FileTransferFileMetadata bind(String tenantId, String moduleAlias, String recordId,
                                         String fieldName, String fileId, FileReferenceDefinition definition) {
        FileTransferClient client = clients.getIfAvailable();
        if (client == null) throw new PlatformException("file transfer client is not configured");
        if (!TransactionScopeSupport.isTransactionActive()) {
            throw new IllegalStateException("file reference binding requires an active transaction");
        }
        FileReferenceOwnership claim = new FileReferenceOwnership();
        claim.setId(required(fileId, "fileId"));
        claim.setTenantId(tenantId);
        claim.setModuleAlias(required(moduleAlias, "moduleAlias"));
        claim.setRecordId(required(recordId, "recordId"));
        claim.setFieldName(required(fieldName, "fieldName"));
        EntityLifecycle.prepareInsert(claim, Instant.now());
        try {
            ownership.insert(claim);
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    throw PlatformErrors.conflict(PlatformErrorCodes.FILE_REFERENCE_ALREADY_BOUND,
                            "file is already bound to a business reference: " + claim.getId(), failure,
                            ErrorScope.module(moduleAlias), Map.of("fieldName", fieldName, "fileId", claim.getId()));
                }
            }
            throw failure;
        }
        FileTransferFileMetadata metadata = new FileReferenceConfirmationService(client).confirmAndPromote(definition, fileId);
        TransactionScopeSupport.afterCompletionOrNow(() -> {}, () ->
                log.error("File reference was promoted but record save did not complete: moduleAlias={}, recordId={}, fieldName={}, fileId={}",
                        moduleAlias, recordId, fieldName, fileId));
        return metadata;
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
