package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

/** Invoked after current definition/version authorization, in the same transaction as design save. */
@Service
public class WorkflowDesignSaveReceiptService {
    private final BaseDao<WorkflowDesignSaveReceipt, String> receipts;

    public WorkflowDesignSaveReceiptService(BaseDao<WorkflowDesignSaveReceipt, String> receipts) {
        this.receipts = Objects.requireNonNull(receipts);
    }

    @Transactional
    public WorkflowVersion execute(String requestId, String definitionId, String versionId, String payload,
            Supplier<WorkflowVersion> save, Supplier<WorkflowVersion> authorizedRead) {
        String key = requestDigest(requestId);
        PlatformAbilityRuntime.lockMutationPartition("workflow.design-save", key);
        String fingerprint = digest(payload);
        var existing = find(key);
        if (existing != null) {
            if (!Objects.equals(existing.getDefinitionId(), definitionId)
                    || !Objects.equals(existing.getWorkflowVersionId(), versionId)
                    || Boolean.TRUE.equals(existing.getCancelled())
                    || !Objects.equals(existing.getPayloadDigest(), fingerprint))
                throw new IllegalArgumentException("保存请求内容已变化，请重新核对后保存");
            return authorizedRead.get();
        }
        var saved = Objects.requireNonNull(save.get());
        var receipt = new WorkflowDesignSaveReceipt();
        receipt.setId(key.substring(0, 32));
        receipt.setRequestDigest(key);
        receipt.setPayloadDigest(fingerprint);
        receipt.setDefinitionId(definitionId);
        receipt.setWorkflowVersionId(versionId);
        receipt.setSavedVersion(saved.getVersion());
        receipt.setCancelled(false);
        receipt.setTenantId(TenantContext.currentTenantId().orElse(null));
        EntityLifecycle.prepareInsert(receipt, Instant.now());
        receipts.insert(receipt);
        return saved;
    }

    public Result lookup(String requestId, String definitionId, String versionId) {
        var receipt = find(requestDigest(requestId));
        return result(receipt, definitionId, versionId);
    }

    /** Absence is not proof of rejection: the original transaction may still be running. */
    public record Result(boolean committed, boolean cancelled, Integer savedVersion) {}

    /** A durable fence prevents a request that has not arrived yet from writing later. */
    @Transactional
    public Result abandon(String requestId, String definitionId, String versionId) {
        String key = requestDigest(requestId);
        PlatformAbilityRuntime.lockMutationPartition("workflow.design-save", key);
        var receipt = find(key);
        if (receipt != null) {
            if (!definitionId.equals(receipt.getDefinitionId()) || !versionId.equals(receipt.getWorkflowVersionId()))
                throw new IllegalArgumentException("保存请求归属不一致");
            return result(receipt, definitionId, versionId);
        }
        receipt = new WorkflowDesignSaveReceipt();
        receipt.setId(key.substring(0, 32));
        receipt.setRequestDigest(key);
        receipt.setPayloadDigest("");
        receipt.setDefinitionId(definitionId);
        receipt.setWorkflowVersionId(versionId);
        receipt.setCancelled(true);
        receipt.setTenantId(TenantContext.currentTenantId().orElse(null));
        EntityLifecycle.prepareInsert(receipt, Instant.now());
        receipts.insert(receipt);
        return result(receipt, definitionId, versionId);
    }

    private Result result(WorkflowDesignSaveReceipt receipt, String definitionId, String versionId) {
        if (receipt == null || !definitionId.equals(receipt.getDefinitionId())
                || !versionId.equals(receipt.getWorkflowVersionId())) return new Result(false, false, null);
        boolean cancelled = Boolean.TRUE.equals(receipt.getCancelled());
        return new Result(!cancelled, cancelled, receipt.getSavedVersion());
    }

    private WorkflowDesignSaveReceipt find(String key) {
        var receipt = receipts.findById(key.substring(0, 32));
        if (receipt != null && !key.equals(receipt.getRequestDigest()))
            throw new IllegalStateException("Workflow save request identity collision");
        return receipt;
    }

    private String requestDigest(String requestId) {
        if (requestId == null || !requestId.matches("[a-zA-Z0-9-]{16,80}"))
            throw new IllegalArgumentException("Invalid workflow save request identity");
        String user = CurrentUserContext.currentUser().orElseThrow().userId();
        String tenant = TenantContext.currentTenantId().orElse("");
        return digest(user.length() + ":" + user + tenant.length() + ":" + tenant + ":" + requestId);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
