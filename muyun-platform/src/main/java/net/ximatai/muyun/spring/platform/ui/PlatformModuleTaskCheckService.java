package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;
import net.ximatai.muyun.spring.platform.task.ModuleCompletionCheckService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class PlatformModuleTaskCheckService {
    private final PlatformPageConfigSnapshotService snapshotService;
    private final ModuleCompletionCheckService completionChecks;
    private final PlatformModuleTaskDefinitionRegistry taskDefinitionRegistry;

    public PlatformModuleTaskCheckService(PlatformPageConfigSnapshotService snapshotService,
                                          ModuleCompletionCheckService completionChecks,
                                          PlatformModuleTaskDefinitionRegistry taskDefinitionRegistry) {
        this.snapshotService = Objects.requireNonNull(snapshotService);
        this.completionChecks = Objects.requireNonNull(completionChecks);
        this.taskDefinitionRegistry = Objects.requireNonNull(taskDefinitionRegistry);
    }

    public PlatformModuleTaskCheckResult check(String moduleAlias, String recordId, String uiConfigId) {
        if (recordId == null || recordId.isBlank()) {
            throw new PlatformException("Module task check requires record id");
        }
        PlatformPageConfigSnapshot snapshot = snapshotService.snapshot(moduleAlias);
        List<PlatformModuleTaskDefinition> definitions = taskDefinitionRegistry.listEnabled(moduleAlias);
        List<PlatformTaskSource> definitionSources = definitions.stream()
                .map(definition -> new PlatformTaskSource(definition.toBlock(), definition.guides()))
                .toList();
        Set<String> definitionKeys = definitions.stream()
                .map(PlatformModuleTaskDefinition::taskCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<PlatformTaskSource> uiSources = snapshot.uiConfigs().stream()
                .filter(config -> uiConfigId == null || uiConfigId.isBlank()
                        || Objects.equals(config.getId(), uiConfigId))
                .flatMap(config -> PlatformTaskBlockLayoutResolver.resolve(config).stream())
                .filter(block -> !definitionKeys.contains(block.key()))
                .map(block -> new PlatformTaskSource(block, List.of()))
                .toList();
        if (hasText(uiConfigId)) {
            boolean configExists = snapshot.uiConfigs().stream()
                    .anyMatch(config -> Objects.equals(config.getId(), uiConfigId));
            if (!configExists) {
                throw new PlatformException("UI config is not published in module snapshot: " + uiConfigId);
            }
        }
        List<PlatformTaskSource> sources = new java.util.ArrayList<>();
        sources.addAll(definitionSources);
        sources.addAll(uiSources);
        return PlatformModuleTaskCheckResult.of(sources.stream()
                .map(source -> checkBlock(snapshot, recordId, source))
                .toList());
    }

    public List<PlatformModuleTaskDefinition> definitions(String moduleAlias) {
        return taskDefinitionRegistry.listEnabled(moduleAlias);
    }

    private PlatformModuleTaskStatus checkBlock(PlatformPageConfigSnapshot snapshot,
                                                String recordId,
                                                PlatformTaskSource source) {
        PlatformTaskBlock block = source.block();
        List<PlatformModuleTaskCheckDetail> checks = block.checks().stream()
                .map(check -> checkOne(snapshot, recordId, check))
                .toList();
        if (checks.isEmpty() || checks.stream().allMatch(check -> check.passed() == null)) {
            return status(source, PlatformTaskCompletionStatus.UNKNOWN, null, null, null, checks,
                    "manual task has no backend check");
        }
        boolean hasFailed = checks.stream().anyMatch(check -> Boolean.FALSE.equals(check.passed()));
        if (hasFailed) {
            return status(source, PlatformTaskCompletionStatus.PENDING, false, matchedCount(checks),
                    expectedCount(checks), checks, null);
        }
        boolean hasUnknown = checks.stream().anyMatch(check -> check.passed() == null);
        return status(source, hasUnknown ? PlatformTaskCompletionStatus.UNKNOWN : PlatformTaskCompletionStatus.COMPLETE,
                hasUnknown ? null : true, matchedCount(checks), expectedCount(checks), checks, null);
    }

    private PlatformModuleTaskCheckDetail checkOne(PlatformPageConfigSnapshot snapshot,
                                                   String recordId,
                                                   PlatformTaskCheckBlock check) {
        if (check.checkType() == PlatformTaskCheckType.QUERY_TEMPLATE) {
            PlatformQueryTemplate template = snapshot.queryTemplates().stream()
                    .filter(item -> Objects.equals(item.getId(), check.queryTemplateId()))
                    .findFirst()
                    .orElseThrow(() -> new PlatformException("Task query template is not published in module snapshot: "
                            + check.queryTemplateId()));
            return completionChecks.check(snapshot.moduleAlias(), recordId, check, template);
        }
        return completionChecks.check(snapshot.moduleAlias(), recordId, check);
    }

    private PlatformModuleTaskStatus status(PlatformTaskSource source,
                                            PlatformTaskCompletionStatus status,
                                            Boolean passed,
                                            Long matchedCount,
                                            Integer expectedCount,
                                            List<PlatformModuleTaskCheckDetail> checks,
                                            String message) {
        PlatformTaskBlock block = source.block();
        return new PlatformModuleTaskStatus(block.key(), block.title(), block.checkType(), status, passed,
                matchedCount, expectedCount, checks, source.guides(), block.diagnosticPath(), message);
    }

    private Long matchedCount(List<PlatformModuleTaskCheckDetail> checks) {
        return checks.stream()
                .map(PlatformModuleTaskCheckDetail::actualCount)
                .filter(Objects::nonNull)
                .reduce(0L, Long::sum);
    }

    private Integer expectedCount(List<PlatformModuleTaskCheckDetail> checks) {
        return checks.stream()
                .map(PlatformModuleTaskCheckDetail::expectedCount)
                .filter(Objects::nonNull)
                .reduce(0, Integer::sum);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PlatformTaskSource(PlatformTaskBlock block,
                                      List<PlatformModuleTaskGuideDefinition> guides) {
        private PlatformTaskSource {
            guides = guides == null ? List.of() : List.copyOf(guides);
        }
    }
}
