package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.exception.DeclaredStateException;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.platform.application.StaticApplicationDefinitionCatalog;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;

import java.util.List;

/** Validates the complete static application/module declaration graph before reconciliation writes. */
public class StaticDeclarationPreflightTask implements PlatformBootstrapTask {
    private final StaticApplicationDefinitionCatalog applicationCatalog;
    private final StaticModuleRegistrationSource moduleSource;

    public StaticDeclarationPreflightTask(StaticApplicationDefinitionCatalog applicationCatalog,
                                          StaticModuleRegistrationSource moduleSource) {
        this.applicationCatalog = applicationCatalog;
        this.moduleSource = moduleSource;
    }

    @Override
    public String name() {
        return "platform.static-declaration-preflight";
    }

    @Override
    public int order() {
        return -20;
    }

    @Override
    public void run() {
        validateApplicationOwnership(moduleSource.definitions(), applicationCatalog);
    }

    static void validateApplicationOwnership(List<? extends StaticModuleRegistration> definitions,
                                             StaticApplicationDefinitionCatalog applicationCatalog) {
        if (applicationCatalog == null) {
            return;
        }
        for (StaticModuleRegistration definition : definitions) {
            if (applicationCatalog.find(definition.applicationAlias()).isEmpty()) {
                throw new DeclaredStateException(DiagnosticText.code("platform.static.diagnostic.static-references-undeclared-static-application",
                    "static references undeclared static application: {moduleAlias} -> {applicationAlias}", Map.of("moduleAlias", String.valueOf(definition.moduleAlias()), "applicationAlias", String.valueOf(definition.applicationAlias()))));
            }
        }
    }
}
