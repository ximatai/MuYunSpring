package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PlatformPageConfigPublishService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final java.util.Set<String> SUMMARY_AGGREGATES = java.util.Set.of(
            "sum", "avg", "max", "min", "count", "distinctCount");

    private final PlatformUiSetService uiSetService;
    private final PlatformUiConfigService uiConfigService;
    private final PlatformUiConfigFieldService uiConfigFieldService;
    private final PlatformQueryTemplateService queryTemplateService;
    private final PlatformQueryItemService queryItemService;
    private final DynamicRecordService recordService;
    private final PageNavigatorSourceCapabilityResolver navigatorSourceCapabilityResolver;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final PublishedPageExecutionCoordinator pageExecutionCoordinator;

    public PlatformPageConfigPublishService(PlatformUiSetService uiSetService,
                                            PlatformUiConfigService uiConfigService,
                                            PlatformUiConfigFieldService uiConfigFieldService,
                                            PlatformQueryTemplateService queryTemplateService,
                                            PlatformQueryItemService queryItemService) {
        this(uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService, null,
                (PageNavigatorSourceCapabilityResolver) null, RuntimeEventPublisher.noop(),
                PublishedPageExecutionCoordinator.noop());
    }

    @Autowired
    public PlatformPageConfigPublishService(PlatformUiSetService uiSetService,
                                            PlatformUiConfigService uiConfigService,
                                            PlatformUiConfigFieldService uiConfigFieldService,
                                            PlatformQueryTemplateService queryTemplateService,
                                            PlatformQueryItemService queryItemService,
                                            DynamicRecordService recordService,
                                            ObjectProvider<PageNavigatorSourceCapabilityResolver> navigatorSourceCapabilityResolver,
                                            ObjectProvider<RuntimeEventPublisher> runtimeEventPublisher,
                                            ObjectProvider<PublishedPageExecutionCoordinator> pageExecutionCoordinator) {
        this(uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService, recordService,
                navigatorSourceCapabilityResolver == null ? null : navigatorSourceCapabilityResolver.getIfAvailable(),
                runtimeEventPublisher == null ? RuntimeEventPublisher.noop()
                        : runtimeEventPublisher.getIfAvailable(RuntimeEventPublisher::noop),
                pageExecutionCoordinator == null ? PublishedPageExecutionCoordinator.noop()
                        : pageExecutionCoordinator.getIfAvailable(PublishedPageExecutionCoordinator::noop));
    }

    public PlatformPageConfigPublishService(PlatformUiSetService uiSetService,
                                            PlatformUiConfigService uiConfigService,
                                            PlatformUiConfigFieldService uiConfigFieldService,
                                            PlatformQueryTemplateService queryTemplateService,
                                            PlatformQueryItemService queryItemService,
                                            DynamicRecordService recordService) {
        this(uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService, recordService,
                (PageNavigatorSourceCapabilityResolver) null, RuntimeEventPublisher.noop(),
                PublishedPageExecutionCoordinator.noop());
    }

    public PlatformPageConfigPublishService(PlatformUiSetService uiSetService,
                                            PlatformUiConfigService uiConfigService,
                                            PlatformUiConfigFieldService uiConfigFieldService,
                                            PlatformQueryTemplateService queryTemplateService,
                                            PlatformQueryItemService queryItemService,
                                            DynamicRecordService recordService,
                                            PageNavigatorSourceCapabilityResolver navigatorSourceCapabilityResolver) {
        this(uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService, recordService,
                navigatorSourceCapabilityResolver, RuntimeEventPublisher.noop(), PublishedPageExecutionCoordinator.noop());
    }

    public PlatformPageConfigPublishService(PlatformUiSetService uiSetService,
                                            PlatformUiConfigService uiConfigService,
                                            PlatformUiConfigFieldService uiConfigFieldService,
                                            PlatformQueryTemplateService queryTemplateService,
                                            PlatformQueryItemService queryItemService,
                                            DynamicRecordService recordService,
                                            PageNavigatorSourceCapabilityResolver navigatorSourceCapabilityResolver,
                                            RuntimeEventPublisher runtimeEventPublisher) {
        this(uiSetService, uiConfigService, uiConfigFieldService, queryTemplateService, queryItemService, recordService,
                navigatorSourceCapabilityResolver, runtimeEventPublisher, PublishedPageExecutionCoordinator.noop());
    }

    public PlatformPageConfigPublishService(PlatformUiSetService uiSetService,
                                            PlatformUiConfigService uiConfigService,
                                            PlatformUiConfigFieldService uiConfigFieldService,
                                            PlatformQueryTemplateService queryTemplateService,
                                            PlatformQueryItemService queryItemService,
                                            DynamicRecordService recordService,
                                            PageNavigatorSourceCapabilityResolver navigatorSourceCapabilityResolver,
                                            RuntimeEventPublisher runtimeEventPublisher,
                                            PublishedPageExecutionCoordinator pageExecutionCoordinator) {
        this.uiSetService = uiSetService;
        this.uiConfigService = uiConfigService;
        this.uiConfigFieldService = uiConfigFieldService;
        this.queryTemplateService = queryTemplateService;
        this.queryItemService = queryItemService;
        this.recordService = recordService;
        this.navigatorSourceCapabilityResolver = navigatorSourceCapabilityResolver;
        this.runtimeEventPublisher = runtimeEventPublisher == null ? RuntimeEventPublisher.noop() : runtimeEventPublisher;
        this.pageExecutionCoordinator = pageExecutionCoordinator == null
                ? PublishedPageExecutionCoordinator.noop() : pageExecutionCoordinator;
    }

    @Transactional
    public void publishUiConfig(String uiConfigId) {
        PlatformUiConfig uiConfig = validateUiConfigPublishable(uiConfigId);
        try (PlatformPageConfigPublishContext.Scope ignored = PlatformPageConfigPublishContext.open()) {
            uiConfigService.update(copyForPublish(uiConfig, Boolean.TRUE));
        }
        publishedConfigurationChanged(uiSetService.requireUiSet(uiConfig.getUiSetId()).getModuleAlias());
    }

    @Transactional
    public void unpublishUiConfig(String uiConfigId) {
        PlatformUiConfig uiConfig = uiConfigService.requireUiConfig(uiConfigId);
        try (PlatformPageConfigPublishContext.Scope ignored = PlatformPageConfigPublishContext.open()) {
            uiConfigService.update(copyForPublish(uiConfig, Boolean.FALSE));
        }
        publishedConfigurationChanged(uiSetService.requireUiSet(uiConfig.getUiSetId()).getModuleAlias());
    }

    public PlatformUiConfig validateUiConfigPublishable(String uiConfigId) {
        PlatformUiConfig uiConfig = uiConfigService.requireUiConfig(uiConfigId);
        PlatformUiSet uiSet = uiSetService.requireUiSet(uiConfig.getUiSetId());
        if (!Boolean.TRUE.equals(uiSet.getEnabled()) || !Boolean.TRUE.equals(uiConfig.getEnabled())) {
            throw BusinessExceptions.warning("platform.ui-config.publish-disabled",
                    "UI config publish requires enabled set and config: " + uiConfigId);
        }
        uiConfigFieldService.validateUiConfigFields(uiConfig.getId());
        List<PlatformUiConfigField> fields = uiConfigFieldService.listByUiConfigIds(List.of(uiConfig.getId()));
        boolean hasVisibleField = fields.stream().anyMatch(field -> Boolean.TRUE.equals(field.getVisible()));
        if (!hasVisibleField) {
            throw BusinessExceptions.warning("platform.ui-config.publish-no-visible-field",
                    "UI config publish requires at least one visible field: " + uiConfigId);
        }
        JsonNode layout = validateLayoutJson(uiSet.getModuleAlias(), uiConfig);
        validatePageNavigator(uiSet, uiConfig);
        validatePageCapabilityContract(uiSet, uiConfig, layout);
        return uiConfig;
    }

    private void validatePageNavigator(PlatformUiSet uiSet, PlatformUiConfig uiConfig) {
        try {
            // Decode the navigator even when the optional dynamic runtime is absent: unsupported
            // source/target combinations are configuration facts, not runtime-only validation.
            PlatformPageLayoutNavigator.navigator(uiConfig);
            if (recordService == null) return;
            PlatformPublishedPageComposition composition = publishedCompositionIncluding(uiSet, uiConfig);
            validatePageNavigator(uiSet, uiConfig, composition);
            if ((uiSet.getSetType() == PlatformUiSetType.LIST || uiSet.getSetType() == PlatformUiSetType.FORM)
                    && composition.listConfig() != null && !composition.listConfig().getId().equals(uiConfig.getId())) {
                PlatformUiSet listSet = publishedUiSets(uiSet.getModuleAlias()).stream()
                        .filter(candidate -> candidate.getId().equals(composition.listConfig().getUiSetId()))
                        .findFirst()
                        .orElseThrow(() -> new PlatformException("Published list UI set is unavailable: "
                                + composition.listConfig().getUiSetId()));
                validatePageNavigator(listSet, composition.listConfig(), composition);
            }
        } catch (IllegalArgumentException exception) {
            throw new PlatformException("UI config navigator layout is invalid: " + uiConfig.getId(), exception);
        }
    }

    private void validatePageNavigator(PlatformUiSet uiSet,
                                       PlatformUiConfig uiConfig,
                                       PlatformPublishedPageComposition composition) {
        PlatformPageNavigatorLayout navigator = PlatformPageLayoutNavigator.navigator(uiConfig);
        if (navigator == null) return;
        validateNavigatorSourceCapabilities(uiSet, uiConfig, navigator);
            DynamicModuleDescriptor module = recordService.describe(uiSet.getModuleAlias());
            DynamicEntityDescriptor entity = module.entities().stream()
                    .filter(candidate -> candidate.entityAlias().equals(module.mainEntityAlias()))
                    .findFirst()
                    .orElseThrow(() -> new PlatformException("Navigator main entity is unavailable: " + uiSet.getModuleAlias()));
            Set<String> editorFieldNames = publishedFormEditorFieldNames(composition);
            for (PlatformPageContextBinding binding : navigator.contextBindings()) {
                if (!"NAVIGATOR".equals(binding.source()) || (!"LIST_QUERY".equals(binding.target())
                        && !"PICKER_QUERY".equals(binding.target()))) {
                    continue;
                }
                PlatformPageNavigatorLevel level = navigator.levels().stream()
                        .filter(candidate -> candidate.key().equals(binding.sourceKey()))
                        .findFirst().orElseThrow();
                DynamicFieldDescriptor field = entity.fields().stream()
                            .filter(candidate -> candidate.fieldName().equals(binding.targetKey()))
                            .findFirst().orElseThrow(() -> new PlatformException("Navigator query field is unavailable: "
                                    + uiSet.getModuleAlias() + "." + binding.targetKey()));
                DynamicReferenceDescriptor reference = field.reference();
                if ("PICKER_QUERY".equals(binding.target())) {
                    if (!editorFieldNames.contains(binding.targetPickerFieldKey())) {
                        throw new PlatformException("Picker query target must be declared by a published form editor: "
                                + uiSet.getModuleAlias() + "." + binding.targetPickerFieldKey());
                    }
                    DynamicFieldDescriptor pickerField = entity.fields().stream()
                            .filter(candidate -> candidate.fieldName().equals(binding.targetPickerFieldKey()))
                            .findFirst().orElseThrow(() -> new PlatformException("Picker query target is unavailable: "
                                    + uiSet.getModuleAlias() + "." + binding.targetPickerFieldKey()));
                    if (!isTreeParentPicker(module, binding)
                            && (pickerField.reference() == null
                            || pickerField.reference().cardinality() != ReferenceCardinality.ONE)) {
                        throw new PlatformException("Picker query target must be a single record reference: "
                                + uiSet.getModuleAlias() + "." + binding.targetPickerFieldKey());
                    }
                }
                if (reference == null || reference.cardinality() != ReferenceCardinality.ONE
                        || !level.sourceModuleAlias().equals(reference.targetModuleAlias())) {
                    throw new PlatformException("Navigator query field must be a single reference to "
                            + level.sourceModuleAlias() + ": " + uiSet.getModuleAlias() + "." + binding.targetKey());
                }
            }
    }

    private PlatformPublishedPageComposition publishedCompositionIncluding(PlatformUiSet uiSet,
                                                                            PlatformUiConfig candidate) {
        List<PlatformUiSet> uiSets = publishedUiSets(uiSet.getModuleAlias());
        List<String> uiSetIds = uiSets.stream().map(PlatformUiSet::getId).toList();
        Map<String, PlatformUiConfig> configsById = uiConfigService.listPublishedByUiSetIds(uiSetIds).stream()
                .collect(Collectors.toMap(PlatformUiConfig::getId, Function.identity()));
        PlatformUiConfig publishCandidate = copyForPublish(candidate, Boolean.TRUE);
        configsById.put(publishCandidate.getId(), publishCandidate);
        return PlatformPublishedPageComposition.resolve(uiSets, List.copyOf(configsById.values()),
                PlatformUiClientType.WEB);
    }

    private List<PlatformUiSet> publishedUiSets(String moduleAlias) {
        return uiSetService.listByModuleAlias(moduleAlias);
    }

    private Set<String> publishedFormEditorFieldNames(PlatformPublishedPageComposition composition) {
        PlatformUiConfig formConfig = composition.formConfig();
        return formConfig == null ? Set.of()
                : uiConfigFieldService.visibleFieldNamesByUiConfigIds(List.of(formConfig.getId()));
    }

    private boolean isTreeParentPicker(DynamicModuleDescriptor module, PlatformPageContextBinding binding) {
        return PlatformAbilityFields.TREE_PARENT_FIELD.equals(binding.targetPickerFieldKey())
                && module.entities().stream()
                .filter(entity -> entity.entityAlias().equals(module.mainEntityAlias()))
                .anyMatch(entity -> entity.capabilities().contains("TREE"));
    }

    private void validateNavigatorSourceCapabilities(PlatformUiSet uiSet,
                                                     PlatformUiConfig uiConfig,
                                                     PlatformPageNavigatorLayout navigator) {
        // Platform core can be used without the Web delivery module. In that case no runnable
        // page host exists, so source-projection validation belongs to the optional delivery adapter.
        if (navigatorSourceCapabilityResolver == null) return;
        for (PlatformPageNavigatorLevel level : navigator.levels()) {
            NavigatorSourceCapability required = "TREE".equals(level.kind())
                    ? NavigatorSourceCapability.REFERENCE_TREE
                    : NavigatorSourceCapability.REFERENCE_QUERY;
            if (!navigatorSourceCapabilityResolver.supports(level.sourceModuleAlias(), required)) {
                throw new PlatformException("Navigator source capability is unavailable: page="
                        + uiSet.getModuleAlias() + ", uiConfig=" + uiConfig.getId() + ", level=" + level.key()
                        + ", source=" + level.sourceModuleAlias() + ", required=" + required);
            }
            PlatformPageNavigatorManagement management = level.management();
            if (management != null) {
                Set<String> actions = management.actions() == null
                        ? Set.of("CREATE", "UPDATE", "DELETE")
                        : management.actions();
                if (!actions.isEmpty() && !navigatorSourceCapabilityResolver.supportsManagement(
                        level.sourceModuleAlias(), actions, management.editorSurface())) {
                    throw new PlatformException("Navigator source management contract is unavailable: page="
                            + uiSet.getModuleAlias() + ", uiConfig=" + uiConfig.getId() + ", level=" + level.key()
                            + ", source=" + level.sourceModuleAlias());
                }
            }
        }
    }

    @Transactional
    public void publishQueryTemplate(String queryTemplateId) {
        PlatformQueryTemplate template = validateQueryTemplatePublishable(queryTemplateId);
        try (PlatformPageConfigPublishContext.Scope ignored = PlatformPageConfigPublishContext.open()) {
            queryTemplateService.update(copyForPublish(template, Boolean.TRUE));
        }
        publishedConfigurationChanged(template.getModuleAlias());
    }

    @Transactional
    public void unpublishQueryTemplate(String queryTemplateId) {
        PlatformQueryTemplate template = queryTemplateService.requireQueryTemplate(queryTemplateId);
        try (PlatformPageConfigPublishContext.Scope ignored = PlatformPageConfigPublishContext.open()) {
            queryTemplateService.update(copyForPublish(template, Boolean.FALSE));
        }
        publishedConfigurationChanged(template.getModuleAlias());
    }

    private void publishedConfigurationChanged(String moduleAlias) {
        // Compile against the transaction's candidate snapshot before emitting the after-commit
        // event. A failed compilation rolls the configuration update back and leaves the old
        // installed execution plan untouched.
        pageExecutionCoordinator.prepareAfterPublishedConfigurationChange(moduleAlias);
        publishPlanInvalidation(moduleAlias);
    }

    private void publishPlanInvalidation(String moduleAlias) {
        runtimeEventPublisher.publishAfterCommit(RuntimeEvent.of(RuntimeEventType.MODULE_PAGE_CONFIG_PUBLISHED,
                moduleAlias, null, null, null, null, true, "published page configuration",
                RuntimeMutationSource.SYSTEM, Map.of()));
    }

    public PlatformQueryTemplate validateQueryTemplatePublishable(String queryTemplateId) {
        PlatformQueryTemplate template = queryTemplateService.requireQueryTemplate(queryTemplateId);
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            throw BusinessExceptions.warning("platform.query-template.publish-disabled",
                    "Query template publish requires enabled template: " + queryTemplateId);
        }
        queryItemService.compile(template.getId());
        return template;
    }

    private void validatePageCapabilityContract(PlatformUiSet uiSet, PlatformUiConfig uiConfig, JsonNode layout) {
        if (recordService == null || layout == null) return;
        Set<String> traits = new java.util.LinkedHashSet<>();
        JsonNode traitValues = layout.path("traits");
        if (traitValues.isArray()) {
            traitValues.forEach(value -> traits.add(value.asText()));
        }
        String template = layout.path("template").asText(null);
        if (!PageCapabilityContractValidator.TREE_MANAGEMENT.equals(template)
                && !traits.contains(PageCapabilityContractValidator.STANDARD_CRUD)
                && !traits.contains(PageCapabilityContractValidator.ENABLED_STATUS)
                && !traits.contains(PageCapabilityContractValidator.RECYCLE_BIN)) {
            return;
        }
        DynamicModuleDescriptor module = recordService.describe(uiSet.getModuleAlias());
        if (module == null) return;
        DynamicEntityDescriptor mainEntity = module.entities().stream()
                .filter(entity -> entity.entityAlias().equals(module.mainEntityAlias()))
                .findFirst()
                .orElse(null);
        if (mainEntity == null) return;
        try {
            PageCapabilityContractValidator.validate(uiSet.getModuleAlias(), template,
                    traits, mainEntity.capabilities(), module.actions().stream()
                            .map(DynamicActionDescriptor::code)
                            .collect(Collectors.toUnmodifiableSet()));
        } catch (IllegalArgumentException exception) {
            throw new PlatformException("UI config page capability is invalid: " + uiConfig.getId(), exception);
        }
    }

    private JsonNode validateLayoutJson(String moduleAlias, PlatformUiConfig uiConfig) {
        String layoutJson = uiConfig.getLayoutJson();
        if (layoutJson == null || layoutJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(layoutJson);
            validateLayoutRoot(moduleAlias, root, uiConfig.getId());
            return root;
        } catch (JsonProcessingException exception) {
            throw new PlatformException("UI config layout JSON cannot be decoded: " + uiConfig.getId());
        }
    }

    private void validateLayoutRoot(String moduleAlias, JsonNode root, String uiConfigId) {
        if (root == null || !root.isObject()) {
            throw new PlatformException("UI config layout JSON root must be object: " + uiConfigId);
        }
        validatePageRootContract(root, uiConfigId);
        validateSummaryPanel(root.get("summaryPanel"), uiConfigId);
        validateReferenceCandidate(root.get("referenceCandidate"), "referenceCandidate", uiConfigId);
        validateReferenceCandidateArray(root.get("referenceCandidates"), "referenceCandidates", uiConfigId);
        validateChildSections(root.get("children"), "children", uiConfigId);
        validateChildSections(root.get("childSections"), "childSections", uiConfigId);
        validateKnownBlocks(moduleAlias, root.get("blocks"), uiConfigId);
    }

    private void validatePageRootContract(JsonNode root, String uiConfigId) {
        JsonNode template = root.get("template");
        if (template != null && !template.isNull()) {
            if (!template.isTextual() || !Set.of("FLAT_MANAGEMENT", "LIST_DETAIL_CARD", "TREE_MANAGEMENT")
                    .contains(template.asText())) {
                throw layoutException(uiConfigId, "template is unsupported");
            }
        }
        JsonNode traits = root.get("traits");
        if (traits == null || traits.isNull()) return;
        if (!traits.isArray()) {
            throw layoutException(uiConfigId, "traits must be array");
        }
        Set<String> supported = Set.of(
                "STANDARD_CRUD", "ENABLED_STATUS", "RECYCLE_BIN", "RESPONSIVE_DETAIL_SURFACE");
        for (JsonNode trait : traits) {
            if (!trait.isTextual() || !supported.contains(trait.asText())) {
                throw layoutException(uiConfigId, "traits contains unsupported value");
            }
        }
    }

    private void validateSummaryPanel(JsonNode summaryPanel, String uiConfigId) {
        if (summaryPanel == null || summaryPanel.isNull()) {
            return;
        }
        if (!summaryPanel.isObject()) {
            throw layoutException(uiConfigId, "summaryPanel must be object");
        }
        JsonNode items = summaryPanel.get("items");
        if (items == null || items.isNull()) {
            return;
        }
        if (!items.isArray()) {
            throw layoutException(uiConfigId, "summaryPanel.items must be array");
        }
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!item.isObject()) {
                throw layoutException(uiConfigId, "summaryPanel.items[" + i + "] must be object");
            }
            JsonNode aggregate = item.get("aggregate");
            if (aggregate == null || !aggregate.isTextual() || aggregate.asText().isBlank()) {
                throw layoutException(uiConfigId, "summaryPanel.items[" + i + "].aggregate is required");
            }
            if (!SUMMARY_AGGREGATES.contains(aggregate.asText())) {
                throw layoutException(uiConfigId, "summaryPanel.items[" + i + "].aggregate is unsupported");
            }
            JsonNode fieldName = item.get("fieldName");
            if (fieldName != null && !fieldName.isNull() && !fieldName.isTextual()) {
                throw layoutException(uiConfigId, "summaryPanel.items[" + i + "].fieldName must be string");
            }
        }
    }

    private void validateReferenceCandidateArray(JsonNode candidates, String path, String uiConfigId) {
        if (candidates == null || candidates.isNull()) {
            return;
        }
        if (!candidates.isArray()) {
            throw layoutException(uiConfigId, path + " must be array");
        }
        for (int i = 0; i < candidates.size(); i++) {
            validateReferenceCandidate(candidates.get(i), path + "[" + i + "]", uiConfigId);
        }
    }

    private void validateReferenceCandidate(JsonNode candidate, String path, String uiConfigId) {
        if (candidate == null || candidate.isNull()) {
            return;
        }
        if (!candidate.isObject()) {
            throw layoutException(uiConfigId, path + " must be object");
        }
        validateOptionalText(candidate, "sourceUiConfigId", path, uiConfigId);
        validateOptionalText(candidate, "uiConfigId", path, uiConfigId);
        validateOptionalText(candidate, "queryTemplateId", path, uiConfigId);
    }

    private void validateChildSections(JsonNode sections, String path, String uiConfigId) {
        if (sections == null || sections.isNull()) {
            return;
        }
        if (!sections.isArray()) {
            throw layoutException(uiConfigId, path + " must be array");
        }
        for (int i = 0; i < sections.size(); i++) {
            JsonNode section = sections.get(i);
            String sectionPath = path + "[" + i + "]";
            if (!section.isObject()) {
                throw layoutException(uiConfigId, sectionPath + " must be object");
            }
            JsonNode relationCode = section.get("relationCode");
            if (relationCode == null || !relationCode.isTextual() || relationCode.asText().isBlank()) {
                throw layoutException(uiConfigId, sectionPath + ".relationCode is required");
            }
            validateOptionalText(section, "uiConfigId", sectionPath, uiConfigId);
        }
    }

    private void validateKnownBlocks(String moduleAlias, JsonNode blocks, String uiConfigId) {
        if (blocks == null || blocks.isNull()) {
            return;
        }
        if (!blocks.isArray()) {
            throw layoutException(uiConfigId, "blocks must be array");
        }
        for (int i = 0; i < blocks.size(); i++) {
            JsonNode block = blocks.get(i);
            String path = "blocks[" + i + "]";
            if (!block.isObject()) {
                throw layoutException(uiConfigId, path + " must be object");
            }
            validateOptionalText(block, "type", path, uiConfigId);
            validateOptionalText(block, "key", path, uiConfigId);
            validateAssociationBlock(moduleAlias, block, path, uiConfigId);
            validateActionBlock(moduleAlias, block, path, uiConfigId);
            validateLocalEditBlock(moduleAlias, block, path, uiConfigId);
            validateTaskBlock(moduleAlias, block, path, uiConfigId);
        }
    }

    private void validateAssociationBlock(String moduleAlias, JsonNode block, String path, String uiConfigId) {
        JsonNode type = block.get("type");
        if (type == null || type.isNull() || !"associationView".equals(type.asText())) {
            return;
        }
        JsonNode viewCode = block.get("viewCode");
        if (viewCode == null || !viewCode.isTextual() || viewCode.asText().isBlank()) {
            throw layoutException(uiConfigId, path + ".viewCode is required");
        }
        validateAssociationViewCode(moduleAlias, viewCode.asText(), path, uiConfigId);
        validateOptionalText(block, "title", path, uiConfigId);
        validateOptionalText(block, "uiConfigId", path, uiConfigId);
        validateOptionalText(block, "queryTemplateId", path, uiConfigId);
    }

    private void validateAssociationViewCode(String moduleAlias, String viewCode, String path, String uiConfigId) {
        if (recordService == null) {
            return;
        }
        DynamicAssociationViewDescriptor view = recordService.describe(moduleAlias).associationViews().stream()
                .filter(item -> item.code().equals(viewCode))
                .findFirst()
                .orElseThrow(() -> layoutException(uiConfigId, path + ".viewCode is unknown"));
        if (!view.queryable()) {
            throw layoutException(uiConfigId, path + ".viewCode is not queryable");
        }
    }

    private void validateLocalEditBlock(String moduleAlias, JsonNode block, String path, String uiConfigId) {
        JsonNode type = block.get("type");
        if (type == null || type.isNull() || !"localEdit".equals(type.asText())) {
            return;
        }
        String actionCode = validateRequiredActionCode(block, path, uiConfigId);
        DynamicActionDescriptor action = validateActionCode(moduleAlias, actionCode, path, uiConfigId);
        if (action != null && !DynamicLocalEditActionExecutor.EXECUTOR_KEY.equals(action.executorKey())) {
            throw layoutException(uiConfigId, path + ".actionCode must use local edit executor");
        }
        validateOptionalText(block, "title", path, uiConfigId);
        validateOptionalText(block, "position", path, uiConfigId);
        validateOptionalText(block, "targetUiConfigId", path, uiConfigId);
        validateOptionalPositiveInt(block, "width", path, uiConfigId);
        validateOptionalPositiveInt(block, "height", path, uiConfigId);
        validateOptionalRefreshObject(block, path, uiConfigId);
        validateLocalEditTargetConfig(moduleAlias, actionCode, block, path, uiConfigId);
    }

    private void validateActionBlock(String moduleAlias, JsonNode block, String path, String uiConfigId) {
        JsonNode type = block.get("type");
        if (type == null || type.isNull()
                || (!"dialog".equals(type.asText()) && !"action".equals(type.asText()))) {
            return;
        }
        String actionCode = validateRequiredActionCode(block, path, uiConfigId);
        DynamicActionDescriptor action = validateActionCode(moduleAlias, actionCode, path, uiConfigId);
        if (action != null && "dialog".equals(type.asText()) && action.executorType() != EntityActionExecutorType.DIALOG) {
            throw layoutException(uiConfigId, path + ".actionCode must be DIALOG action");
        }
        if (action != null && "action".equals(type.asText()) && action.executorType() == EntityActionExecutorType.DIALOG) {
            throw layoutException(uiConfigId, path + ".actionCode must not be DIALOG action");
        }
        validateOptionalText(block, "title", path, uiConfigId);
        validateOptionalText(block, "position", path, uiConfigId);
        validateOptionalPositiveInt(block, "width", path, uiConfigId);
        validateOptionalPositiveInt(block, "height", path, uiConfigId);
    }

    private void validateTaskBlock(String moduleAlias, JsonNode block, String path, String uiConfigId) {
        JsonNode type = block.get("type");
        if (type == null || type.isNull() || !"taskPanel".equals(type.asText())) {
            return;
        }
        validateRequiredText(block, "key", path, uiConfigId);
        validateOptionalText(block, "title", path, uiConfigId);
        validateOptionalText(block, "diagnosticPath", path, uiConfigId);
        validateOptionalPositiveInt(block, "expectedCount", path, uiConfigId);
        JsonNode checks = block.get("checks");
        if (checks != null && !checks.isNull()) {
            if (!checks.isArray()) {
                throw layoutException(uiConfigId, path + ".checks must be array");
            }
            if (checks.isEmpty()) {
                throw layoutException(uiConfigId, path + ".checks must not be empty");
            }
            int index = 0;
            for (JsonNode check : checks) {
                if (check == null || !check.isObject()) {
                    throw layoutException(uiConfigId, path + ".checks[" + index + "] must be object");
                }
                validateTaskCheck(moduleAlias, check, path + ".checks[" + index + "]", uiConfigId);
                index++;
            }
            return;
        }
        validateTaskCheck(moduleAlias, block, path, uiConfigId);
    }

    private void validateTaskCheck(String moduleAlias, JsonNode block, String path, String uiConfigId) {
        PlatformTaskCheckType checkType = validateTaskCheckType(block, path, uiConfigId);
        validateOptionalText(block, "diagnosticPath", path, uiConfigId);
        validateOptionalPositiveInt(block, "expectedCount", path, uiConfigId);
        if (checkType == PlatformTaskCheckType.MANUAL) {
            validateOptionalText(block, "associationViewCode", path, uiConfigId);
            validateOptionalText(block, "queryTemplateId", path, uiConfigId);
            validateOptionalText(block, "externalRecordIdKey", path, uiConfigId);
            validateOptionalText(block, "targetModuleAlias", path, uiConfigId);
            validateOptionalText(block, "generationRuleId", path, uiConfigId);
            return;
        }
        if (checkType == PlatformTaskCheckType.ASSOCIATION_VIEW) {
            String viewCode = validateRequiredText(block, "associationViewCode", path, uiConfigId);
            validateAssociationViewCode(moduleAlias, viewCode, path + ".associationViewCode", uiConfigId);
            validateOptionalText(block, "queryTemplateId", path, uiConfigId);
            validateOptionalText(block, "externalRecordIdKey", path, uiConfigId);
            validateOptionalText(block, "targetModuleAlias", path, uiConfigId);
            validateOptionalText(block, "generationRuleId", path, uiConfigId);
            return;
        }
        if (checkType == PlatformTaskCheckType.QUERY_TEMPLATE) {
            String queryTemplateId = validateRequiredText(block, "queryTemplateId", path, uiConfigId);
            validateQueryTemplate(moduleAlias, queryTemplateId, path, uiConfigId);
            validateOptionalText(block, "associationViewCode", path, uiConfigId);
            validateOptionalText(block, "externalRecordIdKey", path, uiConfigId);
            validateOptionalText(block, "targetModuleAlias", path, uiConfigId);
            validateOptionalText(block, "generationRuleId", path, uiConfigId);
            return;
        }
        String targetModuleAlias = validateRequiredText(block, "targetModuleAlias", path, uiConfigId);
        validateModuleAlias(targetModuleAlias, path + ".targetModuleAlias", uiConfigId);
        validateOptionalText(block, "generationRuleId", path, uiConfigId);
        validateOptionalText(block, "associationViewCode", path, uiConfigId);
        validateOptionalText(block, "queryTemplateId", path, uiConfigId);
        validateOptionalText(block, "externalRecordIdKey", path, uiConfigId);
    }

    private PlatformTaskCheckType validateTaskCheckType(JsonNode block, String path, String uiConfigId) {
        JsonNode checkType = block.get("checkType");
        if (checkType == null || checkType.isNull()) {
            return PlatformTaskCheckType.MANUAL;
        }
        if (!checkType.isTextual() || checkType.asText().isBlank()) {
            throw layoutException(uiConfigId, path + ".checkType must be string");
        }
        try {
            return PlatformTaskCheckType.valueOf(checkType.asText().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw layoutException(uiConfigId, path + ".checkType is unsupported");
        }
    }

    private void validateOptionalPositiveInt(JsonNode block, String field, String path, String uiConfigId) {
        JsonNode value = block.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isInt() || value.asInt() <= 0) {
            throw layoutException(uiConfigId, path + "." + field + " must be positive integer");
        }
    }

    private void validateOptionalRefreshObject(JsonNode block, String path, String uiConfigId) {
        JsonNode refresh = block.get("refresh");
        if (refresh == null || refresh.isNull()) {
            return;
        }
        if (!refresh.isObject()) {
            throw layoutException(uiConfigId, path + ".refresh must be object");
        }
        validateOptionalBoolean(refresh, "list", path + ".refresh", uiConfigId);
        validateOptionalBoolean(refresh, "detail", path + ".refresh", uiConfigId);
    }

    private void validateOptionalBoolean(JsonNode block, String field, String path, String uiConfigId) {
        JsonNode value = block.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isBoolean()) {
            throw layoutException(uiConfigId, path + "." + field + " must be boolean");
        }
    }

    private void validateLocalEditTargetConfig(String moduleAlias,
                                               String actionCode,
                                               JsonNode block,
                                               String path,
                                               String uiConfigId) {
        String targetUiConfigId = text(block, "targetUiConfigId");
        if (targetUiConfigId == null) {
            throw layoutException(uiConfigId, path + ".targetUiConfigId is required");
        }
        PlatformUiConfig sourceConfig = uiConfigService.requireUiConfig(uiConfigId);
        PlatformUiConfig targetConfig = uiConfigService.requireUiConfig(targetUiConfigId);
        PlatformUiSet targetSet = uiSetService.requireUiSet(targetConfig.getUiSetId());
        if (!moduleAlias.equals(targetSet.getModuleAlias())) {
            throw layoutException(uiConfigId, path + ".targetUiConfigId must belong to module");
        }
        if (targetSet.getSetType() != PlatformUiSetType.FORM) {
            throw layoutException(uiConfigId, path + ".targetUiConfigId must use FORM UI set");
        }
        if (sourceConfig.getClientType() != targetConfig.getClientType()) {
            throw layoutException(uiConfigId, path + ".targetUiConfigId must use same client type");
        }
        if (!Boolean.TRUE.equals(targetSet.getEnabled())) {
            throw layoutException(uiConfigId, path + ".targetUiConfigId must use enabled UI set");
        }
        boolean publishingTargetItself = targetUiConfigId.equals(uiConfigId);
        if (!Boolean.TRUE.equals(targetConfig.getEnabled())
                || (!publishingTargetItself && !Boolean.TRUE.equals(targetConfig.getPublished()))) {
            throw layoutException(uiConfigId, path + ".targetUiConfigId must be published and enabled");
        }
        if (!hasLocalEditBinding(targetConfig, actionCode)) {
            throw layoutException(uiConfigId, path + ".targetUiConfigId must bind local edit action");
        }
        uiConfigFieldService.validateLocalEditExecutableFields(targetUiConfigId);
    }

    private boolean hasLocalEditBinding(PlatformUiConfig uiConfig, String actionCode) {
        String layoutJson = uiConfig.getLayoutJson();
        if (layoutJson == null || layoutJson.isBlank()) {
            return false;
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(layoutJson);
        } catch (JsonProcessingException exception) {
            throw new PlatformException("UI config layout JSON cannot be decoded: " + uiConfig.getId());
        }
        JsonNode blocks = root.get("blocks");
        if (blocks == null || !blocks.isArray()) {
            return false;
        }
        for (JsonNode item : blocks) {
            if (item != null && item.isObject()
                    && "localEdit".equals(text(item, "type"))
                    && actionCode.equals(text(item, "actionCode"))) {
                return true;
            }
        }
        return false;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private void validateQueryTemplate(String moduleAlias, String queryTemplateId, String path, String uiConfigId) {
        PlatformQueryTemplate template = queryTemplateService.requireQueryTemplate(queryTemplateId);
        if (!moduleAlias.equals(template.getModuleAlias())) {
            throw layoutException(uiConfigId, path + ".queryTemplateId must belong to module");
        }
        if (!Boolean.TRUE.equals(template.getPublished()) || !Boolean.TRUE.equals(template.getEnabled())) {
            throw layoutException(uiConfigId, path + ".queryTemplateId must be published and enabled");
        }
    }

    private void validateModuleAlias(String moduleAlias, String path, String uiConfigId) {
        if (recordService == null) {
            return;
        }
        try {
            recordService.describe(moduleAlias);
        } catch (RuntimeException exception) {
            throw layoutException(uiConfigId, path + " is unknown");
        }
    }

    private String validateRequiredActionCode(JsonNode block, String path, String uiConfigId) {
        return validateRequiredText(block, "actionCode", path, uiConfigId);
    }

    private String validateRequiredText(JsonNode block, String field, String path, String uiConfigId) {
        JsonNode actionCode = block.get(field);
        if (actionCode == null || !actionCode.isTextual() || actionCode.asText().isBlank()) {
            throw layoutException(uiConfigId, path + "." + field + " is required");
        }
        return actionCode.asText().trim();
    }

    private DynamicActionDescriptor validateActionCode(String moduleAlias, String actionCode, String path, String uiConfigId) {
        if (recordService == null) {
            return null;
        }
        try {
            return recordService.action(moduleAlias, actionCode);
        } catch (RuntimeException exception) {
            throw layoutException(uiConfigId, path + ".actionCode is unknown");
        }
    }

    private void validateOptionalText(JsonNode node, String field, String path, String uiConfigId) {
        JsonNode value = node.get(field);
        if (value != null && !value.isNull() && !value.isTextual()) {
            throw layoutException(uiConfigId, path + "." + field + " must be string");
        }
    }

    private PlatformException layoutException(String uiConfigId, String message) {
        return new PlatformException("UI config layout JSON invalid at " + message + ": " + uiConfigId);
    }

    private PlatformUiConfig copyForPublish(PlatformUiConfig source, boolean published) {
        PlatformUiConfig target = new PlatformUiConfig();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setVersion(source.getVersion());
        target.setUiSetId(source.getUiSetId());
        target.setClientType(source.getClientType());
        target.setLayoutJson(source.getLayoutJson());
        target.setTitle(source.getTitle());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        target.setPublished(published);
        return target;
    }

    private PlatformQueryTemplate copyForPublish(PlatformQueryTemplate source, boolean published) {
        PlatformQueryTemplate target = new PlatformQueryTemplate();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setVersion(source.getVersion());
        target.setModuleAlias(source.getModuleAlias());
        target.setAlias(source.getAlias());
        target.setDefaultTemplate(source.getDefaultTemplate());
        target.setTitle(source.getTitle());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        target.setPublished(published);
        return target;
    }
}
