package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedUiField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageLayoutNavigator;
import net.ximatai.muyun.spring.platform.ui.PlatformPageNavigatorLayout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DynamicModuleUiDefinitionAdapter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private DynamicModuleUiDefinitionAdapter() {
    }

    public static ModuleUiDefinition fromPublishedSnapshot(PlatformPageConfigSnapshot snapshot,
                                                           PlatformResolvedPageConfig resolvedPageConfig) {
        if (snapshot == null) {
            throw new IllegalArgumentException("platform page config snapshot must not be null");
        }
        if (resolvedPageConfig == null) {
            throw new IllegalArgumentException("platform resolved page config must not be null");
        }
        Map<String, PlatformUiSet> uiSets = snapshot.uiSets().stream()
                .collect(Collectors.toMap(PlatformUiSet::getId, Function.identity(), (left, ignored) -> left));
        Map<String, List<PlatformResolvedUiField>> fieldsByConfig = resolvedPageConfig.uiFields().stream()
                .collect(Collectors.groupingBy(PlatformResolvedUiField::uiConfigId,
                        java.util.LinkedHashMap::new,
                        Collectors.toList()));
        List<ViewDefinition> views = new ArrayList<>();
        snapshot.uiConfigs().stream()
                .filter(config -> Boolean.TRUE.equals(config.getPublished()))
                .filter(config -> !Boolean.FALSE.equals(config.getEnabled()))
                .filter(config -> config.getClientType() == PlatformUiClientType.WEB)
                .sorted(Comparator.comparing(PlatformUiConfig::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(config -> {
                    PlatformUiSet uiSet = uiSets.get(config.getUiSetId());
                    ModuleViewKind viewKind = viewKind(uiSet);
                    if (viewKind == null) {
                        return;
                    }
                    views.add(view(config, uiSet, viewKind, fieldsByConfig.get(config.getId())));
                });
        ViewDefinition list = views.stream().filter(view -> view.viewKind() == ModuleViewKind.LIST).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("dynamic page requires a published list slot: "
                        + snapshot.moduleAlias()));
        ViewDefinition editor = views.stream().filter(view -> view.viewKind() == ModuleViewKind.FORM).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("dynamic page requires a published form editor slot: "
                        + snapshot.moduleAlias()));
        PlatformUiConfig listConfig = snapshot.uiConfigs().stream()
                .filter(config -> Objects.equals(config.getId(), list.sourceUiConfigId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("dynamic list source config is missing: " + list.viewCode()));
        ModulePageDefinition page = page(list, editor, listConfig);
        return new ModuleUiDefinition(snapshot.moduleAlias(), List.of(), page, null, List.of(), List.of());
    }

    private static ViewDefinition view(PlatformUiConfig config,
                                       PlatformUiSet uiSet,
                                       ModuleViewKind viewKind,
                                       List<PlatformResolvedUiField> fields) {
        return new ViewDefinition(
                uiSet.getAlias(),
                viewKind,
                ModuleUiClientType.WEB,
                viewTitle(config, uiSet),
                fields(fields),
                config.getId(),
                null
        );
    }

    private static ModulePageDefinition page(ViewDefinition list, ViewDefinition editor, PlatformUiConfig listConfig) {
        JsonNode root = pageRoot(listConfig);
        String template = root.path("template").asText();
        if ("FLAT_MANAGEMENT".equals(template)) {
            JsonNode explorer = root.path("explorer");
            JsonNode detail = root.path("detail");
            return new FlatManagementPageDefinition(navigator(listConfig), new PageExplorerDefinition(
                    explorer.path("title").asText(list.title()),
                    explorer.path("searchPlaceholder").asText(null), explorer.path("emptyDescription").asText(null),
                    explorer.path("recordLabel").asText(null), explorer.path("fallbackTitle").asText(null),
                    explorer.path("titleField").asText("title"), explorer.path("secondaryField").asText(null),
                    explorer.path("mutedWhenDisabled").asBoolean(false)),
                    new PageDetailDefinition(detail.path("emptyDescription").asText(null),
                            detail.path("createTitle").asText(editor.title()), null, editor,
                            workspaceView(detail)),
                    traits(root));
        }
        if ("TREE_MANAGEMENT".equals(template)) {
            return new TreeManagementPageDefinition(
                    new PageDetailDefinition(null, editor.title(), null, editor, workspaceView(root.path("detail"))), traits(root));
        }
        if (!"LIST_DETAIL_CARD".equals(template)) {
            throw new IllegalArgumentException("dynamic page template must be FLAT_MANAGEMENT, LIST_DETAIL_CARD or TREE_MANAGEMENT: "
                    + listConfig.getId());
        }
        PageNavigatorDefinition navigator = navigator(listConfig);
        return new ListDetailCardPageDefinition(navigator, new PageListDefinition(list.title(), list),
                new PageDetailDefinition(null, editor.title(), null, editor, workspaceView(root.path("detail"))),
                traits(root));
    }

    private static PageDetailWorkspaceViewDefinition workspaceView(JsonNode detail) {
        String type = detail.path("workspaceView").path("type").asText(null);
        return type == null || type.isBlank() ? null : new PageDetailWorkspaceViewDefinition(type);
    }

    private static PageNavigatorDefinition navigator(PlatformUiConfig config) {
        PlatformPageNavigatorLayout navigator = PlatformPageLayoutNavigator.navigator(config);
        if (navigator == null) return null;
        return new PageNavigatorDefinition(navigator.levels().stream().map(level -> new PageNavigatorLevelDefinition(
                level.key(), PageNavigatorKind.valueOf(level.kind()), level.sourceModuleAlias(), level.title(),
                level.searchPlaceholder(), level.queryBindings().stream()
                .map(binding -> new PageNavigatorQueryBindingDefinition(binding.field(), binding.queryCriteriaKey()))
                .toList(), level.childBindings().stream()
                .map(binding -> new PageNavigatorChildBindingDefinition(
                        binding.childLevelKey(), binding.childQueryCriteriaKey()))
                .toList(), level.management() == null ? null
                        : new PageNavigatorManagementDefinition(level.management().editorSurface()))).toList());
    }

    private static JsonNode pageRoot(PlatformUiConfig config) {
        if (config.getLayoutJson() == null || config.getLayoutJson().isBlank()) {
            throw new IllegalArgumentException("dynamic list config must declare a page root layout: " + config.getId());
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(config.getLayoutJson());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("dynamic page root layout must be an object: " + config.getId());
            }
            return root;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("dynamic page root layout cannot be decoded: " + config.getId(), exception);
        }
    }

    private static PageTraitsDefinition traits(JsonNode root) {
        java.util.LinkedHashSet<PageTrait> traits = new java.util.LinkedHashSet<>();
        JsonNode values = root.path("traits");
        if (values.isArray()) {
            values.forEach(value -> traits.add(PageTrait.valueOf(value.asText())));
        }
        return new PageTraitsDefinition(traits);
    }

    private static List<ViewFieldDefinition> fields(List<PlatformResolvedUiField> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        return fields.stream()
                .filter(field -> field.fieldName() != null && !field.fieldName().isBlank())
                .map(DynamicModuleUiDefinitionAdapter::field)
                .toList();
    }

    private static ViewFieldDefinition field(PlatformResolvedUiField field) {
        if ("booleanStatus".equals(field.fieldUiControlAlias())) {
            throw new IllegalArgumentException("dynamic field " + field.fieldName()
                    + " cannot use uiType booleanStatus until dynamic UI configuration declares its presentation");
        }
        if ("fileTransfer".equals(field.fieldUiControlAlias())
                || "file_transfer".equals(field.fieldUiControlAlias())) {
            throw new IllegalArgumentException("dynamic field " + field.fieldName()
                    + " cannot use file transfer until the unified file-reference lifecycle is available");
        }
        return new ViewFieldDefinition(
                new ViewFieldRef(field.relationAlias(), field.fieldName(), field.moduleMetadataFieldId()),
                field.fieldTitle(),
                UiRule.constant(field.visible() == null ? Boolean.TRUE : field.visible()),
                UiRule.constant(field.requiredOverride() == null ? Boolean.FALSE : field.requiredOverride()),
                UiRule.constant(field.readOnly() == null ? Boolean.FALSE : field.readOnly()),
                uiType(field),
                valuePresentation(field),
                width(field),
                field.columnSpan(),
                field.align(),
                field.fixedPosition() == null ? null : Boolean.TRUE,
                null,
                field.maxDisplayLines()
        );
    }

    private static String uiType(PlatformResolvedUiField field) {
        return "file_size".equals(field.fieldUiControlAlias()) ? null : field.fieldUiControlAlias();
    }

    private static FieldValuePresentation valuePresentation(PlatformResolvedUiField field) {
        return "file_size".equals(field.fieldUiControlAlias()) ? FieldValuePresentation.FILE_SIZE : null;
    }

    private static String width(PlatformResolvedUiField field) {
        return field.width() == null ? null : field.width() + "px";
    }

    private static String viewTitle(PlatformUiConfig config, PlatformUiSet uiSet) {
        if (config.getTitle() != null && !config.getTitle().isBlank()) {
            return config.getTitle();
        }
        return uiSet == null ? null : uiSet.getTitle();
    }

    private static ModuleViewKind viewKind(PlatformUiSet uiSet) {
        if (uiSet == null || uiSet.getSetType() == null) {
            return null;
        }
        if (Objects.equals(uiSet.getSetType(), PlatformUiSetType.LIST)) {
            return ModuleViewKind.LIST;
        }
        if (Objects.equals(uiSet.getSetType(), PlatformUiSetType.FORM)) {
            return ModuleViewKind.FORM;
        }
        if (Objects.equals(uiSet.getSetType(), PlatformUiSetType.DETAIL)) {
            return ModuleViewKind.DETAIL;
        }
        return null;
    }
}
