package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class LowCodeModulePackageExchangeService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final LowCodeModuleConfigVersionService versionService;
    private final LowCodeModuleHealthService healthService;
    private final LowCodePackageDependencyDiagnostics dependencyDiagnostics;

    public LowCodeModulePackageExchangeService(LowCodeModuleConfigVersionService versionService,
                                               LowCodeModuleHealthService healthService) {
        this(versionService, healthService, List.of());
    }

    @Autowired
    public LowCodeModulePackageExchangeService(LowCodeModuleConfigVersionService versionService,
                                               LowCodeModuleHealthService healthService,
                                               List<LowCodePackageDependencyResolver> dependencyResolvers) {
        this.versionService = versionService;
        this.healthService = healthService;
        this.dependencyDiagnostics = new LowCodePackageDependencyDiagnostics(dependencyResolvers);
    }

    public String exportCurrentPackage(String moduleAlias) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        LowCodeModuleConfigVersion current = versionService.currentVersion(validModuleAlias);
        if (current == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.low-code-module-package-exchange.current-low-code-config-version-not-found",
                    "current low code config version not found: {value1}"), Map.of("value1", String.valueOf(validModuleAlias)));
        }
        return current.getPackageSnapshotText();
    }

    public String exportVersionPackage(String versionId) {
        LowCodeModuleConfigVersion version = versionService.select(versionId);
        if (version == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.low-code-module-package-exchange.low-code-config-version-not-found",
                    "low code config version not found: {value1}"), Map.of("value1", String.valueOf(versionId)));
        }
        return version.getPackageSnapshotText();
    }

    public LowCodeModulePackage parsePackage(String packageJson) {
        if (packageJson == null || packageJson.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.low-code-module-package-exchange.low-code-module-package-json-must-not-be-blank",
                    "low code module package json must not be blank"));
        }
        try {
            return OBJECT_MAPPER.readValue(packageJson, LowCodeModulePackage.class);
        } catch (JsonProcessingException exception) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.low-code-module-package-exchange.low-code-module-package-json-cannot-be-decoded",
                    "low code module package json cannot be decoded"));
        }
    }

    public LowCodePackageDryRunResult dryRunImport(String packageJson) {
        return dryRunImport(parsePackage(packageJson));
    }

    public LowCodePackageDryRunResult dryRunImport(LowCodeModulePackage modulePackage) {
        LowCodeConfigHealthReport healthReport = healthService.check(LowCodeModuleHealthContext.ofPackage(modulePackage));
        List<LowCodePackageImportConflict> conflicts = conflicts(modulePackage);
        return new LowCodePackageDryRunResult(modulePackage, null, healthReport, conflicts);
    }

    private List<LowCodePackageImportConflict> conflicts(LowCodeModulePackage modulePackage) {
        List<LowCodePackageImportConflict> conflicts = new ArrayList<>();
        LowCodeModuleConfigVersion current = versionService.currentVersion(modulePackage.moduleAlias());
        if (modulePackage.mode() == LowCodePackageMode.PAGE_ONLY && current == null) {
            conflicts.add(new LowCodePackageImportConflict(LowCodePackageConflictType.PAGE_ONLY_REQUIRES_EXISTING_MODULE,
                LowCodePackageConflictSeverity.ERROR,
                modulePackage.moduleAlias(),
                null,
                DiagnosticText.code("platform.config.diagnostic.page-only-package-requires-an-existing-module-config-version",
                    "PAGE_ONLY package requires an existing module config version")));
        }
        if (modulePackage.mode() == LowCodePackageMode.MODULE_FULL && current != null) {
            conflicts.add(new LowCodePackageImportConflict(LowCodePackageConflictType.MODULE_ALREADY_HAS_VERSION,
                LowCodePackageConflictSeverity.WARN,
                modulePackage.moduleAlias(),
                current.getId(),
                DiagnosticText.code("platform.config.diagnostic.module-full-package-will-be-imported-as-draft-over-an-existing-module",
                    "MODULE_FULL package will be imported as draft over an existing module")));
        }
        if (modulePackage.mode() == LowCodePackageMode.TEMPLATE && current != null) {
            conflicts.add(new LowCodePackageImportConflict(LowCodePackageConflictType.TEMPLATE_TARGET_MODULE_EXISTS,
                LowCodePackageConflictSeverity.ERROR,
                modulePackage.moduleAlias(),
                current.getId(),
                DiagnosticText.code("platform.config.diagnostic.template-package-requires-a-new-target-module-alias",
                    "TEMPLATE package requires a new target module alias")));
        }
        conflicts.addAll(dependencyConflicts(modulePackage));
        return conflicts;
    }

    private List<LowCodePackageImportConflict> dependencyConflicts(LowCodeModulePackage modulePackage) {
        List<LowCodePackageImportConflict> conflicts = new ArrayList<>();
        for (LowCodePackageDependencyDiagnostic diagnostic : dependencyDiagnostics.diagnose(modulePackage)) {
            conflicts.add(dependencyConflict(modulePackage, diagnostic));
        }
        return conflicts;
    }

    private LowCodePackageImportConflict dependencyConflict(LowCodeModulePackage modulePackage,
                                                           LowCodePackageDependencyDiagnostic diagnostic) {
        return new LowCodePackageImportConflict(diagnostic.conflictType(),
                diagnostic.blocking() ? LowCodePackageConflictSeverity.ERROR : LowCodePackageConflictSeverity.WARN,
                modulePackage.moduleAlias(),
                diagnostic.targetId(),
                diagnostic.message(),
                diagnostic.textSource(),
                diagnostic.messageArgs());
    }
}
