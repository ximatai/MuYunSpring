package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.FieldReadAbility;
import net.ximatai.muyun.spring.ability.FieldReadPolicy;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.ability.security.ProtectedFieldAccessor;
import net.ximatai.muyun.spring.common.option.OptionLoadResolver;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RecordReadProjectionPlanner {
    private static final List<String> INTERNAL_READ_FIELDS = List.of(
            StandardEntitySchema.ID_FIELD,
            StandardEntitySchema.TENANT_ID_FIELD,
            StandardEntitySchema.VERSION_FIELD
    );

    private RecordReadProjectionPlanner() {
    }

    public static RecordReadProjection defaultList(ResolvedModuleUiDescriptor descriptor,
                                                   ResolvedModuleReadModel readModel) {
        return plan(descriptor, readModel, "default_list");
    }

    public static RecordReadProjection defaultList(ResolvedModuleUiDescriptor descriptor,
                                                   ResolvedModuleReadModel readModel,
                                                   Object recordService) {
        return plan(descriptor, readModel, "default_list", recordService, null);
    }

    public static RecordReadProjection defaultList(ResolvedModuleUiDescriptor descriptor,
                                                   ResolvedModuleReadModel readModel,
                                                   Object recordService,
                                                   ActionExecutionContext actionContext) {
        return plan(descriptor, readModel, "default_list", recordService, actionContext);
    }

    public static RecordReadProjection explicit(String moduleAlias,
                                                ResolvedModuleReadModel readModel,
                                                String viewCode,
                                                List<String> outputFieldNames,
                                                Object recordService,
                                                ActionExecutionContext actionContext) {
        if (readModel == null) {
            throw new IllegalArgumentException("resolved module read model must not be null");
        }
        if (moduleAlias == null || moduleAlias.isBlank()) {
            throw new IllegalArgumentException("record read projection module alias must not be blank");
        }
        if (!moduleAlias.equals(readModel.moduleAlias())) {
            throw new IllegalArgumentException("record read projection module alias mismatch: "
                    + moduleAlias + " != " + readModel.moduleAlias());
        }
        if (viewCode == null || viewCode.isBlank()) {
            throw new IllegalArgumentException("record read projection view code must not be blank");
        }
        if (outputFieldNames == null || outputFieldNames.isEmpty()) {
            throw new IllegalArgumentException("record read projection output fields must not be empty");
        }
        validateActionContext(moduleAlias, actionContext);
        Set<String> readableFields = readableFields(readModel);
        FieldReadPolicy fieldReadPolicy = fieldReadPolicy(recordService, actionContext);
        LinkedHashSet<ViewFieldRef> outputFields = new LinkedHashSet<>();
        for (String outputFieldName : outputFieldNames) {
            if (outputFieldName == null || outputFieldName.isBlank()) {
                throw new IllegalArgumentException("record read projection output field must not be blank");
            }
            String fieldName = outputFieldName.trim();
            if (!readableFields.contains(fieldName)) {
                throw new IllegalArgumentException("record read projection field is not readable: "
                        + moduleAlias + "." + viewCode.trim() + "." + fieldName);
            }
            if (!fieldReadPolicy.allows(fieldName)) {
                continue;
            }
            outputFields.add(ViewFieldRef.main(fieldName));
        }
        return new RecordReadProjection(
                moduleAlias,
                viewCode,
                actionContext == null ? null : actionContext.actionCode(),
                actionContext == null ? null : actionContext.permissionCode(),
                actionContext == null ? null : actionContext.actionPolicy().permissionActionCode(),
                fieldReadPolicies(fieldReadPolicy),
                List.copyOf(outputFields),
                INTERNAL_READ_FIELDS,
                postReadTransforms(recordService, outputFields)
        );
    }

    public static RecordReadProjection plan(ResolvedModuleUiDescriptor descriptor,
                                            ResolvedModuleReadModel readModel,
                                            String viewCode) {
        return plan(descriptor, readModel, viewCode, null);
    }

    public static RecordReadProjection plan(ResolvedModuleUiDescriptor descriptor,
                                            ResolvedModuleReadModel readModel,
                                            String viewCode,
                                            Object recordService) {
        return plan(descriptor, readModel, viewCode, recordService, null);
    }

    public static RecordReadProjection plan(ResolvedModuleUiDescriptor descriptor,
                                            ResolvedModuleReadModel readModel,
                                            String viewCode,
                                            Object recordService,
                                            ActionExecutionContext actionContext) {
        if (descriptor == null) {
            throw new IllegalArgumentException("resolved module UI descriptor must not be null");
        }
        if (readModel == null) {
            throw new IllegalArgumentException("resolved module read model must not be null");
        }
        if (!descriptor.moduleAlias().equals(readModel.moduleAlias())) {
            throw new IllegalArgumentException("record read projection module alias mismatch: "
                    + descriptor.moduleAlias() + " != " + readModel.moduleAlias());
        }
        validateActionContext(descriptor, actionContext);
        ResolvedViewDescriptor view = view(descriptor, viewCode);
        LinkedHashSet<ViewFieldRef> outputFields = new LinkedHashSet<>();
        Set<String> readableFields = readableFields(readModel);
        FieldReadPolicy fieldReadPolicy = fieldReadPolicy(recordService, actionContext);
        for (ResolvedViewFieldDescriptor field : view.fields()) {
            if (Boolean.FALSE.equals(field.visible().constant())
                    && !isPlatformManagedReadField(readModel, field.fieldRef())) {
                continue;
            }
            String fieldName = field.fieldRef().fieldName();
            if (!readableFields.contains(fieldName)) {
                throw new IllegalArgumentException("record read projection field is not readable: "
                        + descriptor.moduleAlias() + "." + view.viewCode() + "." + fieldName);
            }
            if (!fieldReadPolicy.allows(fieldName)) {
                continue;
            }
            outputFields.add(field.fieldRef());
        }
        return new RecordReadProjection(
                descriptor.moduleAlias(),
                view.viewCode(),
                actionContext == null ? null : actionContext.actionCode(),
                actionContext == null ? null : actionContext.permissionCode(),
                actionContext == null ? null : actionContext.actionPolicy().permissionActionCode(),
                fieldReadPolicies(fieldReadPolicy),
                List.copyOf(outputFields),
                INTERNAL_READ_FIELDS,
                postReadTransforms(recordService, outputFields)
        );
    }

    private static void validateActionContext(ResolvedModuleUiDescriptor descriptor,
                                              ActionExecutionContext actionContext) {
        if (actionContext == null) {
            return;
        }
        if (!descriptor.moduleAlias().equals(actionContext.moduleAlias())) {
            throw new IllegalArgumentException("record read projection action module alias mismatch: "
                    + descriptor.moduleAlias() + " != " + actionContext.moduleAlias());
        }
        if (!PlatformAction.QUERY.matches(actionContext.actionCode())
                && !PlatformAction.RECYCLE_BIN_QUERY.matches(actionContext.actionCode())) {
            throw new IllegalArgumentException("record read projection requires list query action context: "
                    + descriptor.moduleAlias() + "." + actionContext.actionCode());
        }
    }

    private static void validateActionContext(String moduleAlias,
                                              ActionExecutionContext actionContext) {
        if (actionContext == null) {
            return;
        }
        if (!moduleAlias.equals(actionContext.moduleAlias())) {
            throw new IllegalArgumentException("record read projection action module alias mismatch: "
                    + moduleAlias + " != " + actionContext.moduleAlias());
        }
        if (actionContext.actionPolicy().level() != PlatformActionLevel.LIST) {
            throw new IllegalArgumentException("explicit record read projection requires list action context: "
                    + moduleAlias + "." + actionContext.actionCode());
        }
    }

    @SuppressWarnings("rawtypes")
    private static FieldReadPolicy fieldReadPolicy(Object recordService, ActionExecutionContext actionContext) {
        if (!(recordService instanceof FieldReadAbility fieldReadAbility)) {
            return FieldReadPolicy.allReadable();
        }
        FieldReadPolicy policy = fieldReadAbility.fieldReadPolicy(actionContext);
        return policy == null ? FieldReadPolicy.allReadable() : policy;
    }

    private static List<String> fieldReadPolicies(FieldReadPolicy fieldReadPolicy) {
        if (fieldReadPolicy == null || !fieldReadPolicy.restricted()) {
            return List.of();
        }
        return List.of("fieldReadPolicy:explicit");
    }

    @SuppressWarnings("rawtypes")
    private static List<String> postReadTransforms(Object recordService, Set<ViewFieldRef> outputFields) {
        Set<String> outputFieldNames = outputFields.stream()
                .filter(field -> field.relationCode() == null)
                .map(ViewFieldRef::fieldName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> transforms = new LinkedHashSet<>();
        if (recordService instanceof FieldProtectionAbility fieldProtectionAbility) {
            for (Object item : fieldProtectionAbility.fieldProtectionPlan().fields()) {
                ProtectedFieldAccessor<?> field = (ProtectedFieldAccessor<?>) item;
                if (outputFieldNames.contains(field.fieldName()) && field.protection().hasOutputProtection()) {
                    transforms.add(fieldProtectionTransform(field));
                }
            }
        }
        Class<?> modelClass = modelClass(recordService);
        if (modelClass != null) {
            OptionLoadResolver.resolve(modelClass).stream()
                    .filter(definition -> outputFieldNames.contains(definition.outputField())
                            || (definition.optionItemField().equals("title")
                            && outputFieldNames.contains(definition.sourceField())))
                    .map(definition -> RecordReadPostTransform.optionLoad(definition.outputField()))
                    .map(RecordReadPostTransform::serialize)
                    .forEach(transforms::add);
        }
        return List.copyOf(transforms);
    }

    private static String fieldProtectionTransform(ProtectedFieldAccessor<?> field) {
        return RecordReadPostTransform.fieldProtection(field.fieldName()).serialize();
    }

    private static Class<?> modelClass(Object recordService) {
        if (recordService instanceof CrudAbility<?> crudAbility) {
            return crudAbility.modelClass();
        }
        return null;
    }

    private static ResolvedViewDescriptor view(ResolvedModuleUiDescriptor descriptor, String viewCode) {
        if (descriptor.page() != null) {
            if (descriptor.page().list() != null) {
                ResolvedViewDescriptor pageList = descriptor.page().list().fields();
                if (pageList.viewCode().equals(viewCode) || "default_list".equals(viewCode)) {
                    return pageList;
                }
            }
            if (descriptor.page().explorer() != null && "default_list".equals(viewCode)) {
                ResolvedPageExplorerDescriptor explorer = descriptor.page().explorer();
                java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
                names.add(explorer.titleField());
                if (explorer.secondaryField() != null) names.add(explorer.secondaryField());
                if (explorer.mutedWhenDisabled()) names.add("enabled");
                return new ResolvedViewDescriptor("default_list", ModuleViewKind.LIST, ModuleUiClientType.WEB,
                        explorer.title(), names.stream().map(name -> new ResolvedViewFieldDescriptor(
                                ViewFieldRef.main(name), null, UiRule.constant(Boolean.TRUE),
                                UiRule.constant(Boolean.FALSE), UiRule.constant(Boolean.TRUE), null,
                                null, 1, null, null)).toList());
            }
        }
        throw new IllegalArgumentException("record read projection page slot not found: "
                + descriptor.moduleAlias() + "." + viewCode);
    }

    private static Set<String> readableFields(ResolvedModuleReadModel readModel) {
        LinkedHashSet<String> fields = new LinkedHashSet<>(INTERNAL_READ_FIELDS);
        readModel.fields().stream()
                .map(ResolvedModuleReadField::fieldName)
                .forEach(fields::add);
        return Set.copyOf(fields);
    }

    private static boolean isPlatformManagedReadField(ResolvedModuleReadModel readModel, ViewFieldRef fieldRef) {
        return readModel.fields().stream()
                .anyMatch(field -> field.platformManaged()
                        && java.util.Objects.equals(field.relationCode(), fieldRef.relationCode())
                        && field.fieldName().equals(fieldRef.fieldName()));
    }
}
