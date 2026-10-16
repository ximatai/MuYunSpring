package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import com.fasterxml.jackson.databind.JsonNode;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionRefreshStrategy;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.platform.localization.MetadataQuerySchemas;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuPageMode;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlatformPageBootstrapService {

    private final MenuService menuService;
    private final PlatformPageConfigSnapshotService snapshotService;
    private final ModuleMetadataFieldService moduleFieldService;
    private final FieldUiControlService fieldUiTypeService;
    private final FieldUiControlPropertyService fieldUiTypeAttributeService;
    private final FieldUiControlBindingService fieldUiTypeFieldMappingService;
    private final FieldSpecService fieldSpecService;
    private final DynamicRecordService recordService;

    public PlatformPageBootstrapService(MenuService menuService,
                                        PlatformPageConfigSnapshotService snapshotService) {
        this(menuService, snapshotService, null);
    }

    public PlatformPageBootstrapService(MenuService menuService,
                                        PlatformPageConfigSnapshotService snapshotService,
                                        ModuleMetadataFieldService moduleFieldService) {
        this(menuService, snapshotService, moduleFieldService, null, null, null, null);
    }

    public PlatformPageBootstrapService(MenuService menuService,
                                        PlatformPageConfigSnapshotService snapshotService,
                                        ModuleMetadataFieldService moduleFieldService,
                                        FieldUiControlService fieldUiTypeService,
                                        FieldUiControlPropertyService fieldUiTypeAttributeService,
                                        FieldUiControlBindingService fieldUiTypeFieldMappingService) {
        this(menuService, snapshotService, moduleFieldService, fieldUiTypeService, fieldUiTypeAttributeService,
                fieldUiTypeFieldMappingService, null, null);
    }

    public PlatformPageBootstrapService(MenuService menuService,
                                        PlatformPageConfigSnapshotService snapshotService,
                                        ModuleMetadataFieldService moduleFieldService,
                                        FieldUiControlService fieldUiTypeService,
                                        FieldUiControlPropertyService fieldUiTypeAttributeService,
                                        FieldUiControlBindingService fieldUiTypeFieldMappingService,
                                        DynamicRecordService recordService) {
        this(menuService, snapshotService, moduleFieldService, fieldUiTypeService, fieldUiTypeAttributeService,
                fieldUiTypeFieldMappingService, recordService, null);
    }

    @Autowired
    public PlatformPageBootstrapService(MenuService menuService,
                                        PlatformPageConfigSnapshotService snapshotService,
                                        ModuleMetadataFieldService moduleFieldService,
                                        FieldUiControlService fieldUiTypeService,
                                        FieldUiControlPropertyService fieldUiTypeAttributeService,
                                        FieldUiControlBindingService fieldUiTypeFieldMappingService,
                                        DynamicRecordService recordService,
                                        FieldSpecService fieldSpecService) {
        this.menuService = menuService;
        this.snapshotService = snapshotService;
        this.moduleFieldService = moduleFieldService;
        this.fieldUiTypeService = fieldUiTypeService;
        this.fieldUiTypeAttributeService = fieldUiTypeAttributeService;
        this.fieldUiTypeFieldMappingService = fieldUiTypeFieldMappingService;
        this.recordService = recordService;
        this.fieldSpecService = fieldSpecService;
    }

    public PlatformPageBootstrap bootstrapByMenu(String menuId) {
        return bootstrapByMenu(menuId, PlatformUiClientType.WEB);
    }

    public PlatformPageBootstrap bootstrapByMenu(String menuId, PlatformUiClientType clientType) {
        PlatformUiClientType requestedClientType = clientType == null ? PlatformUiClientType.WEB : clientType;
        Menu menu = menuService.currentUserVisibleMenu(menuId);
        if (menu == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-page-bootstrap.menu-is-not-visible-or-does-not-exist",
                    "Menu is not visible or does not exist: {value1}"), Map.of("value1", String.valueOf(menuId)));
        }
        if (!isModuleEntryMenu(menu)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-page-bootstrap.page-bootstrap-requires-module-entry-menu",
                    "Page bootstrap requires module entry menu: {value1}"), Map.of("value1", String.valueOf(menuId)));
        }
        return bootstrap(menu, requestedClientType);
    }

    public PlatformPageBootstrap bootstrapByModule(String moduleAlias) {
        return bootstrapByModule(moduleAlias, PlatformUiClientType.WEB);
    }

    public PlatformPageBootstrap bootstrapByModule(String moduleAlias, PlatformUiClientType clientType) {
        PlatformUiClientType requestedClientType = clientType == null ? PlatformUiClientType.WEB : clientType;
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        Menu menu = menuService.currentUserVisibleModuleMenu(validAlias);
        if (menu == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-page-bootstrap.module-menu-is-not-visible-or-does-not-exist",
                    "Module menu is not visible or does not exist: {value1}"), Map.of("value1", String.valueOf(validAlias)));
        }
        return bootstrap(menu, requestedClientType);
    }

    private PlatformPageBootstrap bootstrap(Menu menu, PlatformUiClientType clientType) {
        PlatformPageConfigSnapshot snapshot = snapshotService.snapshot(menu.getModuleAlias());
        MenuPageMode pageMode = menu.getPageMode() == null ? MenuPageMode.LIST : menu.getPageMode();
        String defaultUiConfigId = resolveDefaultUiConfigId(snapshot, menu.getDefaultUiConfigId(), pageMode, clientType);
        return new PlatformPageBootstrap(
                PlatformPageEntryContext.from(menu,
                        defaultUiConfigId,
                        resolveDefaultQueryTemplateId(snapshot, menu.getDefaultQueryTemplateId())),
                clientType,
                resolveConfig(snapshot, clientType, selectedUiConfig(snapshot, clientType, defaultUiConfigId))
        );
    }

    private String resolveDefaultUiConfigId(PlatformPageConfigSnapshot snapshot,
                                            String requestedUiConfigId,
                                            MenuPageMode pageMode,
                                            PlatformUiClientType clientType) {
        if (requestedUiConfigId != null && !requestedUiConfigId.isBlank()) {
            boolean exists = snapshot.uiConfigs().stream().anyMatch(config -> Objects.equals(config.getId(), requestedUiConfigId));
            if (!exists) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.platform-page-bootstrap.default-ui-config-is-not-published-in-module-snapshot",
                        "Default UI config is not published in module snapshot: {value1}"), Map.of("value1", String.valueOf(requestedUiConfigId)));
            }
            PlatformUiConfig config = snapshot.uiConfigs().stream()
                    .filter(item -> Objects.equals(item.getId(), requestedUiConfigId))
                    .findFirst()
                    .orElseThrow();
            PlatformUiSet set = snapshot.uiSets().stream()
                    .filter(item -> Objects.equals(item.getId(), config.getUiSetId()))
                    .findFirst()
                    .orElseThrow();
            if (set.getSetType() != uiSetType(pageMode)) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.platform-page-bootstrap.default-ui-config-type-must-match-page-mode",
                        "Default UI config type must match page mode: {value1}"), Map.of("value1", String.valueOf(pageMode)));
            }
            if (config.getClientType() != clientType) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.platform-page-bootstrap.default-ui-config-client-type-must-match-requested-client-type",
                        "Default UI config client type must match requested client type: {value1}"), Map.of("value1", String.valueOf(clientType)));
            }
            return requestedUiConfigId;
        }
        PlatformUiSetType targetType = uiSetType(pageMode);
        return snapshot.uiSets().stream()
                .filter(set -> set.getSetType() == targetType)
                .filter(set -> Boolean.TRUE.equals(set.getDefaultSet()))
                .flatMap(set -> snapshot.uiConfigs().stream()
                        .filter(config -> Objects.equals(config.getUiSetId(), set.getId()))
                        .filter(config -> config.getClientType() == clientType))
                .map(PlatformUiConfig::getId)
                .findFirst()
                .orElse(null);
    }

    private boolean isModuleEntryMenu(Menu menu) {
        return menu.getModuleAlias() != null
                && !menu.getModuleAlias().isBlank()
                && (menu.getRoute() == null || menu.getRoute().isBlank())
                && (menu.getExternalUrl() == null || menu.getExternalUrl().isBlank());
    }

    private String resolveDefaultQueryTemplateId(PlatformPageConfigSnapshot snapshot, String requestedTemplateId) {
        if (requestedTemplateId != null && !requestedTemplateId.isBlank()) {
            boolean exists = snapshot.queryTemplates().stream()
                    .anyMatch(template -> Objects.equals(template.getId(), requestedTemplateId));
            if (!exists) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.platform-page-bootstrap.default-query-template-is-not-published-or-enabled-in-module-snapshot",
                        "Default query template is not published or enabled in module snapshot: {value1}"), Map.of("value1", String.valueOf(requestedTemplateId)));
            }
            return requestedTemplateId;
        }
        return snapshot.queryTemplates().stream()
                .filter(template -> Boolean.TRUE.equals(template.getDefaultTemplate()))
                .map(PlatformQueryTemplate::getId)
                .findFirst()
                .orElse(null);
    }

    private PlatformUiSetType uiSetType(MenuPageMode pageMode) {
        if (pageMode == MenuPageMode.FORM) {
            return PlatformUiSetType.FORM;
        }
        if (pageMode == MenuPageMode.DETAIL) {
            return PlatformUiSetType.DETAIL;
        }
        return PlatformUiSetType.LIST;
    }

    public PlatformResolvedPageConfig resolveConfig(PlatformPageConfigSnapshot snapshot,
                                                    PlatformUiClientType clientType) {
        PlatformUiClientType requestedClientType = clientType == null ? PlatformUiClientType.WEB : clientType;
        return resolveConfig(snapshot, requestedClientType, null);
    }

    private PlatformResolvedPageConfig resolveConfig(PlatformPageConfigSnapshot snapshot,
                                                     PlatformUiClientType clientType,
                                                     PlatformUiConfig selectedUiConfig) {
        if (moduleFieldService == null) {
            return PlatformResolvedPageConfig.empty();
        }
        Set<String> clientUiConfigIds = snapshot.uiConfigs().stream()
                .filter(config -> config.getClientType() == clientType)
                .map(PlatformUiConfig::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<PlatformResolvedUiField> uiFields = snapshot.uiFields().stream()
                .filter(field -> clientUiConfigIds.contains(field.getUiConfigId()))
                .map(this::resolvedUiField)
                .toList();
        List<PlatformResolvedQueryItem> queryItems = snapshot.queryItems().stream()
                .map(this::resolvedQueryItem)
                .toList();
        return new PlatformResolvedPageConfig(uiFields, queryItems, resolvedFieldUiControls(uiFields),
                associationBlocks(snapshot.moduleAlias(), selectedUiConfig),
                actionBlocks(snapshot, clientType, selectedUiConfig),
                taskBlocks(selectedUiConfig));
    }

    private PlatformUiConfig selectedUiConfig(PlatformPageConfigSnapshot snapshot,
                                              PlatformUiClientType clientType,
                                              String uiConfigId) {
        if (uiConfigId == null || uiConfigId.isBlank()) return null;
        return snapshot.uiConfigs().stream()
                .filter(config -> config.getClientType() == clientType)
                .filter(config -> Objects.equals(config.getId(), uiConfigId))
                .findFirst()
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.platform-page-bootstrap.default-ui-config-is-not-published-in-module-snapshot",
                        "Default UI config is not published in module snapshot: {value1}"), Map.of("value1", String.valueOf(uiConfigId))));
    }

    private List<PlatformAssociationBlock> associationBlocks(String moduleAlias, PlatformUiConfig config) {
        if (config == null) return List.of();
        String layoutJson = config.getLayoutJson();
        if (layoutJson == null || layoutJson.isBlank()) {
            return List.of();
        }
        JsonNode root = PlatformPageLayout.root(config);
        JsonNode blocks = root.get("blocks");
        if (blocks == null || !blocks.isArray()) {
            return List.of();
        }
        java.util.ArrayList<PlatformAssociationBlock> resolved = new java.util.ArrayList<>();
        for (JsonNode block : blocks) {
            if (block == null || !block.isObject() || !"associationView".equals(text(block, "type"))) {
                continue;
            }
            String viewCode = text(block, "viewCode");
            if (viewCode == null) {
                continue;
            }
            ResolvedDetailRelationDescriptor relation = resolveDetailRelation(moduleAlias, viewCode,
                    text(block, "title"), text(block, "uiConfigId"), text(block, "queryTemplateId"));
            resolved.add(new PlatformAssociationBlock(
                    config.getId(),
                    text(block, "key"),
                    viewCode,
                    text(block, "title"),
                    text(block, "uiConfigId"),
                    text(block, "queryTemplateId"),
                    "/" + moduleAlias + "/view/{id}/associations/" + viewCode + "/query",
                    relation
            ));
        }
        return resolved;
    }

    private ResolvedDetailRelationDescriptor resolveDetailRelation(String moduleAlias, String viewCode,
                                                                   String title, String targetUiConfigId,
                                                                   String queryTemplateId) {
        if (recordService == null) return null;
        DynamicAssociationViewDescriptor view = recordService.describe(moduleAlias).associationViews().stream()
                .filter(candidate -> viewCode.equals(candidate.code()))
                .findFirst()
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.platform-page-bootstrap.association-view-is-not-available",
                        "association view is not available: {value1}"), Map.of("value1", String.valueOf(viewCode))));
        String parentBinding = view.relationCode() != null ? view.relationCode() : view.referenceField();
        if (parentBinding == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-page-bootstrap.association-view-has-no-stable-parent-binding",
                    "association view has no stable parent binding: {value1}"), Map.of("value1", String.valueOf(viewCode)));
        }
        String requestedTargetUiConfigId = targetUiConfigId == null ? view.targetUiConfigId() : targetUiConfigId;
        PlatformPageConfigSnapshot targetSnapshot = snapshotService.snapshot(view.targetModuleAlias());
        String resolvedTargetUiConfigId = resolveDetailRelationTargetListUiConfig(targetSnapshot,
                requestedTargetUiConfigId);
        String resolvedQueryTemplateId = queryTemplateId == null ? view.targetQueryTemplateId() : queryTemplateId;
        ResolvedDetailRelationListProjection listProjection = detailRelationListProjection(targetSnapshot,
                resolvedTargetUiConfigId, view.targetEntityAlias());
        DynamicEntityDescriptor targetEntity = recordService.describe(view.targetModuleAlias()).entities().stream()
                .filter(candidate -> view.targetEntityAlias().equals(candidate.entityAlias()))
                .findFirst()
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.platform-page-bootstrap.association-target-entity-is-not-available",
                        "association target entity is not available: {value1}.{value2}"), Map.of("value1", String.valueOf(view.targetModuleAlias()), "value2", String.valueOf(view.targetEntityAlias()))));
        return new ResolvedDetailRelationDescriptor(view.code(), title, true, moduleAlias, view.sourceEntityAlias(),
                view.targetModuleAlias(), view.targetEntityAlias(), parentBinding,
                new ResolvedDetailRelationQueryContract("/" + moduleAlias + "/view/{id}/associations/"
                        + viewCode + "/query", resolvedTargetUiConfigId, resolvedQueryTemplateId, true,
                        view.queryable(), listProjection,
                        MetadataQuerySchemas.from(view.targetModuleAlias(), targetEntity, List.of())), true);
    }

    /**
     * Association queries must name a published target LIST config.  A missing explicit config
     * resolves only through the target module's declared default LIST set; there is no client
     * fallback to an arbitrary module view.
     */
    private String resolveDetailRelationTargetListUiConfig(PlatformPageConfigSnapshot targetSnapshot,
                                                           String requestedUiConfigId) {
        String resolved = resolveDefaultUiConfigId(targetSnapshot, requestedUiConfigId, MenuPageMode.LIST,
                PlatformUiClientType.WEB);
        if (resolved == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-page-bootstrap.detail-relation-target-list-ui-config-is-not-published",
                    "Detail relation target LIST UI config is not published: {value1}"), Map.of("value1", String.valueOf(targetSnapshot.moduleAlias())));
        }
        return resolved;
    }

    private ResolvedDetailRelationListProjection detailRelationListProjection(
            PlatformPageConfigSnapshot targetSnapshot,
            String targetUiConfigId,
            String targetEntityAlias) {
        List<ResolvedDetailRelationListField> fields = targetSnapshot.uiFields().stream()
                .filter(field -> Objects.equals(field.getUiConfigId(), targetUiConfigId))
                .map(this::resolvedUiField)
                .filter(field -> Objects.equals(field.metadataAlias(), targetEntityAlias))
                .filter(field -> field.relationAlias() == null || field.relationAlias().isBlank())
                .filter(field -> !Boolean.FALSE.equals(field.visible()))
                .map(field -> new ResolvedDetailRelationListField(field.fieldName(), field.fieldTitle(),
                        field.fieldForm(), field.fieldUiControlAlias(), field.width(), field.align(),
                        field.maxDisplayLines()))
                .toList();
        return new ResolvedDetailRelationListProjection(targetUiConfigId, fields);
    }

    private List<PlatformActionBlock> actionBlocks(PlatformPageConfigSnapshot snapshot,
                                                   PlatformUiClientType clientType,
                                                   PlatformUiConfig config) {
        if (config == null) return List.of();
        String layoutJson = config.getLayoutJson();
        if (layoutJson == null || layoutJson.isBlank()) {
            return List.of();
        }
        JsonNode root = PlatformPageLayout.root(config);
        JsonNode blocks = root.get("blocks");
        if (blocks == null || !blocks.isArray()) {
            return List.of();
        }
        java.util.ArrayList<PlatformActionBlock> resolved = new java.util.ArrayList<>();
        for (JsonNode block : blocks) {
            if (block == null || !block.isObject()) {
                continue;
            }
            String type = text(block, "type");
            if (!"dialog".equals(type) && !"localEdit".equals(type) && !"action".equals(type)) {
                continue;
            }
            String actionCode = text(block, "actionCode");
            if (actionCode == null) {
                continue;
            }
            resolved.add(new PlatformActionBlock(
                    config.getId(),
                    type,
                    text(block, "key"),
                    actionCode,
                    text(block, "title"),
                    text(block, "position"),
                    localEditTargetUiConfigId(block, type),
                    localEditSubmitPath(snapshot.moduleAlias(), actionCode, type),
                    localEditRefreshStrategy(block, type),
                    positiveInteger(block, "width"),
                    positiveInteger(block, "height"),
                    localEditForm(snapshot, clientType, localEditTargetUiConfigId(block, type), type),
                    text(block, "importance")
            ));
        }
        return resolved;
    }

    private String localEditTargetUiConfigId(JsonNode block, String type) {
        if (!"localEdit".equals(type)) {
            return null;
        }
        String targetUiConfigId = text(block, "targetUiConfigId");
        return targetUiConfigId;
    }

    private LocalEditFormDescriptor localEditForm(PlatformPageConfigSnapshot snapshot,
                                                  PlatformUiClientType clientType,
                                                  String targetUiConfigId,
                                                  String type) {
        if (!"localEdit".equals(type)) {
            return null;
        }
        if (targetUiConfigId == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-page-bootstrap.local-edit-action-block-requires-targetuiconfigid",
                    "Local edit action block requires targetUiConfigId"));
        }
        PlatformUiConfig targetConfig = snapshot.uiConfigs().stream()
                .filter(candidate -> targetUiConfigId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.platform-page-bootstrap.local-edit-target-ui-config-is-not-published",
                        "Local edit target UI config is not published: {value1}"), Map.of("value1", String.valueOf(targetUiConfigId))));
        PlatformUiSet targetSet = snapshot.uiSets().stream()
                .filter(candidate -> Objects.equals(candidate.getId(), targetConfig.getUiSetId()))
                .findFirst()
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.platform-page-bootstrap.local-edit-target-ui-set-is-unavailable",
                        "Local edit target UI set is unavailable: {value1}"), Map.of("value1", String.valueOf(targetConfig.getUiSetId()))));
        if (targetConfig.getClientType() != clientType || targetSet.getSetType() != PlatformUiSetType.FORM) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-page-bootstrap.local-edit-target-ui-config-must-be-a-published-form-config",
                    "Local edit target UI config must be a published {value1} FORM config: {value2}"), Map.of("value1", String.valueOf(clientType), "value2", String.valueOf(targetUiConfigId)));
        }
        List<PlatformResolvedUiField> fields = snapshot.uiFields().stream()
                .filter(field -> targetUiConfigId.equals(field.getUiConfigId()))
                .map(this::resolvedUiField)
                .toList();
        return new LocalEditFormDescriptor(targetUiConfigId, fields, resolvedFieldUiControls(fields),
                LocalEditSubmitContract.standard());
    }

    private String localEditSubmitPath(String moduleAlias, String actionCode, String type) {
        if (!"localEdit".equals(type)) {
            return null;
        }
        return "/" + moduleAlias + "/" + actionCode + "/{recordId}";
    }

    private DynamicActionRefreshStrategy localEditRefreshStrategy(JsonNode block, String type) {
        if (!"localEdit".equals(type)) {
            return DynamicActionRefreshStrategy.none();
        }
        JsonNode refresh = block.get("refresh");
        if (refresh == null || refresh.isNull()) {
            return DynamicActionRefreshStrategy.listAndDetail();
        }
        if (!refresh.isObject()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-page-bootstrap.localedit-refresh-must-be-object",
                    "localEdit.refresh must be object"));
        }
        return new DynamicActionRefreshStrategy(
                booleanValue(refresh, "list", true),
                booleanValue(refresh, "detail", true),
                false,
                null,
                null
        );
    }

    private List<PlatformTaskBlock> taskBlocks(PlatformUiConfig config) {
        return config == null ? List.of() : PlatformTaskBlockLayoutResolver.resolve(config);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private boolean booleanValue(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-page-bootstrap.localedit-refresh-must-be-boolean",
                    "localEdit.refresh.{value1} must be boolean"), Map.of("value1", String.valueOf(field)));
        }
        return value.asBoolean();
    }

    private Integer positiveInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt() || value.asInt() <= 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-page-bootstrap.action-block-must-be-positive-integer",
                    "action block {value1} must be positive integer"), Map.of("value1", String.valueOf(field)));
        }
        return value.asInt();
    }

    private PlatformResolvedUiField resolvedUiField(PlatformUiConfigField field) {
        ResolvedModuleMetadataField resolved = moduleFieldService.resolve(field.getModuleMetadataFieldId());
        return new PlatformResolvedUiField(
                field.getUiConfigId(),
                field.getModuleMetadataFieldId(),
                resolved.relationAlias(),
                resolved.metadataAlias(),
                resolved.fieldName(),
                resolved.columnName(),
                resolved.fieldTitle(),
                resolved.fieldSpecAlias(),
                fieldSpecService == null ? null : fieldSpecService.requireFieldType(resolved.fieldSpecAlias())
                        .getFieldType().name(),
                resolved.fieldForm() == null ? null : resolved.fieldForm().name(),
                field.getFieldUiControlAlias(),
                field.getVisible(),
                field.getVisibleWhen(),
                field.getReadOnly(),
                field.getReadOnlyWhen(),
                field.getRequiredOverride(),
                field.getPlaceholder(),
                field.getDefaultValue(),
                field.getWidth(),
                field.getColumnSpan(),
                field.getAlign(),
                field.getFixedPosition(),
                field.getMaxDisplayLines()
        );
    }

    private PlatformResolvedQueryItem resolvedQueryItem(PlatformQueryItem item) {
        ResolvedModuleMetadataField resolved = item.getModuleMetadataFieldId() == null
                || item.getModuleMetadataFieldId().isBlank()
                ? null
                : moduleFieldService.resolve(item.getModuleMetadataFieldId());
        return new PlatformResolvedQueryItem(
                item.getQueryTemplateId(),
                item.getId(),
                item.getParentId(),
                item.getGroupOperator(),
                item.getModuleMetadataFieldId(),
                resolved == null ? null : resolved.relationAlias(),
                resolved == null ? null : resolved.metadataAlias(),
                resolved == null ? null : resolved.fieldName(),
                resolved == null ? null : resolved.fieldTitle(),
                resolved == null ? null : resolved.fieldSpecAlias(),
                item.getOperator(),
                item.getDefaultValue(),
                item.getAllowExternalValue(),
                item.getExternalValueKey(),
                item.getTimeZone()
        );
    }

    private List<PlatformResolvedFieldUiControl> resolvedFieldUiControls(List<PlatformResolvedUiField> uiFields) {
        if (fieldUiTypeService == null || fieldUiTypeAttributeService == null || fieldUiTypeFieldMappingService == null
                || uiFields.isEmpty()) {
            return List.of();
        }
        List<String> aliases = uiFields.stream()
                .map(PlatformResolvedUiField::fieldUiControlAlias)
                .filter(alias -> alias != null && !alias.isBlank())
                .distinct()
                .toList();
        if (aliases.isEmpty()) {
            return List.of();
        }
        Map<String, List<FieldUiControlProperty>> propertiesByType =
                fieldUiTypeAttributeService.listByFieldUiControlAliases(aliases)
                        .stream()
                        .collect(Collectors.groupingBy(FieldUiControlProperty::getFieldUiControlAlias));
        Map<String, List<FieldUiControlBinding>> mappingsByType =
                fieldUiTypeFieldMappingService.listByFieldUiControlAliases(aliases)
                        .stream()
                        .collect(Collectors.groupingBy(FieldUiControlBinding::getFieldUiControlAlias));
        List<FieldUiControl> fieldUiControls = fieldUiTypeService.listEnabledByAliases(aliases);
        Set<String> resolvedAliases = fieldUiControls.stream()
                .map(FieldUiControl::getAlias)
                .collect(Collectors.toSet());
        List<String> missingAliases = aliases.stream()
                .filter(alias -> !resolvedAliases.contains(alias))
                .toList();
        if (!missingAliases.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-page-bootstrap.resolved-page-config-references-disabled-or-missing-field-ui-controls",
                    "Resolved page config references disabled or missing field UI controls: {value1}"), Map.of("value1", String.valueOf(missingAliases)));
        }
        return fieldUiControls.stream()
                .map(type -> resolvedFieldUiControl(type, propertiesByType.get(type.getAlias()),
                        mappingsByType.get(type.getAlias())))
                .toList();
    }

    private PlatformResolvedFieldUiControl resolvedFieldUiControl(FieldUiControl type,
                                                            List<FieldUiControlProperty> properties,
                                                            List<FieldUiControlBinding> mappings) {
        return new PlatformResolvedFieldUiControl(
                type.getAlias(),
                type.getTitle(),
                type.getDefaultFieldSpecAlias(),
                type.getValueShape(),
                type.getPrimaryValueKey(),
                type.getQueryMode(),
                type.getRendererType(),
                type.getIcon(),
                properties == null ? List.of() : properties.stream()
                        .map(attribute -> new PlatformResolvedFieldUiControlProperty(
                                attribute.getAttributeAlias(),
                                attribute.getTitle(),
                                attribute.getValueFieldSpecAlias(),
                                attribute.getDefaultValue()))
                        .toList(),
                mappings == null ? List.of() : mappings.stream()
                        .map(mapping -> new PlatformResolvedFieldUiControlBinding(
                                mapping.getValueKey(),
                                mapping.getTitle(),
                                mapping.getValueFieldSpecAlias()))
                        .toList()
        );
    }
}
