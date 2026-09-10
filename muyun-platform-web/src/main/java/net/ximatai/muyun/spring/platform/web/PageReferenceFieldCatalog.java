package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Safe, source-neutral field directory used by the management-page composer. */
public record PageReferenceFieldCatalog(String moduleAlias, String path, List<Field> fields) {
    public PageReferenceFieldCatalog {
        path = path == null || path.isBlank() ? null : path.trim();
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public record Field(String id, String name, String label, FieldValueType valueType,
                        String referenceModuleAlias, String referenceCardinality,
                        boolean expandable, boolean readOnly, boolean systemManaged,
                        boolean formulaReadable, String formulaDisabledReason) {
        public Field(String id, String name, String label, FieldValueType valueType,
                     String referenceModuleAlias, String referenceCardinality,
                     boolean expandable, boolean readOnly, boolean systemManaged) {
            this(id, name, label, valueType, referenceModuleAlias, referenceCardinality, expandable, readOnly,
                    systemManaged, false, null);
        }
    }
}
