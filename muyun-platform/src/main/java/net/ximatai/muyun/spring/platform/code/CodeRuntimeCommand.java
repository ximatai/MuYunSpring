package net.ximatai.muyun.spring.platform.code;

import java.time.LocalDateTime;
import java.util.Map;

public record CodeRuntimeCommand(
        String moduleAlias,
        String entityAlias,
        String moduleMetadataFieldId,
        String metadataFieldId,
        String fieldName,
        String organizationId,
        LocalDateTime at,
        Map<String, Object> context,
        String sourceRecordId,
        CodeValueUniquenessChecker uniquenessChecker
) {
    public CodeRuntimeCommand {
        moduleAlias = net.ximatai.muyun.spring.common.util.PlatformNameRules.requireModuleAlias(moduleAlias);
        entityAlias = net.ximatai.muyun.spring.common.util.PlatformNameRules.requireIdentifier(entityAlias, "entityAlias");
        fieldName = net.ximatai.muyun.spring.common.util.PlatformNameRules.requireFieldName(fieldName, "fieldName");
        context = context == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(context));
    }

    public CodeRuntimeCommand(String moduleAlias,
                              String entityAlias,
                              String metadataFieldId,
                              String fieldName,
                              String organizationId,
                              LocalDateTime at,
                              Map<String, Object> context,
                              String sourceRecordId,
                              CodeValueUniquenessChecker uniquenessChecker) {
        this(moduleAlias, entityAlias, null, metadataFieldId, fieldName, organizationId, at, context, sourceRecordId,
                uniquenessChecker);
    }

    public static Builder builder(String moduleAlias, String entityAlias, String fieldName) {
        return new Builder(moduleAlias, entityAlias, fieldName);
    }

    public GenerateCodeCommand toGenerateCommand() {
        return new GenerateCodeCommand(
                moduleAlias,
                entityAlias,
                moduleMetadataFieldId,
                metadataFieldId,
                fieldName,
                organizationId,
                at,
                context,
                uniquenessChecker
        );
    }

    public CodeRuntimeCommand withUniquenessChecker(CodeValueUniquenessChecker checker) {
        return new CodeRuntimeCommand(
                moduleAlias,
                entityAlias,
                moduleMetadataFieldId,
                metadataFieldId,
                fieldName,
                organizationId,
                at,
                context,
                sourceRecordId,
                checker
        );
    }

    public static final class Builder {
        private final String moduleAlias;
        private final String entityAlias;
        private final String fieldName;
        private String moduleMetadataFieldId;
        private String metadataFieldId;
        private String organizationId;
        private LocalDateTime at;
        private Map<String, Object> context;
        private String sourceRecordId;
        private CodeValueUniquenessChecker uniquenessChecker;

        private Builder(String moduleAlias, String entityAlias, String fieldName) {
            this.moduleAlias = moduleAlias;
            this.entityAlias = entityAlias;
            this.fieldName = fieldName;
        }

        public Builder moduleMetadataFieldId(String moduleMetadataFieldId) {
            this.moduleMetadataFieldId = moduleMetadataFieldId;
            return this;
        }

        public Builder metadataFieldId(String metadataFieldId) {
            this.metadataFieldId = metadataFieldId;
            return this;
        }

        public Builder organizationId(String organizationId) {
            this.organizationId = organizationId;
            return this;
        }

        public Builder at(LocalDateTime at) {
            this.at = at;
            return this;
        }

        public Builder context(Map<String, Object> context) {
            this.context = context;
            return this;
        }

        public Builder sourceRecordId(String sourceRecordId) {
            this.sourceRecordId = sourceRecordId;
            return this;
        }

        public Builder uniquenessChecker(CodeValueUniquenessChecker uniquenessChecker) {
            this.uniquenessChecker = uniquenessChecker;
            return this;
        }

        public CodeRuntimeCommand build() {
            return new CodeRuntimeCommand(
                    moduleAlias,
                    entityAlias,
                    moduleMetadataFieldId,
                    metadataFieldId,
                    fieldName,
                    organizationId,
                    at,
                    context,
                    sourceRecordId,
                    uniquenessChecker
            );
        }
    }
}
