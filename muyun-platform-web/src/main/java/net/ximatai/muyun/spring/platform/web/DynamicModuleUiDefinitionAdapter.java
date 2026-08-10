package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedPageConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformResolvedUiField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DynamicModuleUiDefinitionAdapter {
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
        return new ModuleUiDefinition(snapshot.moduleAlias(), views, List.of());
    }

    private static ScopedListWorkspaceDefinition scopedListWorkspace(PlatformUiConfig config,
                                                                      ModuleViewKind viewKind) {
        if (viewKind != ModuleViewKind.LIST || config.getScopeModuleAlias() == null
                || config.getScopeModuleAlias().isBlank()) {
            return null;
        }
        String createPolicy = config.getScopeCreatePolicy();
        return new ScopedListWorkspaceDefinition(config.getScopeModuleAlias(), config.getScopeField(),
                config.getScopeQueryCriteriaKey(), config.getScopeTitle(), config.getScopeSearchPlaceholder(),
                Boolean.TRUE.equals(config.getScopeShowItemSubtitle()),
                createPolicy == null || createPolicy.isBlank()
                        ? ScopedListWorkspaceCreatePolicy.ALLOW_UNSCOPED
                        : ScopedListWorkspaceCreatePolicy.valueOf(createPolicy));
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
                scopedListWorkspace(config, viewKind)
        );
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
