package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicQuerySchemas;

import java.util.List;

/** Online metadata owns configured query labels; standalone dynamic declarations remain source neutral. */
public final class MetadataQuerySchemas {
    private MetadataQuerySchemas() { }

    public static QuerySchema from(String moduleAlias, DynamicEntityDescriptor entity, List<String> quickSearchFields) {
        return from(moduleAlias, entity, quickSearchFields, List.of());
    }

    public static QuerySchema from(String moduleAlias, DynamicEntityDescriptor entity, List<String> quickSearchFields,
                                   List<String> externalCriteriaKeys) {
        var schema = DynamicQuerySchemas.from(moduleAlias, entity, quickSearchFields, externalCriteriaKeys);
        return new QuerySchema(schema.scopeName(), schema.entityAlias(),
                new QuerySchema.QuickSearch(schema.quickSearch().enabled(), schema.quickSearch().fields(),
                        titles(moduleAlias, entity.entityAlias(), schema.quickSearch().fieldSchemas())),
                titles(moduleAlias, entity.entityAlias(), schema.fields()), schema.externalCriteria(),
                schema.defaultSorts(), schema.criteriaComposition());
    }

    private static List<QuerySchema.Field> titles(String moduleAlias, String entityAlias, List<QuerySchema.Field> fields) {
        return fields.stream().map(field -> new QuerySchema.Field(field.name(), field.title(), field.valueType(),
                field.operators(), field.defaultOperator(), field.quickSearch(), field.sortable(), field.optionBinding(),
                field.selectionMode(), field.optionTitleField(), field.reference(), field.persistentControl(),
                field.titleSource() != null ? field.titleSource() : field.title() == null || field.title().isBlank() ? null :
                        ConfigurationTextSources.metadataFieldTitle(moduleAlias, entityAlias, field.name(), field.title()))).toList();
    }
}
