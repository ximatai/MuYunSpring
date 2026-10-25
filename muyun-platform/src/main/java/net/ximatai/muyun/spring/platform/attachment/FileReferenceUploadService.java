package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.EntitySaveLifecycleListener;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.ability.child.AggregateChildMutationContext;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared provenance boundary for static and dynamic field references. */
@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FileReferenceUploadService extends AbstractAbilityService<FileReferenceUpload>
        implements EntitySaveLifecycleListener {
    private final FileReferenceUploadClaimDao claims;
    private final ObjectProvider<ManagedFileAssetService> assets;
    private final ObjectProvider<FileTransferClient> clients;
    private final Clock clock;

    public FileReferenceUploadService(FileReferenceUploadDao dao, FileReferenceUploadClaimDao claims, ObjectProvider<ManagedFileAssetService> assets,
                                      ObjectProvider<FileTransferClient> clients, Clock clock) {
        super("platform.file_reference_upload", FileReferenceUpload.class, dao);
        this.claims = Objects.requireNonNull(claims);
        this.assets = Objects.requireNonNull(assets);
        this.clients = Objects.requireNonNull(clients);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Records the actual inline upload. External uploads must first register their issued ticket. */
    public void record(String tenantId, FileReferenceUploadRequest request, String fileId) {
        var upload = admission(tenantId, request);
        upload.setFileId(fileId);
        upload.setExpiresAt(clock.instant().plusSeconds(3600));
        insert(upload);
    }

    public String registerTicket(String tenantId, FileReferenceUploadRequest request, FileTransferAccess access) {
        if (recordId(request.draft()) == null) throw new IllegalArgumentException("upload requires a stable draft record id");
        if (access.operation() != FileTransferOperation.UPLOAD || access.expiresAt() == null
                || !access.expiresAt().isAfter(clock.instant()) || access.accessToken() == null) {
            throw new IllegalArgumentException("upload requires a valid upload access");
        }
        var upload = admission(tenantId, request);
        // Keep a non-secret identifier for the exact issued credential; never persist the bearer token.
        upload.setTicketIdentifier(fingerprint(access.accessToken()));
        upload.setExpiresAt(access.expiresAt());
        insert(upload);
        return upload.getId();
    }

    /** The browser supplies only an opaque registration id and file id, never an uploader identity. */
    @Transactional
    public void bindUploadedFile(String moduleAlias, String ticketId, String fileId) {
        if (ticketId == null || ticketId.isBlank() || fileId == null || fileId.isBlank()) throw denied();
        var upload = getDao().findById(ticketId);
        String userId = currentUserId();
        if (upload == null || !Objects.equals(upload.getTenantId(), TenantContext.currentTenantId().orElse(null))
                || !userId.equals(upload.getUploaderUserId()) || !moduleAlias.equals(upload.getModuleAlias())
                || upload.getTicketIdentifier() == null || upload.getUsedAt() != null
                || !upload.getExpiresAt().isAfter(clock.instant())
                || upload.getFileId() != null && !fileId.equals(upload.getFileId())) throw denied();
        requireTemporary(fileId, upload.getUploaderUserId(), upload.getTenantId());
        if (fileId.equals(upload.getFileId())) return; // retry of the same completed registration
        var claim = new FileReferenceUploadClaim();
        claim.setId(fileId);
        claim.setTenantId(upload.getTenantId());
        claim.setRegistrationId(upload.getId());
        EntityLifecycle.prepareInsert(claim, clock.instant());
        try {
            claims.insert(claim);
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) throw denied();
            }
            throw failure;
        }
        upload.setFileId(fileId);
        Integer version = upload.getVersion();
        EntityLifecycle.prepareUpdate(upload, clock.instant());
        // The durable claim arbitrates files across tickets even after admission cleanup;
        // the version predicate arbitrates different files racing for the same ticket.
        if (getDao().updateByIdAndVersion(upload, version) != 1) throw new OptimisticLockException("upload registration changed");
    }

    public boolean ownsPending(String tenantId, FileReferenceReadRequest request) {
        String userId = CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null);
        if (userId == null) return false;
        var uploads = pending(tenantId, userId, request.moduleAlias(), request.relationCode(), request.fieldName(),
                recordId(request.draft()), request.fileId());
        if (uploads.isEmpty()) return false;
        if (uploads.getFirst().getTicketIdentifier() != null) requireTemporary(request.fileId(), userId, tenantId);
        return true;
    }

    @Override
    public <T extends EntityContract> void prepareValues(CrudAbility<T> ability, T existing, T incoming) {
        var user = CurrentUserContext.currentUser().orElse(null);
        if (user == null || user.system()) return;
        if (existing == null && incoming.getId() != null) existing = ability.selectActiveRaw(incoming.getId());
        T stored = existing;
        String tenantId = incoming.getTenantId() == null ? user.tenantId() : incoming.getTenantId();
        FileReferenceSaveLifecycleListener.definitions(incoming).forEach((field, definition) -> {
            if (incoming instanceof DynamicRecord record && !record.getPlatformValues().containsKey(field)) return;
            var retained = FileReferenceSaveLifecycleListener.values(stored, field, definition);
            for (String fileId : FileReferenceSaveLifecycleListener.values(incoming, field, definition)) {
                if (retained.contains(fileId)) continue;
                var target = target(ability, incoming);
                var uploads = pending(tenantId, user.userId(), target.moduleAlias(), target.relationCode(), field, incoming.getId(), fileId);
                if (definition.storagePolicy() == FileReferenceStoragePolicy.MUYUN_FILE_SERVER) {
                    if (uploads.isEmpty() || uploads.getFirst().getTicketIdentifier() == null) throw denied();
                    requireTemporary(fileId, user.userId(), tenantId);
                } else if (uploads.isEmpty()) {
                    var service = assets.getIfAvailable();
                    if (service == null || !user.userId().equals(service.requireOwned(tenantId, fileId).getCreatedBy())) throw denied();
                }
            }
        });
    }

    @Override
    public <T extends EntityContract> void persisted(CrudAbility<T> ability, T entity) {
        FileReferenceSaveLifecycleListener.definitions(entity).forEach((field, definition) -> {
            for (String fileId : FileReferenceSaveLifecycleListener.values(entity, field, definition)) {
                for (var upload : getDao().query(Criteria.of().eq("fileId", fileId).isNull("usedAt"), PageRequest.of(1, 1))) {
                    upload.setUsedAt(clock.instant());
                    Integer version = upload.getVersion();
                    EntityLifecycle.prepareUpdate(upload, clock.instant());
                    if (getDao().updateByIdAndVersion(upload, version) != 1) throw new OptimisticLockException("upload registration changed");
                }
            }
        });
    }

    /** Each job transaction removes at most 200 rows, across all tenants. */
    public int purgeExpiredBatch() {
        int removed = purge(Criteria.of().isNotNull("usedAt"), 200);
        return removed + purge(Criteria.of().lte("expiresAt", clock.instant()), 200 - removed);
    }

    private int purge(Criteria criteria, int limit) {
        if (limit == 0) return 0;
        int removed = 0;
        for (var row : getDao().query(criteria, PageRequest.of(1, limit), Sort.asc("expiresAt"), Sort.asc("id"))) {
            removed += getDao().deleteByIdAndVersion(row.getId(), row.getVersion());
        }
        return removed;
    }

    private List<FileReferenceUpload> pending(String tenant, String user, String module, String relation,
                                              String field, String record, String file) {
        return list(Criteria.of().eqNullable("tenantId", tenant).eq("fileId", file).eq("uploaderUserId", user)
                .eq("moduleAlias", module).eqNullable("relationCode", relation).eq("fieldName", field)
                .eqNullable("recordId", record).isNull("usedAt").gt("expiresAt", clock.instant()), PageRequest.of(1, 1));
    }

    private FileReferenceUpload admission(String tenantId, FileReferenceUploadRequest request) {
        var upload = new FileReferenceUpload();
        upload.setTenantId(tenantId);
        upload.setUploaderUserId(currentUserId());
        upload.setModuleAlias(request.moduleAlias());
        upload.setRelationCode(request.relationCode());
        upload.setFieldName(request.fieldName());
        upload.setRecordId(recordId(request.draft()));
        return upload;
    }

    private void requireTemporary(String fileId, String userId, String tenantId) {
        var client = clients.getIfAvailable();
        if (client == null) throw new PlatformConfigurationException("file transfer is not configured");
        var facts = client.readUploadFacts(fileId);
        var metadata = facts == null ? null : facts.metadata();
        // FileServer's metadata token already enforces the storage tenant, including system scope.
        if (metadata == null || !fileId.equals(metadata.fileId()) || !metadata.temporary()
                || !userId.equals(facts.uploaderUserId())
                || tenantId != null && !tenantId.equals(facts.tenantId())) throw denied();
    }

    private static String currentUserId() {
        return CurrentUserContext.currentUser().orElseThrow(() -> new AuthenticationRequiredException("file upload requires authentication")).userId();
    }

    private static String fingerprint(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static PlatformAccessDeniedException denied() {
        return new PlatformAccessDeniedException("file requires an own, matching, unexpired temporary upload registration");
    }

    private static String recordId(Map<String, Object> draft) {
        Object value = draft.get("id");
        return value instanceof String id && !id.isBlank() ? id : null;
    }

    private record Target(String moduleAlias, String relationCode) { }

    /** A child row is checked in the declaring aggregate's relation, rather than its service alias. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Target target(CrudAbility<?> ability, EntityContract incoming) {
        var declared = AggregateChildMutationContext.relationOf(incoming).orElse(null);
        if (declared != null) return new Target(declared.moduleAlias(), declared.relationCode());
        var parent = AggregateChildMutationContext.parentOf(ability).orElse(null);
        if (parent == null) return new Target(ability.getModuleAlias(), null);
        ChildrenAbility owner = (ChildrenAbility) parent.service();
        for (Object value : owner.childRelations()) {
            ChildRelation relation = (ChildRelation) value;
            var children = relation.incomingChildren(parent.current());
            if (children != null && children.stream().anyMatch(child -> child == incoming)) {
                return new Target(parent.service().getModuleAlias(), relation.relationCode());
            }
        }
        throw denied();
    }
}
