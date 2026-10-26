package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.exception.DeclaredStateException;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates invariants shared by every static-module registration source. */
public final class StaticModuleRegistrationValidator {
    private StaticModuleRegistrationValidator() {
    }

    public static void validate(List<? extends StaticModuleRegistration> definitions) {
        Set<String> modules = new HashSet<>();
        for (StaticModuleRegistration definition : definitions) {
            if (!modules.add(definition.moduleAlias())) {
                throw new DeclaredStateException(DiagnosticText.code("platform.static.diagnostic.duplicate-static-module-definition",
                    "duplicate static module definition: {moduleAlias}", Map.of("moduleAlias", String.valueOf(definition.moduleAlias()))));
            }
            Set<String> actions = new HashSet<>();
            for (StaticModuleActionDefinition action : definition.actions()) {
                if (!actions.add(action.actionCode())) {
                    throw new DeclaredStateException(DiagnosticText.code("platform.static.diagnostic.duplicate-static-module-action-definition",
                    "duplicate static module action definition: {moduleAlias}.{actionCode}", Map.of("moduleAlias", String.valueOf(definition.moduleAlias()), "actionCode", String.valueOf(action.actionCode()))));
                }
            }
        }
    }
}
