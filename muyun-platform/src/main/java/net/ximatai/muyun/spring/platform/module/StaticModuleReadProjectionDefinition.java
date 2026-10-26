package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.exception.DeclarationValidationException;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.ability.reference.ReferencePath;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjection;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;

public record StaticModuleReadProjectionDefinition(String path,
                                                   ReferencePath referencePath,
                                                   String outputField,
                                                   ModuleReadProjection.ProjectionType projectionType,
                                                   boolean filterable,
                                                   boolean sortable) {
    public StaticModuleReadProjectionDefinition(String path, String outputField) {
        this(path, null, outputField, ModuleReadProjection.ProjectionType.FIELD, false, true);
    }

    public StaticModuleReadProjectionDefinition(ReferencePath referencePath, String outputField) {
        this(null, referencePath, outputField, ModuleReadProjection.ProjectionType.FIELD, false, true);
    }

    public StaticModuleReadProjectionDefinition {
        if ((path == null || path.isBlank()) && referencePath == null) {
            throw new DeclarationValidationException(DiagnosticText.code("platform.static.diagnostic.static-module-read-projection-path-must-not-be-blank",
                    "static module read projection path must not be blank"));
        }
        path = path == null || path.isBlank() ? null : path.trim();
        projectionType = projectionType == null ? ModuleReadProjection.ProjectionType.FIELD : projectionType;
        if (referencePath == null && !path.contains(".")) {
            throw new DeclarationValidationException(DiagnosticText.code("platform.static.diagnostic.static-module-read-projection-path-requires-reference-field-path",
                    "static module read projection path requires reference field path: {path}", Map.of("path", String.valueOf(path))));
        }
        outputField = outputField == null || outputField.isBlank()
                ? defaultOutputField(path, referencePath)
                : outputField.trim();
        outputField = PlatformNameRules.requireFieldName(outputField, "readProjectionOutputField");
    }

    public StaticModuleReadProjectionDefinition(String path,
                                                String outputField,
                                                boolean filterable,
                                                boolean sortable) {
        this(path, null, outputField, ModuleReadProjection.ProjectionType.FIELD, filterable, sortable);
    }

    private static String defaultOutputField(String path, ReferencePath referencePath) {
        if (referencePath != null) {
            return referencePath.targetField().fieldName();
        }
        int index = path.lastIndexOf('.');
        return index < 0 ? path : path.substring(index + 1);
    }
}
