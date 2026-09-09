package net.ximatai.muyun.spring.platform.exchange.template;

import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.option.OptionItem;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldCompanionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.FieldCompanionRules;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelColumnPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelSheetPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelValueType;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookMeta;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DynamicExchangeTemplatePlanBuilder {
    private final OptionSourceRegistry optionSourceRegistry;
    private final DynamicReferenceDropdownResolver referenceDropdownResolver;

    public DynamicExchangeTemplatePlanBuilder() {
        this(null, DynamicReferenceDropdownResolver.NONE);
    }

    public DynamicExchangeTemplatePlanBuilder(OptionSourceRegistry optionSourceRegistry) {
        this(optionSourceRegistry, DynamicReferenceDropdownResolver.NONE);
    }

    public DynamicExchangeTemplatePlanBuilder(OptionSourceRegistry optionSourceRegistry,
                                              DynamicReferenceDropdownResolver referenceDropdownResolver) {
        this.optionSourceRegistry = optionSourceRegistry;
        this.referenceDropdownResolver = referenceDropdownResolver == null
                ? DynamicReferenceDropdownResolver.NONE
                : referenceDropdownResolver;
    }

    public ExcelWorkbookPlan build(DynamicModuleDescriptor descriptor) {
        return build(descriptor, DynamicExchangeTemplateOptions.DEFAULT);
    }

    public ExcelWorkbookPlan build(DynamicModuleDescriptor descriptor, DynamicExchangeTemplateOptions options) {
        DynamicExchangeTemplateOptions effectiveOptions = options == null
                ? DynamicExchangeTemplateOptions.DEFAULT
                : options;
        validateModule(descriptor);
        Map<String, DynamicEntityDescriptor> entitiesByAlias = entitiesByAlias(descriptor);
        DynamicEntityDescriptor mainEntity = requireEntity(entitiesByAlias, descriptor.mainEntityAlias(),
                "dynamic exchange template main entity not found");

        List<ExcelSheetPlan> sheets = new ArrayList<>();
        sheets.add(buildSheet(mainEntity, true, true, effectiveOptions));
        Set<String> childEntityAliases = new LinkedHashSet<>();
        for (DynamicRelationDescriptor relation : descriptor.relations()) {
            if (!descriptor.mainEntityAlias().equals(relation.parentEntityAlias())) {
                continue;
            }
            if (!childEntityAliases.add(relation.childEntityAlias())) {
                continue;
            }
            DynamicEntityDescriptor childEntity = requireEntity(entitiesByAlias, relation.childEntityAlias(),
                    "dynamic exchange template child entity not found");
            sheets.add(buildSheet(childEntity, false, false, effectiveOptions));
        }

        return new ExcelWorkbookPlan(new ExcelWorkbookMeta(
                ExcelExchangeProtocol.PROTOCOL_VERSION,
                descriptor.moduleAlias(),
                null,
                null,
                null
        ), sheets);
    }

    private void validateModule(DynamicModuleDescriptor descriptor) {
        if (descriptor == null) {
            throw new PlatformException("dynamic exchange template requires module descriptor");
        }
        if (isBlank(descriptor.moduleAlias())) {
            throw new PlatformException("dynamic exchange template requires moduleAlias");
        }
        if (isBlank(descriptor.mainEntityAlias())) {
            throw new PlatformException("dynamic exchange template requires mainEntityAlias");
        }
    }

    private Map<String, DynamicEntityDescriptor> entitiesByAlias(DynamicModuleDescriptor descriptor) {
        if (descriptor.entities().isEmpty()) {
            throw new PlatformException("dynamic exchange template requires module entities");
        }
        return descriptor.entities().stream()
                .collect(Collectors.toMap(
                        DynamicEntityDescriptor::entityAlias,
                        Function.identity(),
                        (left, right) -> {
                            throw new PlatformException("dynamic exchange template entity duplicated: " + left.entityAlias());
                        },
                        LinkedHashMap::new
                ));
    }

    private DynamicEntityDescriptor requireEntity(Map<String, DynamicEntityDescriptor> entitiesByAlias,
                                                  String entityAlias,
                                                  String message) {
        DynamicEntityDescriptor entity = entitiesByAlias.get(entityAlias);
        if (entity == null) {
            throw new PlatformException(message + ": " + entityAlias);
        }
        return entity;
    }

    private ExcelSheetPlan buildSheet(DynamicEntityDescriptor entity,
                                      boolean main,
                                      boolean requireBusinessFields,
                                      DynamicExchangeTemplateOptions options) {
        List<ExcelColumnPlan> columns = buildColumns(entity, options);
        if (requireBusinessFields && columns.size() == 1) {
            throw new PlatformException("dynamic exchange template main sheet requires business fields: "
                    + entity.entityAlias());
        }
        return new ExcelSheetPlan(sheetNameOf(entity), entity.entityAlias(), main, columns);
    }

    private List<ExcelColumnPlan> buildColumns(DynamicEntityDescriptor entity, DynamicExchangeTemplateOptions options) {
        Set<String> hiddenCompanionFieldNames = hiddenCompanionFieldNames(entity);
        List<ExcelColumnPlan> columns = new ArrayList<>();
        columns.add(new ExcelColumnPlan(
                ExcelExchangeProtocol.RELATE_ID_FIELD,
                ExcelExchangeProtocol.RELATE_ID_TITLE,
                false,
                ExcelValueType.TEXT,
                List.of()
        ));
        for (DynamicFieldDescriptor field : entity.fields()) {
            if (field == null || isBlank(field.fieldName()) || hiddenCompanionFieldNames.contains(field.fieldName())
                    || PlatformFieldPolicy.isAudit(field.fieldName())) {
                continue;
            }
            columns.add(new ExcelColumnPlan(
                    field.fieldName(),
                    titleOf(field),
                    field.required(),
                    valueTypeOf(field.type()),
                    dropdownOptions(entity.entityAlias(), field, options)
            ));
        }
        return columns;
    }

    private Set<String> hiddenCompanionFieldNames(DynamicEntityDescriptor entity) {
        Set<String> fieldNames = new LinkedHashSet<>();
        for (DynamicFieldDescriptor field : entity.fields()) {
            if (field == null || field.companions() == null) {
                continue;
            }
            for (DynamicFieldCompanionDescriptor companion : field.companions()) {
                if (companion != null && !isBlank(companion.fieldName())) {
                    if (field.type() == FieldType.ZONED_TIMESTAMP
                            && FieldCompanionRules.zonedTimestampTimeZoneFieldName(field.fieldName())
                            .equals(companion.fieldName())) {
                        continue;
                    }
                    fieldNames.add(companion.fieldName());
                }
            }
        }
        return fieldNames;
    }

    private String titleOf(DynamicFieldDescriptor field) {
        return isBlank(field.title()) ? field.fieldName() : field.title();
    }

    private String sheetNameOf(DynamicEntityDescriptor entity) {
        return isBlank(entity.title()) ? entity.entityAlias() : entity.title();
    }

    private ExcelValueType valueTypeOf(FieldType type) {
        if (type == null) {
            return ExcelValueType.TEXT;
        }
        return switch (type) {
            case STRING, TEXT, JSON -> ExcelValueType.TEXT;
            case INTEGER, LONG, DECIMAL -> ExcelValueType.NUMBER;
            case BOOLEAN -> ExcelValueType.BOOLEAN;
            case DATE -> ExcelValueType.DATE;
            case TIMESTAMP -> ExcelValueType.DATE_TIME;
            case ZONED_TIMESTAMP -> ExcelValueType.DATE_TIME_WITH_TIME_ZONE;
        };
    }

    private List<String> dropdownOptions(String entityAlias,
                                         DynamicFieldDescriptor field,
                                         DynamicExchangeTemplateOptions options) {
        if (field.optionBinding() != null) {
            return dictionaryOptions(field);
        }
        if (field.reference() != null && options.referenceDropdownEnabled(entityAlias, field.fieldName())) {
            return referenceOptions(field.fieldName(), field.reference(), options.referenceDropdownLimit());
        }
        return List.of();
    }

    private List<String> dictionaryOptions(DynamicFieldDescriptor field) {
        if (optionSourceRegistry == null) {
            return List.of();
        }
        try {
            return optionSourceRegistry.source(field.optionBinding())
                    .options()
                    .stream()
                    .map(OptionItem::code)
                    .toList();
        } catch (RuntimeException ex) {
            throw new PlatformException("dynamic exchange template option load failed: " + field.fieldName(), ex);
        }
    }

    private List<String> referenceOptions(String fieldName, DynamicReferenceDescriptor reference, int limit) {
        try {
            return referenceDropdownResolver.resolve(reference, limit);
        } catch (RuntimeException ex) {
            throw new PlatformException("dynamic exchange template reference option load failed: " + fieldName, ex);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
