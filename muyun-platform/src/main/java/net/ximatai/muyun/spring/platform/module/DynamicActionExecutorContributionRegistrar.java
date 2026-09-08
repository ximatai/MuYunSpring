package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutor;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Registers code-owned dynamic actions after all executor beans have been assembled. */
@Service
public class DynamicActionExecutorContributionRegistrar implements PlatformBootstrapTask {
    private final ApplicationContext applicationContext;
    private final ModuleActionContributionRegistrar contributionRegistrar;
    private final PlatformModuleService moduleService;
    public DynamicActionExecutorContributionRegistrar(ApplicationContext applicationContext,
                                                      ModuleActionContributionRegistrar contributionRegistrar,
                                                      PlatformModuleService moduleService) {
        this.applicationContext = applicationContext;
        this.contributionRegistrar = contributionRegistrar;
        this.moduleService = moduleService;
    }

    @Override
    public void run() {
        List<DynamicActionExecutor> executors = List.copyOf(
                applicationContext.getBeansOfType(DynamicActionExecutor.class).values());
        executors.forEach(this::register);
        contributionRegistrar.disableMissingDynamicActionExecutorActions(executors.stream()
                .map(DynamicActionExecutor::executorKey)
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Override
    public int order() {
        return 20;
    }

    private void register(DynamicActionExecutor executor) {
        Set<PlatformDynamicActionContribution> declarations = AnnotationUtils.getRepeatableAnnotations(
                AopUtils.getTargetClass(executor), PlatformDynamicActionContribution.class);
        if (declarations.isEmpty()) {
            return;
        }
        String executorKey = executor.executorKey();
        List<ModuleActionContribution> contributions = new ArrayList<>();
        for (PlatformDynamicActionContribution declaration : declarations) {
            requireDynamicModule(declaration.moduleAlias());
            contributions.add(new ModuleActionContribution(
                    declaration.moduleAlias(), blankToNull(declaration.entityAlias()), declaration.actionCode(),
                    blankToNull(declaration.permissionActionCode()), declaration.title(), declaration.category(),
                    declaration.actionLevel(), declaration.accessMode(), declaration.actionAuth(), declaration.dataAuth(),
                    declaration.defaultGrantPolicy(), blankToNull(declaration.availableExpression()),
                    blankToNull(declaration.unavailableMessage()), EntityActionExecutorType.SERVICE, executorKey,
                    ModuleActionSourceType.CODE_EXTENSION, sourceId(executorKey, declaration.moduleAlias()), null,
                    ModuleActionBindingType.DYNAMIC_ACTION_EXECUTOR, executorKey, executorKey, true, executor.definition().formSupported()
            ));
        }
        contributionRegistrar.registerAll(contributions);
    }

    private String sourceId(String executorKey, String moduleAlias) {
        return sha256(executorKey + ":" + moduleAlias);
    }

    private void requireDynamicModule(String moduleAlias) {
        PlatformModule module = moduleService.resolveVisibleModule(moduleAlias);
        if (module == null || module.getModuleKind() != ModuleKind.DYNAMIC) {
            throw new PlatformException("Dynamic action executor contribution requires a dynamic module: " + moduleAlias);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                encoded.append(String.format("%02x", valueByte));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
