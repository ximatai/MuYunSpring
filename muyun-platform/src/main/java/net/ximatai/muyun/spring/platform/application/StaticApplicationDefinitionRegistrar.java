package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.spring.common.exception.DeclaredStateException;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reconciles static application declarations before their modules are registered. */
public class StaticApplicationDefinitionRegistrar implements PlatformBootstrapTask {
    private final ApplicationService applicationService;
    private final StaticApplicationDefinitionCatalog definitionCatalog;
    private final boolean disablesStaleSystemManagedApplications;

    public StaticApplicationDefinitionRegistrar(ApplicationService applicationService,
                                                List<StaticApplicationDefinition> definitions) {
        this(applicationService, new StaticApplicationDefinitionCatalog(definitions), false);
    }

    public StaticApplicationDefinitionRegistrar(ApplicationService applicationService,
                                                StaticApplicationDefinitionCatalog definitionCatalog) {
        this(applicationService, definitionCatalog,
                definitionCatalog != null && definitionCatalog.hasScanners());
    }

    public StaticApplicationDefinitionRegistrar(ApplicationService applicationService,
                                                StaticApplicationDefinitionCatalog definitionCatalog,
                                                boolean disablesStaleSystemManagedApplications) {
        this.applicationService = applicationService;
        this.definitionCatalog = definitionCatalog == null
                ? new StaticApplicationDefinitionCatalog(List.of())
                : definitionCatalog;
        this.disablesStaleSystemManagedApplications = disablesStaleSystemManagedApplications;
    }

    @Override
    public String name() {
        return "platform.static-applications";
    }

    @Override
    public int order() {
        return -10;
    }

    @Override
    public void run() {
        registerAll();
    }

    public void registerAll() {
        try (TenantContext.Scope ignored = TenantContext.system("register static applications")) {
            List<StaticApplicationDefinition> definitions = definitionCatalog.definitions();
            PlatformManagedMutationContext.runAsPlatformManaged(() -> {
                definitions.forEach(this::registerApplication);
                disableStaleSystemManagedApplications(definitions);
            });
        }
    }

    private void registerApplication(StaticApplicationDefinition definition) {
        Application application = applicationService.select(definition.alias());
        if (application == null) {
            application = new Application();
            application.setAlias(definition.alias());
            applyManagedFields(application, definition);
            applicationService.insert(application);
            return;
        }
        if (!Boolean.TRUE.equals(application.getSystemManaged())) {
            throw new DeclaredStateException(DiagnosticText.code("platform.static.diagnostic.static-application-conflicts-with-non-platform-managed-application",
                    "static application conflicts with non-platform-managed application: {alias}", Map.of("alias", String.valueOf(definition.alias()))));
        }
        applyManagedFields(application, definition);
        applicationService.update(application);
    }

    private void applyManagedFields(Application application, StaticApplicationDefinition definition) {
        application.setTitle(definition.title());
        application.setSortOrder(definition.sortOrder());
        application.setEnabled(Boolean.TRUE);
        application.setSystemManaged(Boolean.TRUE);
    }

    private void disableStaleSystemManagedApplications(List<StaticApplicationDefinition> definitions) {
        if (!disablesStaleSystemManagedApplications) {
            return;
        }
        Set<String> currentAliases = new HashSet<>();
        definitions.forEach(definition -> currentAliases.add(definition.alias()));
        applicationService.list(Criteria.of().eq("systemManaged", Boolean.TRUE)).stream()
                .map(Application::getAlias)
                .filter(alias -> !currentAliases.contains(alias))
                .forEach(applicationService::disable);
    }
}
