package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Safe, source-neutral field directory used by the management-page composer. */
public record PageReferenceFieldCatalog(String moduleAlias, String path, Integer dictionaryRadioMaxOptions, List<Field> fields) {
    public static final int DEFAULT_DICTIONARY_RADIO_MAX_OPTIONS = 12;

    public PageReferenceFieldCatalog {
        path = path == null || path.isBlank() ? null : path.trim();
        if (dictionaryRadioMaxOptions != null && dictionaryRadioMaxOptions <= 0) {
            dictionaryRadioMaxOptions = DEFAULT_DICTIONARY_RADIO_MAX_OPTIONS;
        }
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public PageReferenceFieldCatalog(String moduleAlias, String path, List<Field> fields) {
        this(moduleAlias, path, DEFAULT_DICTIONARY_RADIO_MAX_OPTIONS, fields);
    }

    public record Field(String id, String name, String label, FieldValueType valueType,
                        String referenceModuleAlias, String referenceCardinality,
                        boolean expandable, boolean readOnly, boolean systemManaged,
                        boolean formulaReadable, String formulaDisabledReason,
                        String optionSourceType, String optionSelectionMode) {
        public Field(String id, String name, String label, FieldValueType valueType,
                     String referenceModuleAlias, String referenceCardinality,
                     boolean expandable, boolean readOnly, boolean systemManaged) {
            this(id, name, label, valueType, referenceModuleAlias, referenceCardinality, expandable, readOnly,
                    systemManaged, false, null, null, null);
        }
    }
}
