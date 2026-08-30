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
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceFieldPolicy;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicQuerySchemas;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
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
import net.ximatai.muyun.spring.platform.ui.PlatformPublishedPageComposition;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;
import net.ximatai.muyun.spring.platform.ui.PageNavigatorSourceCapabilityResolver;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
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
    private final List<FileReferenceFieldPolicy> fileReferenceFieldPolicies;
    private final PageNavigatorResolver pageNavigatorResolver;
    private final PageNavigatorSourceCapabilityResolver navigatorSourceCapabilityResolver;
    private final DynamicPublishedPageDefinitionResolver publishedPageDefinitionResolver;

    @Autowired
    public PlatformModuleRuntimeContextService(PlatformModuleService moduleService,
                                               PlatformModuleActionService actionService,
                                               StaticModuleDefinitionCatalog staticModuleCatalog,
                                               ObjectProvider<DynamicRecordService> dynamicRecordService,
                                               ObjectProvider<ActionExecutionPolicyService> actionExecutionPolicyService,
                                               ObjectProvider<FileReferenceFieldPolicy> fileReferenceFieldPolicies,
                                               ObjectProvider<PageNavigatorResolver> pageNavigatorResolver,
                                               ObjectProvider<PageNavigatorSourceCapabilityResolver> navigatorSourceCapabilityResolver,
                                               ObjectProvider<DynamicPublishedPageDefinitionResolver> publishedPageDefinitionResolver) {
        this(moduleService, actionService, staticModuleCatalog,
                dynamicRecordService == null ? null : dynamicRecordService.getIfAvailable(),
                null, null,
                actionExecutionPolicyService == null
                        ? new AllowAllActionExecutionPolicyService()
                        : actionExecutionPolicyService.getIfAvailable(AllowAllActionExecutionPolicyService::new),
                fileReferenceFieldPolicies == null ? List.of() : fileReferenceFieldPolicies.orderedStream().toList(),
                new CompositePageNavigatorResolver(pageNavigatorResolver == null ? List.of()
                        : pageNavigatorResolver.orderedStream().toList()),
                navigatorSourceCapabilityResolver == null ? null : navigatorSourceCapabilityResolver.getIfAvailable(),
                null, null, null, null,
                publishedPageDefinitionResolver == null ? null : publishedPageDefinitionResolver.getIfAvailable());
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
                navigatorSourceCapabilityResolver, null, null, null, null);
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
        this(moduleService, actionService, staticModuleCatalog, dynamicRecordService, pageConfigSnapshotService,
                pageBootstrapService, actionExecutionPolicyService, fileReferenceFieldPolicies, pageNavigatorResolver,
                navigatorSourceCapabilityResolver, fieldUiControlService, fieldUiControlPropertyService,
                fieldUiControlBindingService, null);
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
                                        FieldUiControlBindingService fieldUiControlBindingService,
                                        ModuleMetadataFieldService moduleMetadataFieldService) {
        this(moduleService, actionService, staticModuleCatalog, dynamicRecordService, pageConfigSnapshotService,
                pageBootstrapService, actionExecutionPolicyService, fileReferenceFieldPolicies, pageNavigatorResolver,
                navigatorSourceCapabilityResolver, fieldUiControlService, fieldUiControlPropertyService,
                fieldUiControlBindingService, moduleMetadataFieldService, null);
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
                                        FieldUiControlBindingService fieldUiControlBindingService,
                                        ModuleMetadataFieldService moduleMetadataFieldService,
                                        DynamicPublishedPageDefinitionResolver publishedPageDefinitionResolver) {
        this.moduleService = moduleService;
        this.actionService = actionService;
        this.staticModuleCatalog = staticModuleCatalog;
        this.dynamicRecordService = dynamicRecordService;
        this.actionExecutionPolicyService = actionExecutionPolicyService == null
                ? new AllowAllActionExecutionPolicyService()
                : actionExecutionPolicyService;
        this.fileReferenceFieldPolicies = fileReferenceFieldPolicies == null ? List.of() : List.copyOf(fileReferenceFieldPolicies);
        this.pageNavigatorResolver = pageNavigatorResolver == null
                ? new DeclaredPageNavigatorResolver()
                : pageNavigatorResolver;
        this.navigatorSourceCapabilityResolver = navigatorSourceCapabilityResolver;
        this.publishedPageDefinitionResolver = publishedPageDefinitionResolver;
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
     * Compiles a validated, transient dynamic page definition without installing it into the
     * runtime. This is deliberately separate from {@link #context(String)} so preview cannot
     * affect published-page selection, cached execution plans, or descriptor visibility.
     */
    public ResolvedModuleUiDescriptor previewDynamicPageDescriptor(String moduleAlias,
                                                                    ModuleUiDefinition definition) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        PlatformModule module = moduleService.resolveVisibleModule(validModuleAlias);
        DynamicModuleDescriptor dynamicDescriptor = dynamicDescriptor(module, validModuleAlias);
        if (module == null || module.getModuleKind() != ModuleKind.DYNAMIC || dynamicDescriptor == null) {
            throw new PlatformException(PlatformErrorCodes.RESOURCE_NOT_FOUND, 404,
                    "dynamic module runtime context not found: " + validModuleAlias);
        }
        if (definition == null || !validModuleAlias.equals(definition.moduleAlias())) {
            throw new IllegalArgumentException("preview page definition must belong to dynamic module: " + validModuleAlias);
        }
        return compileDynamicPageDescriptor(validModuleAlias, title(module, Optional.empty(), dynamicDescriptor,
                validModuleAlias), dynamicDescriptor, definition);
    }

    /** Returns installed dynamic main-field facts used by page-tree validation and descriptor labels. */
    public java.util.Map<String, String> dynamicMainFieldTitles(String moduleAlias) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        PlatformModule module = moduleService.resolveVisibleModule(validModuleAlias);
        DynamicModuleDescriptor dynamicDescriptor = dynamicDescriptor(module, validModuleAlias);
        if (module == null || module.getModuleKind() != ModuleKind.DYNAMIC || dynamicDescriptor == null) {
            throw new PlatformException(PlatformErrorCodes.RESOURCE_NOT_FOUND, 404,
                    "dynamic module runtime context not found: " + validModuleAlias);
        }
        return dynamicDescriptor.entities().stream()
                .filter(entity -> dynamicDescriptor.mainEntityAlias().equals(entity.entityAlias()))
                .findFirst().map(entity -> entity.fields().stream().collect(java.util.stream.Collectors.toMap(
                        net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor::fieldName,
                        net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor::title,
                        (left, right) -> left, LinkedHashMap::new)))
                .orElseThrow(() -> new IllegalStateException("dynamic runtime has no main entity: " + validModuleAlias));
    }

    /** Association views are dynamic runtime facts; page revisions may only select from this catalog. */
    public java.util.Map<String, net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor>
    dynamicAssociationViews(String moduleAlias) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        PlatformModule module = moduleService.resolveVisibleModule(validModuleAlias);
        DynamicModuleDescriptor dynamicDescriptor = dynamicDescriptor(module, validModuleAlias);
        if (module == null || module.getModuleKind() != ModuleKind.DYNAMIC || dynamicDescriptor == null) {
            throw new PlatformException(PlatformErrorCodes.RESOURCE_NOT_FOUND, 404,
                    "dynamic module runtime context not found: " + validModuleAlias);
        }
        return dynamicDescriptor.associationViews().stream().collect(java.util.stream.Collectors.toMap(
                net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor::code,
                java.util.function.Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    /**
     * Compiles the installed dynamic runtime and the published WEB UI snapshot into immutable
     * server execution facts.  The plan intentionally carries no request or SQL state.
     */
    public Optional<ModuleExecutionPlan> dynamicExecutionPlan(String moduleAlias) {
        if (dynamicRecordService == null) {
            return Optional.empty();
        }
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        DynamicModuleDescriptor dynamicDescriptor = dynamicRecordService.describe(validAlias);
        Optional<DynamicPublishedPageDefinitionResolver.ResolvedPublishedPage> publishedPage =
                publishedPageDefinitionResolver == null ? Optional.empty()
                        : publishedPageDefinitionResolver.resolveWebGlobal(dynamicDescriptor);
        if (publishedPage.isPresent()) {
            PlatformModuleRuntimeContext runtimeContext = context(validAlias);
            if (runtimeContext.moduleKind() != ModuleKind.DYNAMIC || runtimeContext.uiDescriptor() == null) {
                return Optional.empty();
            }
            return Optional.of(compiledPublishedPageExecutionPlan(validAlias, dynamicDescriptor, runtimeContext,
                    publishedPage.get()));
        }
        return Optional.empty();
    }

    private ModuleExecutionPlan compiledPublishedPageExecutionPlan(
            String moduleAlias,
            DynamicModuleDescriptor dynamicDescriptor,
            PlatformModuleRuntimeContext runtimeContext,
            DynamicPublishedPageDefinitionResolver.ResolvedPublishedPage publishedPage) {
        DynamicEntityDescriptor mainEntity = dynamicDescriptor.entities().stream()
                .filter(entity -> dynamicDescriptor.mainEntityAlias().equals(entity.entityAlias()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "dynamic runtime has no main entity: " + moduleAlias));
        ResolvedModuleUiDescriptor descriptor = runtimeContext.uiDescriptor();
        List<ResolvedViewFieldDescriptor> listViewFields = descriptor.page() == null || descriptor.page().list() == null
                || descriptor.page().list().fields() == null ? List.of() : descriptor.page().list().fields().fields();
        List<ResolvedViewFieldDescriptor> formViewFields = descriptor.page() == null || descriptor.page().detail() == null
                || descriptor.page().detail().editor() == null ? List.of() : descriptor.page().detail().editor().fields();
        List<String> listFields = listViewFields.stream()
                .filter(field -> field.fieldRef().relationCode() == null)
                .filter(field -> !Boolean.FALSE.equals(field.visible().constant()))
                .map(field -> field.fieldRef().fieldName()).distinct().toList();
        List<String> quickSearchFields = listFields.stream().filter(field -> isSearchableText(mainEntity, field)).toList();
        List<PageContextBindingDefinition> bindings = descriptor.page() == null || descriptor.page().navigator() == null
                ? List.of() : descriptor.page().navigator().contextBindings().stream()
                .map(binding -> new PageContextBindingDefinition(binding.source(), binding.sourceKey(), binding.target(),
                        binding.targetKey(), binding.targetNavigatorLevelKey(), binding.targetPickerFieldKey(),
                        binding.navigatorListQueryMode()))
                .toList();
        List<String> externalCriteriaKeys = bindings.stream()
                .filter(binding -> binding.target() == PageContextTarget.LIST_QUERY)
                .filter(binding -> binding.source() != PageContextSource.SESSION)
                .map(PageContextBindingDefinition::targetKey).distinct().toList();
        QuerySchema querySchema = DynamicQuerySchemas.from(moduleAlias, mainEntity, quickSearchFields, externalCriteriaKeys);
        List<ModuleMutationFieldValidation> validations = formViewFields.stream()
                .filter(field -> field.fieldRef().relationCode() == null)
                .map(field -> new ModuleMutationFieldValidation(null, field.fieldRef().fieldName(),
                        Boolean.TRUE.equals(field.readOnly().constant()),
                        Boolean.TRUE.equals(field.required().constant()) || isRequired(mainEntity, field.fieldRef().fieldName())))
                .toList();
        String versionKey = "dynamic-runtime-" + dynamicRecordService.runtimeRevision(moduleAlias)
                + "-page-" + publishedPage.revision().getId()
                + "-r" + publishedPage.revision().getRevisionNo();
        return new ModuleExecutionPlan(moduleAlias, versionKey, descriptor,
                new ResolvedModuleReadModel(moduleAlias, runtimeContext.mainEntityAlias(), listFields.stream()
                        .map(field -> new ResolvedModuleReadField(runtimeContext.mainEntityAlias(), null, field, false))
                        .toList()),
                bindings, QueryDescriptor.builder(moduleAlias).build(), querySchema, List.of(), List.of(), null, null,
                List.of(), bindings.stream().filter(binding -> binding.target() == PageContextTarget.MUTATION_CONSTRAINT)
                        .toList(), validations, List.of(),
                runtimeContext.capabilities().contains(EntityCapability.DATA_SCOPE));
    }

    private static boolean isSearchableText(DynamicEntityDescriptor entity, String fieldName) {
        return entity.fields().stream().filter(field -> fieldName.equals(field.fieldName()))
                .anyMatch(field -> field.type() == net.ximatai.muyun.spring.dynamic.metadata.FieldType.STRING
                        || field.type() == net.ximatai.muyun.spring.dynamic.metadata.FieldType.TEXT);
    }

    private static boolean isRequired(DynamicEntityDescriptor entity, String fieldName) {
        return entity.fields().stream().filter(field -> fieldName.equals(field.fieldName()))
                .anyMatch(net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor::required);
    }

    /** Returns the startup-visible dynamic modules so their published plans are installed eagerly. */
    public List<String> dynamicModuleAliases() {
        return moduleService.listVisibleModules().stream()
                .filter(module -> module.getModuleKind() == ModuleKind.DYNAMIC)
                .map(PlatformModule::getAlias)
                .filter(this::hasInstalledDynamicRuntime)
                .toList();
    }

    /** Keeps a stale platform-module row from preventing the whole runtime from starting. */
    private boolean hasInstalledDynamicRuntime(String moduleAlias) {
        if (dynamicRecordService == null) return false;
        try {
            dynamicRecordService.describe(moduleAlias);
            return true;
        } catch (ModuleDefinitionException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("unknown module alias:")) {
                return false;
            }
            throw exception;
        }
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
        Optional<ModuleUiDefinition> publishedDefinition = publishedPageDefinitionResolver == null ? Optional.empty()
                : publishedPageDefinitionResolver.resolveWebGlobal(dynamicDescriptor)
                        .map(DynamicPublishedPageDefinitionResolver.ResolvedPublishedPage::definition);
        if (publishedDefinition.isPresent()) {
            return compileDynamicPageDescriptor(moduleAlias, title, dynamicDescriptor, publishedDefinition.get());
        }
        return null;
    }

    private ResolvedModuleUiDescriptor compileDynamicPageDescriptor(String moduleAlias,
                                                                     String title,
                                                                     DynamicModuleDescriptor dynamicDescriptor,
                                                                     ModuleUiDefinition definition) {
        java.util.Map<ViewFieldRef, FieldValueType> fieldTypes = dynamicMainFieldTypes(dynamicDescriptor);
        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(definition, ModuleKind.DYNAMIC, title,
                dynamicOptionFields(dynamicDescriptor), dynamicReferenceFields(dynamicDescriptor),
                dynamicRecordLabelField(dynamicDescriptor), fieldTypes, FieldControlDescriptorCatalog.standard());
        return descriptor.withPage(resolvePage(moduleAlias, ModuleKind.DYNAMIC, descriptor.page()))
                .withDetailRelations(dynamicDetailRelations(moduleAlias, dynamicDescriptor, definition.detailRelations()));
    }

    private List<net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor> dynamicDetailRelations(
            String moduleAlias, DynamicModuleDescriptor sourceModule, List<PageDetailRelationDefinition> configured) {
        if (configured == null || configured.isEmpty()) return List.of();
        java.util.Map<String, net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor> views =
                sourceModule.associationViews().stream().collect(java.util.stream.Collectors.toMap(
                        net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor::code,
                        java.util.function.Function.identity(), (left, right) -> left));
        return configured.stream().map(selection -> {
            var view = views.get(selection.code());
            if (view == null) {
                throw new IllegalArgumentException("page revision references an unknown dynamic association view: "
                        + selection.code());
            }
            if (view.relationCode() == null || view.relationCode().isBlank()) {
                throw new IllegalArgumentException("page revision association is not a child relation: "
                        + selection.code());
            }
            DynamicModuleDescriptor targetModule = moduleAlias.equals(view.targetModuleAlias())
                    ? sourceModule : dynamicRecordService.describe(view.targetModuleAlias());
            DynamicEntityDescriptor target = targetModule.entities().stream()
                    .filter(entity -> view.targetEntityAlias().equals(entity.entityAlias()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "dynamic association target entity is unavailable: " + view.targetModuleAlias()
                                    + "." + view.targetEntityAlias()));
            java.util.Map<String, DynamicFieldDescriptor> targetFields = target.fields().stream()
                    .collect(java.util.stream.Collectors.toMap(DynamicFieldDescriptor::fieldName,
                            java.util.function.Function.identity(), (left, ignored) -> left,
                            java.util.LinkedHashMap::new));
            List<DynamicFieldDescriptor> selectedFields = selection.listFields().isEmpty()
                    ? List.of()
                    : selection.listFields().stream().map(fieldName -> {
                        DynamicFieldDescriptor field = targetFields.get(fieldName);
                        if (field == null) {
                            throw new IllegalArgumentException("page revision relation field is unavailable: "
                                    + selection.code() + "." + fieldName);
                        }
                        return field;
                    }).toList();
            var projection = new net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationListProjection(null,
                    selectedFields.stream().map(field ->
                            new net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationListField(
                                    field.fieldName(), field.title(),
                                    field.storageForm() == null ? null : field.storageForm().name(), null,
                                    null, null, null)).toList());
            var query = new net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationQueryContract(
                    "/" + moduleAlias + "/view/{id}/associations/" + view.code() + "/query", null, null,
                    true, view.queryable(), projection,
                    DynamicQuerySchemas.from(view.targetModuleAlias(), target, List.of()), false, null);
            return new net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor(
                    selection.code(), selection.title(), true, moduleAlias, view.sourceEntityAlias(),
                    view.targetModuleAlias(), view.targetEntityAlias(), view.relationCode(), query, null, null,
                    net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationEditing.DEFAULT, true);
        }).toList();
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
                        level.sourceModuleAlias(), level.title(), level.searchPlaceholder(), level.secondaryField(), level.management(),
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

    private java.util.Map<ViewFieldRef, FieldValueType> dynamicMainFieldTypes(DynamicModuleDescriptor dynamicDescriptor) {
        if (dynamicDescriptor == null) return java.util.Map.of();
        return dynamicDescriptor.entities().stream()
                .filter(entity -> dynamicDescriptor.mainEntityAlias().equals(entity.entityAlias()))
                .findFirst().map(entity -> entity.fields().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        field -> ViewFieldRef.main(field.fieldName()), field -> FieldValueType.from(field.type()),
                        (left, right) -> left))).orElseGet(java.util.Map::of);
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
                .map(entity -> ReferenceFieldDescriptorCompiler.withTreeParentReference(
                        dynamicDescriptor.moduleAlias(),
                        entity.capabilities().contains(EntityCapability.TREE.name()),
                        entity.fields().stream()
                                .filter(field -> field.reference() != null)
                                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                        field -> field.fieldName(),
                                        field -> new ResolvedReferenceFieldDescriptor(
                                                field.reference().targetModuleAlias(), field.reference().cardinality(),
                                                field.reference().projections().stream()
                                                        .filter(projection -> "title".equals(projection.targetField()))
                                                        .map(net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceProjectionDescriptor::outputField)
                                                        .findFirst().orElse(null),
                                                referencePickerMode(field.reference().targetModuleAlias()),
                                                ReferenceCandidateDelivery.SOURCE_FIELD,
                                                "/" + dynamicDescriptor.moduleAlias() + "/references/"
                                                        + field.fieldName() + "/resolve",
                                                field.reference().candidateDependencies(),
                                                field.reference().plusFields().stream()
                                                        .map(ResolvedReferenceSelectionProjectionDescriptor::new).toList()),
                                        (left, right) -> left)),
                        this::referencePickerMode))
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
            if (CapabilityModuleRegistry.defaultRegistry().actionOwner(action)
                    .map(contribution -> capabilities.add(contribution.capability()))
                    .orElse(false)) {
                return;
            }
            switch (action) {
                case CREATE, VIEW, UPDATE, DELETE, BATCH_DELETE, QUERY -> capabilities.add(EntityCapability.CRUD);
                case TREE -> capabilities.add(EntityCapability.TREE);
                case REFERENCE -> capabilities.add(EntityCapability.REFERENCE);
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
