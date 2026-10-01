package net.ximatai.muyun.spring.platform.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Reliable submission of the standard child operation; does not own a second metadata candidate. */
@Service
public class ModuleChildMetadataCreationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ModuleChildMetadataCreationReceiptDao receipts;
    private final ModuleMetadataOrchestrationService orchestration;
    private final ModuleMetadataRelationService relations;
    private final MetadataService metadata;
    private final ActionExecutionPolicyService permissions;

    public ModuleChildMetadataCreationService(ModuleChildMetadataCreationReceiptDao receipts,
            ModuleMetadataOrchestrationService orchestration, ModuleMetadataRelationService relations,
            MetadataService metadata, ActionExecutionPolicyService permissions) {
        this.receipts = Objects.requireNonNull(receipts);
        this.orchestration = Objects.requireNonNull(orchestration);
        this.relations = Objects.requireNonNull(relations);
        this.metadata = Objects.requireNonNull(metadata);
        this.permissions = Objects.requireNonNull(permissions);
    }

    public record Receipt(String metadataId, String relationId) {}

    @Transactional
    public ModuleMainMetadataCreationResult create(String moduleAlias, String parentRelationId,
            ModuleChildMetadataCreateCommand command) {
        Objects.requireNonNull(command, "child creation command");
        authorize(moduleAlias, parentRelationId);
        String key = requestDigest(command.requestId());
        String payload = digest(Arrays.asList(moduleAlias, parentRelationId, command.alias(), command.title(),
                command.schemaName(), command.tableName()));
        PlatformAbilityRuntime.lockMutationPartition("platform.child-metadata-creation", key);
        var prior = find(key);
        if (prior != null) {
            if (!payload.equals(prior.getPayloadDigest()))
                throw new IllegalArgumentException("明细创建请求内容已变化，请重新审阅并确认");
            var child = metadata.select(prior.getMetadataId());
            var relation = relations.select(prior.getRelationId());
            if (child == null || relation == null)
                throw new IllegalArgumentException("明细曾经创建成功，但当前已不存在；请核对现行配置，不要重复提交旧请求");
            return new ModuleMainMetadataCreationResult(child, relation);
        }
        var result = orchestration.createChildMetadata(moduleAlias, parentRelationId, command);
        var receipt = new ModuleChildMetadataCreationReceipt();
        receipt.setId(key.substring(0, 32)); receipt.setRequestDigest(key); receipt.setPayloadDigest(payload);
        receipt.setModuleAlias(moduleAlias); receipt.setParentRelationId(parentRelationId);
        receipt.setMetadataId(result.metadata().getId()); receipt.setRelationId(result.relation().getId());
        receipt.setTenantId(TenantContext.currentTenantId().orElse(null));
        EntityLifecycle.prepareInsert(receipt, Instant.now());
        receipts.insert(receipt);
        return result;
    }

    public Receipt lookup(String moduleAlias, String parentRelationId, String requestId) {
        authorize(moduleAlias, parentRelationId);
        var receipt = find(requestDigest(requestId));
        if (receipt == null || !moduleAlias.equals(receipt.getModuleAlias())
                || !parentRelationId.equals(receipt.getParentRelationId())) return null;
        // These identifiers prove the original commit, not that the configuration is still active.
        return new Receipt(receipt.getMetadataId(), receipt.getRelationId());
    }

    private void authorize(String moduleAlias, String parentRelationId) {
        PlatformNameRules.requireModuleAlias(moduleAlias);
        permissions.requireAuthorized(ActionExecutionContext.ofActionCode(ModuleMetadataRelationService.MODULE_ALIAS,
                "createChildMetadata", Set.of(), CurrentUserContext.currentUser()));
        var parent = relations.select(parentRelationId);
        if (parent == null || !moduleAlias.equals(parent.getModuleAlias()))
            throw new IllegalArgumentException("父元数据不存在或不属于当前模块");
    }

    private ModuleChildMetadataCreationReceipt find(String key) {
        var receipt = receipts.findById(key.substring(0, 32));
        if (receipt != null && !key.equals(receipt.getRequestDigest()))
            throw new IllegalStateException("Child creation request identity collision");
        return receipt;
    }

    private static String requestDigest(String requestId) {
        if (requestId == null || !requestId.matches("[a-zA-Z0-9-]{16,80}"))
            throw new IllegalArgumentException("明细创建请求标识无效");
        var user = CurrentUserContext.currentUser().orElseThrow(() -> new IllegalStateException("请先登录"));
        return digest(Arrays.asList(user.userId(), user.tenantId(), TenantContext.currentTenantId().orElse(null), requestId));
    }

    private static String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
