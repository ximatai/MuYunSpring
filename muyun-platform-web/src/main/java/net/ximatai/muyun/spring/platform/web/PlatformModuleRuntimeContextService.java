package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceFieldPolicy;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageBootstrapService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;
import net.ximatai.muyun.spring.platform.ui.PageNavigatorSourceCapabilityResolver;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class PlatformModuleRuntimeContextService {
    static final String DECISION_AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    static final String DECISION_ACCESS_DENIED = "ACCESS_DENIED";

    private final PlatformModuleService moduleService;
    private final PlatformModuleActionService actionService;
    private final StaticModuleDefinitionCatalog staticModuleCatalog;
    private final DynamicRecordService dynamicRecordService;
    private final ActionExecutionPolicyService actionExecutionPolicyService;
    private final PlatformPageConfigSnapshotService pageConfigSnapshotService;
    private final PlatformPageBootstrapService pageBootstrapService;
    private final List<FileReferenceFieldPolicy> fileReferenceFieldPolicies;
    private final PageNavigatorResolver pageNavigatorResolver;
    private final PageNavigatorSourceCapabilityResolver navigatorSourceCapabilityResolver;
    private final FieldUiControlService fieldUiControlService;
    private final FieldUiControlPropertyService fieldUiControlPropertyService;
    private final FieldUiControlBindingService fieldUiControlBindingService;

    @Autowired
    public PlatformModuleRuntimeContextService(PlatformModuleService moduleService,
                                               PlatformModuleActionService actionService,
                                               StaticModuleDefinitionCatalog staticModuleCatalog,
                                               ObjectProvider<DynamicRecordService> dynamicRecordService,
                                               ObjectProvider<PlatformPageConfigSnapshotService> pageConfigSnapshotService,
                                               ObjectProvider<PlatformPageBootstrapService> pageBootstrapService,
                                               ObjectProvider<ActionExecutionPolicyService> actionExecutionPolicyService,
                                               ObjectProvider<FileReferenceFieldPolicy> fileReferenceFieldPolicies,
                                               ObjectProvider<PageNavigatorResolver> pageNavigatorResolver,
                                               ObjectProvider<PageNavigatorSourceCapabilityResolver> navigatorSourceCapabilityResolver,
                                               ObjectProvider<FieldUiControlService> fieldUiControlService,
                                               ObjectProvider<FieldUiControlPropertyService> fieldUiControlPropertyService,
                                               ObjectProvider<FieldUiControlBindingService> fieldUiControlBindingService) {
        this(moduleService, actionService, staticModuleCatalog,
                dynamicRecordService == null ? null : dynamicRecordService.getIfAvailable(),
                pageConfigSnapshotService == null ? null : pageConfigSnapshotService.getIfAvailable(),
                pageBootstrapService == null ? null : pageBootstrapService.getIfAvailable(),
                actionExecutionPolicyService == null
                        ? new AllowAllActionExecutionPolicyService()
                        : actionExecutionPolicyService.getIfAvailable(AllowAllActionExecutionPolicyService::new),
                fileReferenceFieldPolicies == null ? List.of() : fileReferenceFieldPolicies.orderedStream().toList(),
                new CompositePageNavigatorResolver(pageNavigatorResolver == null ? List.of()
                        : pageNavigatorResolver.orderedStream().toList()),
                navigatorSourceCapabilityResolver == null ? null : navigatorSourceCapabilityResolver.getIfAvailable(),
                fieldUiControlService == null ? null : fieldUiControlService.getIfAvailable(),
                fieldUiControlPropertyService == null ? null : fieldUiControlPropertyService.getIfAvailable(),
                fieldUiControlBindingService == null ? null : fieldUiControlBindingService.getIfAvailable());
    }

    PlatformModuleRuntimeContextService(PlatformModuleService moduleService,
                                        PlatformModuleActionService actionService,
                                        StaticModuleDefinitionCatalog staticModuleCatalog,
                                        DynamicRecordService dynamicRecordService,
                                        PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                        PlatformPageBootstrapService pageBootstrapService,
                                        ActionExecutionPolicyService actionExecutionPolicyService) {
        this(moduleService, actionService, staticModuleCatalog, dynamicRecordService, pageConfigSnapshotService,
                pageBootstrapService, actionExecutionPolicyService, List.of(), new DeclaredPageNavigatorResolver());
    }

    PlatformModuleRuntimeContextService(PlatformModuleService moduleService,
                                        PlatformModuleActionService actionService,
                                        StaticModuleDefinitionCatalog staticModuleCatalog,
                                        DynamicRecordService dynamicRecordService,
                                        PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                        PlatformPageBootstrapService pageBootstrapService,
                                        ActionExecutionPolicyService actionExecutionPolicyService,
                                        List<FileReferenceFieldPolicy> fileReferenceFieldPolicies) {
        this(moduleService, actionService, staticModuleCatalog, dynamicRecordService, pageConfigSnapshotService,
                pageBootstrapService, actionExecutionPolicyService, fileReferenceFieldPolicies,
                new DeclaredPageNavigatorResolver(), null);
    }

    PlatformModuleRuntimeContextService(PlatformModuleService moduleService,
                                        PlatformModuleActionService actionService,
                                        StaticModuleDefinitionCatalog staticModuleCatalog,
                                        DynamicRecordService dynamicRecordService,
                                        PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                        PlatformPageBootstrapService pageBootstrapService,
                                        ActionExecutionPolicyService actionExecutionPolicyService,
                                        List<FileReferenceFieldPolicy> fileReferenceFieldPolicies,
                                        PageNavigatorResolver pageNavigatorResolver) {
        this(moduleService, actionService, staticModuleCatalog, dynamicRecordService, pageConfigSnapshotService,
                pageBootstrapService, actionExecutionPolicyService, fileReferenceFieldPolicies, pageNavigatorResolver, null);
    }

    PlatformModuleRuntimeContextService(PlatformModuleService moduleService,
                                        PlatformModuleActionService actionService,
                                        StaticModuleDefinitionCatalog staticModuleCatalog,
                                        DynamicRecordService dynamicRecordService,
                                        PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                        PlatformPageBootstrapService pageBootstrapService,
                                        ActionExecutionPolicyService actionExecutionPolicyService,
                                        List<FileReferenceFieldPolicy> fileReferenceFieldPolicies,
                                        PageNavigatorResolver pageNavigatorResolver,
                                        PageNavigatorSourceCapabilityResolver navigatorSourceCapabilityResolver) {
        this(moduleService, actionService, staticModuleCatalog, dynamicRecordService, pageConfigSnapshotService,
                pageBootstrapService, actionExecutionPolicyService, fileReferenceFieldPolicies, pageNavigatorResolver,
                navigatorSourceCapabilityResolver, null, null, null);
    }

    PlatformModuleRuntimeContextService(PlatformModuleService moduleService,
                                        PlatformModuleActionService actionService,
                                        StaticModuleDefinitionCatalog staticModuleCatalog,
                                        DynamicRecordService dynamicRecordService,
                                        PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                        PlatformPageBootstrapService pageBootstrapService,
                                        ActionExecutionPolicyService actionExecutionPolicyService,
                                        List<FileReferenceFieldPolicy> fileReferenceFieldPolicies,
                                        PageNavigatorResolver pageNavigatorResolver,
                                        PageNavigatorSourceCapabilityResolver navigatorSourceCapabilityResolver,
                                        FieldUiControlService fieldUiControlService,
                                        FieldUiControlPropertyService fieldUiControlPropertyService,
                                        FieldUiControlBindingService fieldUiControlBindingService) {
        this.moduleService = moduleService;
        this.actionService = actionService;
        this.staticModuleCatalog = staticModuleCatalog;
        this.dynamicRecordService = dynamicRecordService;
        this.pageConfigSnapshotService = pageConfigSnapshotService;
        this.pageBootstrapService = pageBootstrapService;
        this.actionExecutionPolicyService = actionExecutionPolicyService == null
                ? new AllowAllActionExecutionPolicyService()
                : actionExecutionPolicyService;
        this.fileReferenceFieldPolicies = fileReferenceFieldPolicies == null ? List.of() : List.copyOf(fileReferenceFieldPolicies);
        this.pageNavigatorResolver = pageNavigatorResolver == null
                ? new DeclaredPageNavigatorResolver()
                : pageNavigatorResolver;
        this.navigatorSourceCapabilityResolver = navigatorSourceCapabilityResolver;
        this.fieldUiControlService = fieldUiControlService;
        this.fieldUiControlPropertyService = fieldUiControlPropertyService;
        this.fieldUiControlBindingService = fieldUiControlBindingService;
    }

    public PlatformModuleRuntimeContext context(String moduleAlias) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        PlatformModule module = moduleService.resolveVisibleModule(validModuleAlias);
        Optional<StaticModuleDefinition> staticDefinition = staticModuleCatalog.find(validModuleAlias);
        DynamicModuleDescriptor dynamicDescriptor = dynamicDescriptor(module, validModuleAlias);
        if (module == null && staticDefinition.isEmpty() && dynamicDescriptor == null) {
            throw new PlatformException(PlatformErrorCodes.RESOURCE_NOT_FOUND, 404,
                    "module runtime context not found: " + validModuleAlias);
        }
        ModuleKind moduleKind = moduleKind(module, staticDefinition, dynamicDescriptor);
        List<PlatformModuleRuntimeAction> actions = actions(validModuleAlias, moduleKind, staticDefinition,
                dynamicDescriptor);
        Set<EntityCapability> capabilities = capabilities(staticDefinition, dynamicDescriptor, actions);
        String title = title(module, staticDefinition, dynamicDescriptor, validModuleAlias);
        ResolvedModuleUiDescriptor uiDescriptor = uiDescriptor(validModuleAlias, moduleKind, title, staticDefinition,
                dynamicDescriptor);
        Set<NavigatorSourceCapability> navigatorSourceCapabilities = navigatorSourceCapabilityResolver == null
                ? Set.of()
                : navigatorSourceCapabilityResolver.capabilities(validModuleAlias);
        return new PlatformModuleRuntimeContext(
                validModuleAlias,
                title,
                moduleKind,
                entryType(module, staticDefinition),
                entryRoute(module, staticDefinition),
                entryExternalUrl(module, staticDefinition),
                mainEntityAlias(staticDefinition, dynamicDescriptor),
                capabilities,
                abilityCodes(capabilities),
                actions,
                navigatorSourceCapabilities,
                uiDescriptor
        );
    }

    /**
     * Resolves the page-runtime declaration used by the standard form upload endpoint.
     * A ticket can only be issued for a file-reference field that the same module runtime
     * exposes to the browser, for either static or dynamic modules.
     */
    public boolean declaresFileReference(String moduleAlias, String relationCode, String fieldName) {
        return fileReference(moduleAlias, relationCode, fieldName) != null;
    }

    /** Resolves the declared file-reference contract used by storage-specific upload transports. */
    public ResolvedFileReferenceFieldDescriptor fileReference(String moduleAlias, String relationCode, String fieldName) {
        ResolvedModuleUiDescriptor descriptor = context(moduleAlias).uiDescriptor();
        if (descriptor == null) return null;
        return descriptor.fileReferences().stream().filter(reference ->
                java.util.Objects.equals(reference.fieldRef().relationCode(), relationCode)
                        && java.util.Objects.equals(reference.fieldRef().fieldName(), fieldName))
                .findFirst().orElse(null);
    }

    private ResolvedModuleUiDescriptor uiDescriptor(String moduleAlias,
                                                    ModuleKind moduleKind,
                                                    String title,
                                                    Optional<StaticModuleDefinition> staticDefinition,
                                                    DynamicModuleDescriptor dynamicDescriptor) {
        if (moduleKind == ModuleKind.DYNAMIC) {
            return dynamicUiDescriptor(moduleAlias, title, dynamicDescriptor);
        }
        ResolvedModuleUiDescriptor descriptor = staticDefinition
                .map(definition -> ModuleUiDescriptorCompiler.compile(definition, this::referencePickerMode))
                .orElse(null);
        return descriptor == null ? null : descriptor.withPage(resolvePage(moduleAlias, moduleKind, descriptor.page()))
                .withFileReferences(descriptor.fileReferences().stream()
                .map(reference -> withFieldAccess(moduleAlias, reference))
                .toList());
    }

    private ResolvedModuleUiDescriptor dynamicUiDescriptor(String moduleAlias,
                                                           String title,
                                                           DynamicModuleDescriptor dynamicDescriptor) {
        if (pageConfigSnapshotService == null || pageBootstrapService == null) {
            return null;
        }
        PlatformPageConfigSnapshot snapshot = pageConfigSnapshotService.snapshot(moduleAlias);
        PlatformResolvedPageConfig resolvedConfig = pageBootstrapService.resolveConfig(snapshot,
                PlatformUiClientType.WEB);
        java.util.Map<ViewFieldRef, FieldValueType> fieldTypes = dynamicFieldTypes(dynamicDescriptor, resolvedConfig);
        ModuleUiDefinition definition = DynamicModuleUiDefinitionAdapter.fromPublishedSnapshot(snapshot,
                resolvedConfig, dynamicDescriptor.entities().stream()
                        .filter(entity -> dynamicDescriptor.mainEntityAlias().equals(entity.entityAlias()))
                        .findFirst()
                        .map(net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor::formulaRules)
                        .orElse(List.of()), fieldTypes);
        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(definition, ModuleKind.DYNAMIC, title,
                dynamicOptionFields(dynamicDescriptor), dynamicReferenceFields(dynamicDescriptor),
                dynamicRecordLabelField(dynamicDescriptor), fieldTypes, dynamicFieldControls(resolvedConfig))
                .withFileReferences(dynamicFileReferences(dynamicDescriptor, resolvedConfig).stream()
                        .map(reference -> withFieldAccess(moduleAlias, reference))
                        .toList());
        return descriptor.withPage(resolvePage(moduleAlias, ModuleKind.DYNAMIC, descriptor.page()));
    }

    private java.util.Map<String, ResolvedFieldControlDescriptor> dynamicFieldControls(
            PlatformResolvedPageConfig resolvedConfig) {
        if (fieldUiControlService == null || fieldUiControlPropertyService == null || fieldUiControlBindingService == null) {
            return FieldControlDescriptorCatalog.standard();
        }
        List<String> aliases = resolvedConfig.uiFields().stream()
                .map(net.ximatai.muyun.spring.platform.ui.PlatformResolvedUiField::fieldUiControlAlias)
                .filter(alias -> alias != null && !alias.isBlank())
                .filter(alias -> !"file_size".equals(alias)).distinct().toList();
        if (aliases.isEmpty()) return FieldControlDescriptorCatalog.standard();
        java.util.Map<String, ResolvedFieldControlDescriptor> configured = FieldControlDescriptorCatalog.fromConfigured(
                fieldUiControlService.listEnabledByAliases(aliases),
                fieldUiControlPropertyService.listByFieldUiControlAliases(aliases),
                fieldUiControlBindingService.listByFieldUiControlAliases(aliases));
        for (String alias : aliases) {
            if (!configured.containsKey(alias)) {
                throw new IllegalArgumentException("dynamic UI references missing, disabled, or unsupported field control: " + alias);
            }
        }
        java.util.LinkedHashMap<String, ResolvedFieldControlDescriptor> controls = new java.util.LinkedHashMap<>(
                FieldControlDescriptorCatalog.standard());
        controls.putAll(configured);
        return java.util.Map.copyOf(controls);
    }

    private ResolvedModulePageDescriptor resolvePage(String moduleAlias,
                                                     ModuleKind moduleKind,
                                                     ResolvedModulePageDescriptor candidate) {
        if (candidate == null) {
            return null;
        }
        Set<String> visibleLevelKeys = pageNavigatorResolver.visibleLevelKeys(
                new PageNavigatorResolutionContext(moduleAlias, moduleKind,
                        CurrentUserContext.currentUser().orElse(null), candidate));
        ResolvedPageNavigatorDescriptor navigator = filterNavigator(candidate.navigator(), visibleLevelKeys);
        validateNavigatorSourceCapabilities(moduleAlias, navigator);
        return candidate.withNavigator(navigator);
    }

    private void validateNavigatorSourceCapabilities(String pageModuleAlias,
                                                     ResolvedPageNavigatorDescriptor navigator) {
        if (navigator == null || navigatorSourceCapabilityResolver == null) return;
        for (ResolvedPageNavigatorLevelDescriptor level : navigator.levels()) {
            NavigatorSourceCapability required = level.kind() == PageNavigatorKind.TREE
                    ? NavigatorSourceCapability.REFERENCE_TREE
                    : NavigatorSourceCapability.REFERENCE_QUERY;
            if (!navigatorSourceCapabilityResolver.supports(level.sourceModuleAlias(), required)) {
                throw new PlatformException(PlatformErrorCodes.CONFIG_MISSING, 409,
                        "Navigator source capability is unavailable: page=" + pageModuleAlias + ", level="
                                + level.key() + ", source=" + level.sourceModuleAlias() + ", required=" + required);
            }
        }
    }

    private ResolvedPageNavigatorDescriptor filterNavigator(ResolvedPageNavigatorDescriptor navigator,
                                                             Set<String> visibleLevelKeys) {
        if (navigator == null || visibleLevelKeys == null || visibleLevelKeys.isEmpty()) {
            return null;
        }
        List<ResolvedPageNavigatorLevelDescriptor> visibleLevels = navigator.levels().stream()
                .filter(level -> visibleLevelKeys.contains(level.key()))
                .map(level -> new ResolvedPageNavigatorLevelDescriptor(level.key(), level.kind(),
                        level.sourceModuleAlias(), level.title(), level.searchPlaceholder(), level.management(),
                        level.singleResultPolicy(), level.initialSelectionPolicy(), level.sourceScope()))
                .toList();
        List<ResolvedPageContextBindingDescriptor> visibleBindings = navigator.contextBindings().stream()
                .filter(binding -> binding.source() != PageContextSource.NAVIGATOR
                        || visibleLevelKeys.contains(binding.sourceKey()))
                .filter(binding -> binding.target() != PageContextTarget.NAVIGATOR_QUERY
                        || visibleLevelKeys.contains(binding.targetNavigatorLevelKey()))
                .toList();
        return visibleLevels.isEmpty() ? null : new ResolvedPageNavigatorDescriptor(visibleLevels, visibleBindings);
    }

    private ResolvedFileReferenceFieldDescriptor withFieldAccess(String moduleAlias,
                                                                   ResolvedFileReferenceFieldDescriptor reference) {
        return fileReferenceFieldPolicies.stream()
                .filter(policy -> policy.supportsField(moduleAlias, reference.fieldRef().relationCode(),
                        reference.fieldRef().fieldName()))
                .findFirst()
                .map(policy -> reference.withAccess(policy.readAvailable()))
                .orElse(reference);
    }

    private java.util.List<ResolvedFileReferenceFieldDescriptor> dynamicFileReferences(
            DynamicModuleDescriptor dynamicDescriptor,
            PlatformResolvedPageConfig resolvedConfig) {
        if (dynamicDescriptor == null || resolvedConfig == null) {
            return java.util.List.of();
        }
        java.util.Map<String, java.util.Map<String, net.ximatai.muyun.spring.dynamic.descriptor.DynamicFileReferenceDescriptor>>
                referencesByEntity = dynamicDescriptor.entities().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor::entityAlias,
                        entity -> entity.fileReferences().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                                net.ximatai.muyun.spring.dynamic.descriptor.DynamicFileReferenceDescriptor::fieldName,
                                java.util.function.Function.identity(), (left, right) -> left)),
                        (left, right) -> left));
        return resolvedConfig.uiFields().stream()
                .map(field -> {
                    var reference = referencesByEntity.getOrDefault(field.metadataAlias(), java.util.Map.of())
                            .get(field.fieldName());
                    return reference == null ? null : new ResolvedFileReferenceFieldDescriptor(
                            new ViewFieldRef(field.relationAlias(), field.fieldName(), field.moduleMetadataFieldId()),
                            reference.allowedMediaTypes(), reference.maxFileSizeBytes(), reference.maxFiles(),
                            reference.storagePolicy(), false);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private java.util.Map<ViewFieldRef, FieldValueType> dynamicFieldTypes(
            DynamicModuleDescriptor dynamicDescriptor,
            PlatformResolvedPageConfig resolvedConfig) {
        if (dynamicDescriptor == null || resolvedConfig == null) {
            return java.util.Map.of();
        }
        java.util.Map<String, java.util.Map<String, FieldValueType>> typesByEntity = dynamicDescriptor.entities().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        DynamicEntityDescriptor::entityAlias,
                        entity -> entity.fields().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                                DynamicFieldDescriptor::fieldName,
                                field -> FieldValueType.from(field.type()),
                                (left, right) -> left)),
                        (left, right) -> left));
        java.util.LinkedHashMap<ViewFieldRef, FieldValueType> resolved = new java.util.LinkedHashMap<>();
        for (net.ximatai.muyun.spring.platform.ui.PlatformResolvedUiField field : resolvedConfig.uiFields()) {
            FieldValueType type = typesByEntity.getOrDefault(field.metadataAlias(), java.util.Map.of())
                    .get(field.fieldName());
            if (type == null) {
                continue;
            }
            ViewFieldRef fieldRef = field.relationAlias() == null || field.relationAlias().isBlank()
                    ? ViewFieldRef.main(field.fieldName())
                    : ViewFieldRef.relation(field.relationAlias(), field.fieldName());
            resolved.putIfAbsent(fieldRef, type);
        }
        return java.util.Map.copyOf(resolved);
    }

    private java.util.Map<String, ResolvedOptionFieldDescriptor> dynamicOptionFields(
            DynamicModuleDescriptor dynamicDescriptor) {
        if (dynamicDescriptor == null) {
            return java.util.Map.of();
        }
        return dynamicDescriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals(dynamicDescriptor.mainEntityAlias()))
                .findFirst()
                .map(entity -> entity.fields().stream()
                        .filter(field -> field.optionBinding() != null)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                field -> field.fieldName(),
                                field -> new ResolvedOptionFieldDescriptor(field.optionBinding(),
                                        field.selectionMode() == null ? OptionSelectionMode.SINGLE : field.selectionMode(),
                                        null),
                                (left, right) -> left)))
                .orElseGet(java.util.Map::of);
    }

    private java.util.Map<String, ResolvedReferenceFieldDescriptor> dynamicReferenceFields(
            DynamicModuleDescriptor dynamicDescriptor) {
        if (dynamicDescriptor == null) {
            return java.util.Map.of();
        }
        return dynamicDescriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals(dynamicDescriptor.mainEntityAlias()))
                .findFirst()
                .map(entity -> entity.fields().stream()
                        .filter(field -> field.reference() != null)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                field -> field.fieldName(),
                                field -> new ResolvedReferenceFieldDescriptor(
                                        field.reference().targetModuleAlias(), field.reference().cardinality(), null,
                                        referencePickerMode(field.reference().targetModuleAlias())),
                                (left, right) -> left)))
                .orElseGet(java.util.Map::of);
    }

    /** Resolves target capabilities during descriptor compilation; browser code never probes them. */
    private ReferencePickerMode referencePickerMode(String targetModuleAlias) {
        if (targetModuleAlias == null || targetModuleAlias.isBlank()) return ReferencePickerMode.AUTO;
        Optional<StaticModuleDefinition> staticTarget = staticModuleCatalog.find(targetModuleAlias);
        if (staticTarget.isPresent()) {
            return staticTarget.get().capabilities().contains(EntityCapability.TREE)
                    ? ReferencePickerMode.TREE : ReferencePickerMode.LIST;
        }
        if (dynamicRecordService == null) return ReferencePickerMode.AUTO;
        try {
            DynamicModuleDescriptor target = dynamicRecordService.describe(targetModuleAlias);
            boolean tree = target.entities().stream()
                    .filter(entity -> target.mainEntityAlias().equals(entity.entityAlias()))
                    .anyMatch(entity -> entity.capabilities().contains(EntityCapability.TREE.name()));
            return tree ? ReferencePickerMode.TREE : ReferencePickerMode.LIST;
        } catch (RuntimeException ignored) {
            return ReferencePickerMode.AUTO;
        }
    }

    private String dynamicRecordLabelField(DynamicModuleDescriptor descriptor) {
        if (descriptor == null) return null;
        return descriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals(descriptor.mainEntityAlias()))
                .flatMap(entity -> entity.fields().stream())
                .filter(net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor::titleField)
                .map(net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor::fieldName)
                .findFirst().orElse(null);
    }

    private DynamicModuleDescriptor dynamicDescriptor(PlatformModule module, String moduleAlias) {
        if (dynamicRecordService == null) {
            if (module != null && module.getModuleKind() == ModuleKind.DYNAMIC) {
                throw new PlatformException(PlatformErrorCodes.CONFIG_MISSING, 500,
                        "dynamic record service is required for dynamic module context: " + moduleAlias);
            }
            return null;
        }
        if (module != null && module.getModuleKind() != ModuleKind.DYNAMIC) {
            return null;
        }
        try {
            return dynamicRecordService.describe(moduleAlias);
        } catch (RuntimeException ignored) {
            if (module != null && module.getModuleKind() == ModuleKind.DYNAMIC) {
                throw ignored;
            }
            return null;
        }
    }

    private ModuleKind moduleKind(PlatformModule module,
                                  Optional<StaticModuleDefinition> staticDefinition,
                                  DynamicModuleDescriptor dynamicDescriptor) {
        if (module != null && module.getModuleKind() != null) {
            return module.getModuleKind();
        }
        if (dynamicDescriptor != null) {
            return ModuleKind.DYNAMIC;
        }
        if (staticDefinition.isPresent()) {
            return ModuleKind.STATIC;
        }
        return ModuleKind.STATIC;
    }

    private String title(PlatformModule module,
                         Optional<StaticModuleDefinition> staticDefinition,
                         DynamicModuleDescriptor dynamicDescriptor,
                         String moduleAlias) {
        if (module != null && module.getTitle() != null && !module.getTitle().isBlank()) {
            return module.getTitle();
        }
        if (dynamicDescriptor != null && dynamicDescriptor.title() != null && !dynamicDescriptor.title().isBlank()) {
            return dynamicDescriptor.title();
        }
        return staticDefinition.map(StaticModuleDefinition::title).orElse(moduleAlias);
    }

    private ModuleEntryType entryType(PlatformModule module, Optional<StaticModuleDefinition> staticDefinition) {
        if (module != null && module.getEntryType() != null) {
            return module.getEntryType();
        }
        return staticDefinition.map(StaticModuleDefinition::entryType).orElse(ModuleEntryType.MODULE);
    }

    private String entryRoute(PlatformModule module, Optional<StaticModuleDefinition> staticDefinition) {
        if (module != null && module.getEntryRoute() != null) {
            return module.getEntryRoute();
        }
        return staticDefinition.map(StaticModuleDefinition::entryRoute).orElse(null);
    }

    private String entryExternalUrl(PlatformModule module, Optional<StaticModuleDefinition> staticDefinition) {
        if (module != null && module.getEntryExternalUrl() != null) {
            return module.getEntryExternalUrl();
        }
        return staticDefinition.map(StaticModuleDefinition::entryExternalUrl).orElse(null);
    }

    private String mainEntityAlias(Optional<StaticModuleDefinition> staticDefinition,
                                   DynamicModuleDescriptor dynamicDescriptor) {
        if (dynamicDescriptor != null) {
            return dynamicDescriptor.mainEntityAlias();
        }
        return staticDefinition.flatMap(definition -> definition.entities().stream()
                .findFirst()
                .map(EntityDefinition::alias)).orElse(null);
    }

    private List<PlatformModuleRuntimeAction> actions(String moduleAlias,
                                                      ModuleKind moduleKind,
                                                      Optional<StaticModuleDefinition> staticDefinition,
                                                      DynamicModuleDescriptor dynamicDescriptor) {
        List<PlatformModuleAction> persisted = actionService.listByModuleAliases(List.of(moduleAlias)).stream()
                .toList();
        if (moduleKind == ModuleKind.DYNAMIC && dynamicDescriptor != null) {
            return dynamicActions(moduleAlias, dynamicDescriptor, persisted);
        }
        List<PlatformModuleAction> enabledPersisted = persisted.stream()
                .filter(action -> Boolean.TRUE.equals(action.getEnabled()))
                .toList();
        if (!enabledPersisted.isEmpty()) {
            return enabledPersisted.stream()
                    .map(action -> runtimeAction(action, policy(action)))
                    .toList();
        }
        return staticDefinition
                .map(definition -> definition.actions().stream()
                        .map(action -> runtimeAction(definition.moduleAlias(), action))
                        .toList())
                .orElse(List.of());
    }

    private List<PlatformModuleRuntimeAction> dynamicActions(String moduleAlias,
                                                             DynamicModuleDescriptor dynamicDescriptor,
                                                             List<PlatformModuleAction> persisted) {
        LinkedHashMap<String, PlatformModuleRuntimeAction> actions = new LinkedHashMap<>();
        dynamicDescriptor.actions().stream()
                .filter(DynamicActionDescriptor::enabled)
                .forEach(action -> actions.put(action.code(), runtimeAction(moduleAlias, action)));
        for (PlatformModuleAction action : persisted) {
            if (Boolean.FALSE.equals(action.getEnabled())) {
                actions.remove(action.getActionCode());
                continue;
            }
            actions.put(action.getActionCode(), runtimeAction(action, policy(action)));
        }
        return List.copyOf(actions.values());
    }

    private PlatformModuleRuntimeAction runtimeAction(PlatformModuleAction action, ActionExecutionPolicy policy) {
        Authorization authorization = authorize(action.getModuleAlias(), policy);
        return new PlatformModuleRuntimeAction(
                action.getActionCode(),
                policy.permissionActionCode(),
                action.getTitle(),
                policy.level(),
                action.getCategory(),
                policy.accessMode(),
                policy.actionAuth(),
                policy.dataAuth(),
                policy.defaultGrantPolicy(),
                action.getExecutorType(),
                action.getExecutorKey(),
                authorization.authorized(),
                authorization.decision()
        );
    }

    private PlatformModuleRuntimeAction runtimeAction(String moduleAlias, StaticModuleActionDefinition action) {
        ActionExecutionPolicy policy = policy(action);
        Authorization authorization = authorize(moduleAlias, policy);
        return new PlatformModuleRuntimeAction(
                action.actionCode(),
                policy.permissionActionCode(),
                action.title(),
                policy.level(),
                action.category(),
                policy.accessMode(),
                policy.actionAuth(),
                policy.dataAuth(),
                policy.defaultGrantPolicy(),
                action.executorType(),
                action.executorKey(),
                authorization.authorized(),
                authorization.decision()
        );
    }

    private PlatformModuleRuntimeAction runtimeAction(String moduleAlias, DynamicActionDescriptor action) {
        ActionExecutionPolicy policy = policy(action);
        Authorization authorization = authorize(moduleAlias, policy);
        return new PlatformModuleRuntimeAction(
                action.code(),
                policy.permissionActionCode(),
                action.title(),
                policy.level(),
                action.category(),
                policy.accessMode(),
                policy.actionAuth(),
                policy.dataAuth(),
                policy.defaultGrantPolicy(),
                action.executorType(),
                action.executorKey(),
                authorization.authorized(),
                authorization.decision()
        );
    }

    private ActionExecutionPolicy policy(PlatformModuleAction action) {
        String permissionActionCode = action.getPermissionActionCode() == null || action.getPermissionActionCode().isBlank()
                ? action.getActionCode()
                : action.getPermissionActionCode();
        return new ActionExecutionPolicy(
                action.getActionCode(),
                toPlatformLevel(action.getActionLevel()),
                toAccessMode(action.effectiveAccessMode()),
                action.effectiveActionAuth(),
                action.effectiveDataAuth(),
                action.effectiveDefaultGrantPolicy(),
                inheritActionCode(action.getActionCode(), permissionActionCode, action.effectiveActionAuth())
        );
    }

    private ActionExecutionPolicy policy(StaticModuleActionDefinition action) {
        return new ActionExecutionPolicy(
                action.actionCode(),
                toPlatformLevel(action.actionLevel()),
                toAccessMode(action.accessMode()),
                action.actionAuth(),
                action.dataAuth(),
                action.defaultGrantPolicy(),
                inheritActionCode(action.actionCode(), action.permissionActionCode(), action.actionAuth())
        );
    }

    private ActionExecutionPolicy policy(DynamicActionDescriptor action) {
        return new ActionExecutionPolicy(
                action.code(),
                toPlatformLevel(action.actionLevel()),
                toAccessMode(action.accessMode()),
                action.actionAuth(),
                action.dataAuth(),
                action.defaultGrantPolicy(),
                action.authInheritActionCode()
        );
    }

    private ActionExecutionPolicy policy(PlatformModuleRuntimeAction action) {
        return new ActionExecutionPolicy(
                action.actionCode(),
                action.actionLevel(),
                action.accessMode(),
                action.actionAuth(),
                action.dataAuth(),
                action.defaultGrantPolicy(),
                inheritActionCode(action.actionCode(), action.permissionActionCode(), action.actionAuth())
        );
    }

    private Authorization authorize(String moduleAlias, ActionExecutionPolicy policy) {
        try {
            ActionAuthorizationResult result = actionExecutionPolicyService.authorize(
                    ActionExecutionContext.ofPolicy(moduleAlias, policy, Set.of(), CurrentUserContext.currentUser()));
            return new Authorization(true, result.decision());
        } catch (AuthenticationRequiredException ignored) {
            return new Authorization(false, DECISION_AUTHENTICATION_REQUIRED);
        } catch (PlatformAccessDeniedException ignored) {
            return new Authorization(false, DECISION_ACCESS_DENIED);
        }
    }

    private Set<EntityCapability> capabilities(Optional<StaticModuleDefinition> staticDefinition,
                                               DynamicModuleDescriptor dynamicDescriptor,
                                               List<PlatformModuleRuntimeAction> actions) {
        EnumSet<EntityCapability> capabilities = baselineCapabilities();
        String staticMainEntityAlias = mainEntityAlias(staticDefinition, null);
        staticDefinition.ifPresent(definition -> {
            capabilities.addAll(definition.capabilities());
            definition.entities().stream()
                    .filter(entity -> entity.alias().equals(staticMainEntityAlias))
                    .map(EntityDefinition::capabilities)
                    .forEach(capabilities::addAll);
        });
        if (dynamicDescriptor != null) {
            for (DynamicEntityDescriptor entity : dynamicDescriptor.entities()) {
                if (!entity.entityAlias().equals(dynamicDescriptor.mainEntityAlias())) {
                    continue;
                }
                for (String capability : entity.capabilities()) {
                    capabilities.add(EntityCapability.valueOf(capability));
                }
            }
            String mainEntityAlias = dynamicDescriptor.mainEntityAlias();
            if (dynamicDescriptor.relations().stream()
                    .anyMatch(relation -> relation.parentEntityAlias().equals(mainEntityAlias))) {
                capabilities.add(EntityCapability.CHILD_RELATION);
            }
            if (dynamicDescriptor.references().stream()
                    .anyMatch(reference -> reference.sourceEntityAlias().equals(mainEntityAlias))) {
                capabilities.add(EntityCapability.REFERENCE);
                capabilities.add(EntityCapability.REFERENCE_DEPENDENCY);
            }
        }
        actions.stream()
                .map(PlatformModuleRuntimeAction::actionCode)
                .forEach(actionCode -> inferCapabilities(capabilities, actionCode));
        normalizeCapabilities(capabilities);
        return Set.copyOf(capabilities);
    }

    private Set<String> abilityCodes(Set<EntityCapability> capabilities) {
        LinkedHashSet<String> abilities = new LinkedHashSet<>();
        for (EntityCapability capability : EntityCapability.values()) {
            if (capabilities.contains(capability)) {
                abilities.add(abilityCode(capability));
            }
        }
        return Set.copyOf(abilities);
    }

    private String abilityCode(EntityCapability capability) {
        String[] parts = capability.name().toLowerCase().split("_");
        StringBuilder code = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                code.append(Character.toUpperCase(parts[i].charAt(0)));
                code.append(parts[i].substring(1));
            }
        }
        return code.toString();
    }

    private void inferCapabilities(EnumSet<EntityCapability> capabilities, String actionCode) {
        PlatformAction.fromCode(actionCode).ifPresent(action -> {
            switch (action) {
                case CREATE, VIEW, UPDATE, DELETE, BATCH_DELETE, QUERY -> capabilities.add(EntityCapability.CRUD);
                case TREE -> capabilities.add(EntityCapability.TREE);
                case SORT -> capabilities.add(EntityCapability.SORT);
                case REFERENCE -> capabilities.add(EntityCapability.REFERENCE);
                case ENABLE, DISABLE -> capabilities.add(EntityCapability.ENABLE);
                case RECYCLE_BIN_QUERY, RECYCLE_BIN_RESTORE, RECYCLE_BIN_PURGE ->
                        capabilities.add(EntityCapability.RECYCLE_BIN);
                case IMPORT, EXPORT -> capabilities.add(EntityCapability.EXCHANGE);
                default -> {
                }
            }
        });
    }

    private void normalizeCapabilities(EnumSet<EntityCapability> capabilities) {
        capabilities.addAll(baselineCapabilities());
        if (capabilities.contains(EntityCapability.TREE)) {
            capabilities.add(EntityCapability.SORT);
        }
        if (capabilities.contains(EntityCapability.APPROVAL)) {
            capabilities.add(EntityCapability.WORKFLOW);
        }
    }

    private EnumSet<EntityCapability> baselineCapabilities() {
        EnumSet<EntityCapability> capabilities = EnumSet.noneOf(EntityCapability.class);
        for (EntityCapability capability : EntityCapability.values()) {
            if (capability.isBaseline()) {
                capabilities.add(capability);
            }
        }
        return capabilities;
    }

    static String inheritActionCode(String actionCode, String permissionActionCode, boolean actionAuth) {
        if (!actionAuth || permissionActionCode == null || permissionActionCode.isBlank()
                || actionCode.equals(permissionActionCode)) {
            return null;
        }
        return permissionActionCode;
    }

    private PlatformActionLevel toPlatformLevel(EntityActionLevel level) {
        if (level == null) {
            return PlatformActionLevel.ANY;
        }
        return switch (level) {
            case LIST -> PlatformActionLevel.LIST;
            case RECORD -> PlatformActionLevel.RECORD;
            case BATCH -> PlatformActionLevel.BATCH;
            case ANY -> PlatformActionLevel.ANY;
        };
    }

    private ActionAccessMode toAccessMode(EntityActionAccessMode accessMode) {
        if (accessMode == null) {
            return ActionAccessMode.AUTH_REQUIRED;
        }
        return ActionAccessMode.valueOf(accessMode.name());
    }

    private record Authorization(boolean authorized, String decision) {
    }
}
