package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Read-only compatibility for historical initialization receipts. New creation uses standard governance. */
@Service
public class ApplicationConstructionInitializationService {
    private final ApplicationConstructionPlanService plans;
    private final ApplicationConstructionInitializationDao receipts;
    private final DynamicRuntimeActivationService activation;
    private final ActionExecutionPolicyService permissions;

    public ApplicationConstructionInitializationService(ApplicationConstructionPlanService plans,
            ApplicationConstructionInitializationDao receipts, DynamicRuntimeActivationService activation,
            ActionExecutionPolicyService permissions) {
        this.plans = Objects.requireNonNull(plans);
        this.receipts = Objects.requireNonNull(receipts);
        this.activation = Objects.requireNonNull(activation);
        this.permissions = Objects.requireNonNull(permissions);
    }

    public record Result(ApplicationConstructionPlanService.Initialization receipt,
                         DynamicRuntimeActivationService.Status runtime) {}

    public Result status(String planId, String objectKey) {
        requireOperator();
        plans.read(planId);
        var receipt = receipts.findById(receiptId(planId, objectKey));
        return receipt == null ? null : result(receipt);
    }
    private Result result(ApplicationConstructionInitialization receipt) {
        try (var ignored = TenantContext.system("construction initialization result")) {
            return new Result(ApplicationConstructionPlanService.initialization(receipt), activation.status(receipt.getModuleAlias()));
        }
    }
    private void requireOperator() {
        var user = CurrentUserContext.currentUser().orElseThrow(() -> new PlatformAccessDeniedException("请先登录"));
        if (!user.system()) throw new PlatformAccessDeniedException("模块初始化目前要求系统配置身份");
        requireAction(PlatformModuleService.MODULE_ALIAS, PlatformAction.CREATE.code());
        requireAction(ModuleMetadataRelationService.MODULE_ALIAS, "createMainMetadata");
    }
    private void requireAction(String module, String action) {
        permissions.requireAuthorized(ActionExecutionContext.ofActionCode(module, action, Set.of(), CurrentUserContext.currentUser()));
    }
    private static String receiptId(String planId, String objectKey) { return digest(planId + ":" + objectKey).substring(0, 32); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
