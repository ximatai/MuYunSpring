package net.ximatai.muyun.spring.platform.exchange.exporter;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordActionGateway;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelColumnPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelSheetPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;
import net.ximatai.muyun.spring.platform.exchange.template.DynamicExchangeTemplatePlanBuilder;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class DynamicExportExecutor {
    private static final String EXPORT_TRACE_ID = "dynamic-export";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final DynamicRecordService recordService;
    private final DynamicExchangeTemplatePlanBuilder templatePlanBuilder;

    public DynamicExportExecutor(DynamicRecordService recordService,
                                 DynamicExchangeTemplatePlanBuilder templatePlanBuilder) {
        this.recordService = Objects.requireNonNull(recordService, "recordService must not be null");
        this.templatePlanBuilder = Objects.requireNonNull(templatePlanBuilder, "templatePlanBuilder must not be null");
    }

    public ExcelWorkbookPlan export(DynamicExportCommand command) {
        DynamicExportCommand normalized = Objects.requireNonNull(command, "command must not be null");
        ExcelWorkbookPlan template = templatePlanBuilder.build(normalized.descriptor());
        Map<String, Map<String, DynamicReferenceDescriptor>> references = referencesByEntityAndField(normalized.descriptor());
        DynamicRecordActionGateway records = recordService.recordsForAction(
                normalized.descriptor().moduleAlias(), PlatformAction.EXPORT, EXPORT_TRACE_ID);
        List<DynamicRecord> mainRecords = records.list(
                normalized.descriptor().mainEntityAlias(),
                normalized.criteria(),
                normalized.pageRequest(),
                normalized.sortArray()
        );
        Map<String, DynamicRelationDescriptor> relationsByChild =
                firstLevelRelationsByChildEntity(normalized.descriptor().relations(), normalized.descriptor().mainEntityAlias());
        return new ExcelWorkbookPlan(template.meta(), withRows(template.sheets(), mainRecords, records, relationsByChild,
                references));
    }

    private List<ExcelSheetPlan> withRows(List<ExcelSheetPlan> sheets,
                                          List<DynamicRecord> mainRecords,
                                          DynamicRecordActionGateway records,
                                          Map<String, DynamicRelationDescriptor> relationsByChild,
                                          Map<String, Map<String, DynamicReferenceDescriptor>> references) {
        List<ExcelSheetPlan> result = new ArrayList<>();
        for (ExcelSheetPlan sheet : sheets) {
            List<List<Object>> rows = sheet.main()
                    ? rows(sheet.entityAlias(), sheet.columns(), mainRecords, references)
                    : childRows(sheet, mainRecords, records, relationsByChild.get(sheet.entityAlias()), references);
            result.add(new ExcelSheetPlan(
                    sheet.sheetName(),
                    sheet.entityAlias(),
                    sheet.main(),
                    sheet.columns(),
                    rows
            ));
        }
        return result;
    }

    private Map<String, DynamicRelationDescriptor> firstLevelRelationsByChildEntity(
            List<DynamicRelationDescriptor> relations,
            String mainEntityAlias) {
        Map<String, DynamicRelationDescriptor> result = new LinkedHashMap<>();
        for (DynamicRelationDescriptor relation : relations) {
            if (relation == null || !mainEntityAlias.equals(relation.parentEntityAlias())) {
                continue;
            }
            result.putIfAbsent(relation.childEntityAlias(), relation);
        }
        return Map.copyOf(result);
    }

    private Map<String, Map<String, DynamicReferenceDescriptor>> referencesByEntityAndField(
            DynamicModuleDescriptor descriptor) {
        Map<String, Map<String, DynamicReferenceDescriptor>> result = new LinkedHashMap<>();
        for (DynamicEntityDescriptor entity : descriptor.entities()) {
            Map<String, DynamicReferenceDescriptor> fields = new LinkedHashMap<>();
            for (DynamicFieldDescriptor field : entity.fields()) {
                if (field.reference() != null) {
                    fields.put(field.fieldName(), field.reference());
                }
            }
            if (!fields.isEmpty()) {
                result.put(entity.entityAlias(), Map.copyOf(fields));
            }
        }
        return Map.copyOf(result);
    }

    private List<List<Object>> rows(String entityAlias,
                                    List<ExcelColumnPlan> columns,
                                    List<DynamicRecord> records,
                                    Map<String, Map<String, DynamicReferenceDescriptor>> references) {
        ReferenceTitleLookup titles = titleLookup(entityAlias, columns, records, references);
        return records.stream()
                .map(record -> row(columns, record, titles))
                .toList();
    }

    private List<Object> row(List<ExcelColumnPlan> columns, DynamicRecord record, ReferenceTitleLookup titles) {
        Map<String, Object> values = record.getValues();
        return columns.stream()
                .map(column -> value(column, record, values, titles))
                .toList();
    }

    private List<List<Object>> childRows(ExcelSheetPlan sheet,
                                         List<DynamicRecord> mainRecords,
                                         DynamicRecordActionGateway records,
                                         DynamicRelationDescriptor relation,
                                         Map<String, Map<String, DynamicReferenceDescriptor>> references) {
        if (relation == null || mainRecords.isEmpty()) {
            return List.of();
        }
        Map<String, List<DynamicRecord>> childrenByParentId = childrenByParentId(records, relation, parentIds(mainRecords));
        ReferenceTitleLookup titles = titleLookup(sheet.entityAlias(), sheet.columns(),
                childrenByParentId.values().stream().flatMap(Collection::stream).toList(), references);
        List<List<Object>> rows = new ArrayList<>();
        for (DynamicRecord parent : mainRecords) {
            String parentId = parent.getId();
            List<DynamicRecord> children = childrenByParentId.get(parentId);
            if (children == null || children.isEmpty()) {
                rows.add(emptyChildRow(sheet.columns(), parentId));
                continue;
            }
            children.stream()
                    .map(child -> childRow(sheet.columns(), parentId, child, titles))
                    .forEach(rows::add);
        }
        return List.copyOf(rows);
    }

    private Set<String> parentIds(List<DynamicRecord> mainRecords) {
        return mainRecords.stream()
                .map(DynamicRecord::getId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, List<DynamicRecord>> childrenByParentId(DynamicRecordActionGateway records,
                                                                DynamicRelationDescriptor relation,
                                                                Collection<String> parentIds) {
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<DynamicRecord>> grouped = new LinkedHashMap<>();
        records.list(
                relation.childEntityAlias(),
                Criteria.of().in(relation.childForeignKeyField(), List.copyOf(parentIds)),
                ALL
        ).forEach(child -> {
            Object parentId = child.getValues().get(relation.childForeignKeyField());
            if (parentId != null) {
                grouped.computeIfAbsent(String.valueOf(parentId), ignored -> new ArrayList<>()).add(child);
            }
        });
        grouped.replaceAll((ignored, value) -> List.copyOf(value));
        return Map.copyOf(grouped);
    }

    private List<Object> childRow(List<ExcelColumnPlan> columns,
                                  String relateId,
                                  DynamicRecord child,
                                  ReferenceTitleLookup titles) {
        Map<String, Object> values = child.getValues();
        return columns.stream()
                .map(column -> childValue(column, relateId, values, titles))
                .toList();
    }

    private Object childValue(ExcelColumnPlan column,
                              String relateId,
                              Map<String, Object> values,
                              ReferenceTitleLookup titles) {
        if (ExcelExchangeProtocol.RELATE_ID_FIELD.equals(column.fieldName())) {
            return relateId;
        }
        return titles.render(column.fieldName(), values.get(column.fieldName()));
    }

    private List<Object> emptyChildRow(List<ExcelColumnPlan> columns, String relateId) {
        return columns.stream()
                .map(column -> ExcelExchangeProtocol.RELATE_ID_FIELD.equals(column.fieldName()) ? (Object) relateId : null)
                .toList();
    }

    private Object value(ExcelColumnPlan column,
                         DynamicRecord record,
                         Map<String, Object> values,
                         ReferenceTitleLookup titles) {
        if (ExcelExchangeProtocol.RELATE_ID_FIELD.equals(column.fieldName())) {
            return record.getId();
        }
        return titles.render(column.fieldName(), values.get(column.fieldName()));
    }

    private ReferenceTitleLookup titleLookup(String entityAlias,
                                             List<ExcelColumnPlan> columns,
                                             List<DynamicRecord> records,
                                             Map<String, Map<String, DynamicReferenceDescriptor>> references) {
        Map<String, DynamicReferenceDescriptor> entityReferences = references.getOrDefault(entityAlias, Map.of());
        if (entityReferences.isEmpty() || records.isEmpty()) {
            return emptyTitleLookup();
        }
        Map<String, Map<String, String>> titlesByField = new LinkedHashMap<>();
        for (ExcelColumnPlan column : columns) {
            DynamicReferenceDescriptor reference = entityReferences.get(column.fieldName());
            if (reference == null) {
                continue;
            }
            Set<String> ids = records.stream()
                    .flatMap(record -> referenceIds(reference, record.getValues().get(column.fieldName())).stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!ids.isEmpty()) {
                ReferenceTarget target = ReferenceTarget.of(reference.targetModuleAlias(), reference.targetEntityAlias());
                var ability = PlatformAbilityRuntime.referenceTargetResolver().resolve(target)
                        .or(() -> recordService.referenceAbility(target))
                        .orElseThrow(() -> new PlatformException(PlatformErrorCodes.CONFIG_MISSING, 409,
                                "dynamic export reference target is unavailable: " + target.qualifiedName()));
                titlesByField.put(column.fieldName(), ability.titles(ids));
            }
        }
        return titlesByField.isEmpty() ? emptyTitleLookup() : new ReferenceTitleLookup(entityReferences,
                titlesByField);
    }

    private ReferenceTitleLookup emptyTitleLookup() {
        return new ReferenceTitleLookup(Map.of(), Map.of());
    }

    private List<String> referenceIds(DynamicReferenceDescriptor reference, Object rawValue) {
        if (rawValue == null) {
            return List.of();
        }
        if (reference.cardinality() == ReferenceCardinality.MANY) {
            return manyReferenceIds(rawValue);
        }
        String value = text(rawValue);
        return value == null ? List.of() : List.of(value);
    }

    private List<String> manyReferenceIds(Object rawValue) {
        if (rawValue instanceof Collection<?> values) {
            return values.stream()
                    .map(this::text)
                    .filter(Objects::nonNull)
                    .toList();
        }
        if (rawValue.getClass().isArray()) {
            List<String> result = new ArrayList<>();
            int length = Array.getLength(rawValue);
            for (int index = 0; index < length; index++) {
                String value = text(Array.get(rawValue, index));
                if (value != null) {
                    result.add(value);
                }
            }
            return List.copyOf(result);
        }
        String value = text(rawValue);
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private final class ReferenceTitleLookup {
        private final Map<String, DynamicReferenceDescriptor> referencesByField;
        private final Map<String, Map<String, String>> titlesByField;

        private ReferenceTitleLookup(Map<String, DynamicReferenceDescriptor> referencesByField,
                                     Map<String, Map<String, String>> titlesByField) {
            this.referencesByField = referencesByField;
            this.titlesByField = titlesByField;
        }

        private Object render(String fieldName, Object rawValue) {
            DynamicReferenceDescriptor reference = referencesByField.get(fieldName);
            if (reference == null || rawValue == null) {
                return rawValue;
            }
            Map<String, String> titles = titlesByField.getOrDefault(fieldName, Map.of());
            List<String> ids = referenceIds(reference, rawValue);
            if (ids.isEmpty()) {
                return rawValue;
            }
            List<String> rendered = ids.stream()
                    .map(id -> titles.getOrDefault(id, id))
                    .toList();
            if (reference.cardinality() == ReferenceCardinality.MANY) {
                return String.join(",", rendered);
            }
            return rendered.getFirst();
        }
    }
}
