package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionRefreshStrategy;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MenuService menuService;
    private final PlatformPageConfigSnapshotService snapshotService;
    private final ModuleMetadataFieldService moduleFieldService;
    private final FieldUiControlService fieldUiTypeService;
    private final FieldUiControlPropertyService fieldUiTypeAttributeService;
    private final FieldUiControlBindingService fieldUiTypeFieldMappingService;

    public PlatformPageBootstrapService(MenuService menuService,
                                        PlatformPageConfigSnapshotService snapshotService) {
        this(menuService, snapshotService, null);
    }

    public PlatformPageBootstrapService(MenuService menuService,
                                        PlatformPageConfigSnapshotService snapshotService,
                                        ModuleMetadataFieldService moduleFieldService) {
        this(menuService, snapshotService, moduleFieldService, null, null, null);
    }

    @Autowired
    public PlatformPageBootstrapService(MenuService menuService,
                                        PlatformPageConfigSnapshotService snapshotService,
                                        ModuleMetadataFieldService moduleFieldService,
                                        FieldUiControlService fieldUiTypeService,
                                        FieldUiControlPropertyService fieldUiTypeAttributeService,
                                        FieldUiControlBindingService fieldUiTypeFieldMappingService) {
        this.menuService = menuService;
        this.snapshotService = snapshotService;
        this.moduleFieldService = moduleFieldService;
        this.fieldUiTypeService = fieldUiTypeService;
        this.fieldUiTypeAttributeService = fieldUiTypeAttributeService;
        this.fieldUiTypeFieldMappingService = fieldUiTypeFieldMappingService;
    }

    public PlatformPageBootstrap bootstrapByMenu(String menuId) {
        return bootstrapByMenu(menuId, PlatformUiClientType.WEB);
    }

    public PlatformPageBootstrap bootstrapByMenu(String menuId, PlatformUiClientType clientType) {
        PlatformUiClientType requestedClientType = clientType == null ? PlatformUiClientType.WEB : clientType;
        Menu menu = menuService.currentUserVisibleMenu(menuId);
        if (menu == null) {
            throw new PlatformException("Menu is not visible or does not exist: " + menuId);
        }
        if (!isModuleEntryMenu(menu)) {
            throw new PlatformException("Page bootstrap requires module entry menu: " + menuId);
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
            throw new PlatformException("Module menu is not visible or does not exist: " + validAlias);
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
                throw new PlatformException("Default UI config is not published in module snapshot: " + requestedUiConfigId);
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
                throw new PlatformException("Default UI config type must match page mode: " + pageMode);
            }
            if (config.getClientType() != clientType) {
                throw new PlatformException("Default UI config client type must match requested client type: "
                        + clientType);
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
                throw new PlatformException("Default query template is not published or enabled in module snapshot: "
                        + requestedTemplateId);
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
                actionBlocks(snapshot.moduleAlias(), selectedUiConfig),
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
                .orElseThrow(() -> new PlatformException("Default UI config is not published in module snapshot: "
                        + uiConfigId));
    }

    private List<PlatformAssociationBlock> associationBlocks(String moduleAlias, PlatformUiConfig config) {
        if (config == null) return List.of();
        String layoutJson = config.getLayoutJson();
        if (layoutJson == null || layoutJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(layoutJson);
        } catch (JsonProcessingException exception) {
            throw new PlatformException("UI config layout JSON cannot be decoded: " + config.getId());
        }
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
            resolved.add(new PlatformAssociationBlock(
                    config.getId(),
                    text(block, "key"),
                    viewCode,
                    text(block, "title"),
                    text(block, "uiConfigId"),
                    text(block, "queryTemplateId"),
                    "/" + moduleAlias + "/view/{id}/associations/" + viewCode + "/query"
            ));
        }
        return resolved;
    }

    private List<PlatformActionBlock> actionBlocks(String moduleAlias, PlatformUiConfig config) {
        if (config == null) return List.of();
        String layoutJson = config.getLayoutJson();
        if (layoutJson == null || layoutJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(layoutJson);
        } catch (JsonProcessingException exception) {
            throw new PlatformException("UI config layout JSON cannot be decoded: " + config.getId());
        }
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
                    localEditTargetUiConfigId(config, block, type),
                    localEditSubmitPath(moduleAlias, actionCode, type),
                    localEditRefreshStrategy(block, type),
                    positiveInteger(block, "width"),
                    positiveInteger(block, "height")
            ));
        }
        return resolved;
    }

    private String localEditTargetUiConfigId(PlatformUiConfig config, JsonNode block, String type) {
        if (!"localEdit".equals(type)) {
            return null;
        }
        String targetUiConfigId = text(block, "targetUiConfigId");
        if (targetUiConfigId != null) {
            return targetUiConfigId;
        }
        targetUiConfigId = text(block, "uiConfigId");
        return targetUiConfigId == null ? config.getId() : targetUiConfigId;
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
            throw new PlatformException("localEdit.refresh must be object");
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
            throw new PlatformException("localEdit.refresh." + field + " must be boolean");
        }
        return value.asBoolean();
    }

    private Integer positiveInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt() || value.asInt() <= 0) {
            throw new PlatformException("action block " + field + " must be positive integer");
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
                resolved.fieldForm() == null ? null : resolved.fieldForm().name(),
                field.getFieldUiControlAlias(),
                field.getVisible(),
                field.getReadOnly(),
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
            throw new PlatformException("Resolved page config references disabled or missing field UI controls: "
                    + missingAliases);
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
