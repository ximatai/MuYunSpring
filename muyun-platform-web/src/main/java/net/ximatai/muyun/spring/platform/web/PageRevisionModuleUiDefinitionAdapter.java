package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.platform.ui.PlatformPageContractType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog;
import net.ximatai.muyun.spring.platform.module.DynamicModuleOverviewMode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles the published {@code management v1} composition into the source-neutral page DSL.
 *
 * <p>The persisted tree deliberately has only two configurable slots.  This is the stable exchange
 * contract for the first visual composer and preview; later templates receive their own adapters
 * instead of teaching this adapter generic tree semantics:</p>
 *
 * <pre>{@code
 * {
 *   "template": "management", "templateVersion": 1,
 *   "nodes": [
 *     {"slot": "list", "title": "客户", "fields": ["name", "code"]},
 *     {"slot": "form", "title": "编辑客户", "fields": ["name", "code"]}
 *   ]
 * }
 * }</pre>
 */
public final class PageRevisionModuleUiDefinitionAdapter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PageRevisionModuleUiDefinitionAdapter() {
    }

    /**
     * Converts one effective published revision. {@code mainEntityFieldNames} is the already
     * compiled main metadata field namespace. The form slot may additionally select a direct
     * child association from the supplied runtime catalog; virtual fields remain out of scope.
     */
    public static ModuleUiDefinition fromPublishedRevision(PlatformPageDefinition page,
                                                           PlatformPresentationRevision revision,
                                                           Collection<String> mainEntityFieldNames) {
        return fromRevision(page, revision, revision == null ? null : revision.getUiTreeJson(),
                fieldTitles(mainEntityFieldNames), Map.of(), true);
    }

    /** Dynamic sources supply their metadata titles here; an explicit tree label still wins. */
    public static ModuleUiDefinition fromPublishedRevision(PlatformPageDefinition page,
                                                           PlatformPresentationRevision revision,
                                                           Map<String, String> mainEntityFieldTitles) {
        return fromRevision(page, revision, revision == null ? null : revision.getUiTreeJson(),
                mainEntityFieldTitles, Map.of(), true);
    }

    /** Dynamic page revisions may place only server-known association views into the detail slot. */
    public static ModuleUiDefinition fromPublishedRevision(PlatformPageDefinition page,
                                                           PlatformPresentationRevision revision,
                                                           Map<String, String> mainEntityFieldTitles,
                                                           Map<String, DynamicAssociationViewDescriptor> associations) {
        return fromRevision(page, revision, revision == null ? null : revision.getUiTreeJson(),
                mainEntityFieldTitles, associations, true);
    }

    /**
     * Compiles a transient tree against an existing, visible revision identity. The caller owns
     * template-catalog validation and revision visibility; this adapter owns only the source-neutral
     * management DSL conversion and dynamic main-field namespace check.
     */
    public static ModuleUiDefinition fromPreviewRevision(PlatformPageDefinition page,
                                                         PlatformPresentationRevision revision,
                                                         String uiTreeJson,
                                                         Collection<String> mainEntityFieldNames) {
        return fromRevision(page, revision, uiTreeJson, fieldTitles(mainEntityFieldNames), Map.of(), false);
    }

    public static ModuleUiDefinition fromPreviewRevision(PlatformPageDefinition page,
                                                         PlatformPresentationRevision revision,
                                                         String uiTreeJson,
                                                         Map<String, String> mainEntityFieldTitles) {
        return fromRevision(page, revision, uiTreeJson, mainEntityFieldTitles, Map.of(), false);
    }

    public static ModuleUiDefinition fromPreviewRevision(PlatformPageDefinition page,
                                                         PlatformPresentationRevision revision,
                                                         String uiTreeJson,
                                                         Map<String, String> mainEntityFieldTitles,
                                                         Map<String, DynamicAssociationViewDescriptor> associations) {
        return fromRevision(page, revision, uiTreeJson, mainEntityFieldTitles, associations, false);
    }

    public static ModuleUiDefinition fromPublishedRevision(PlatformPageDefinition page,
                                                           PlatformPresentationRevision revision,
                                                           DynamicPageCompilationContext context) {
        return fromRevision(page, revision, revision == null ? null : revision.getUiTreeJson(),
                context.mainFieldTitles(), context.associations(), true, context.overviewMode(),
                context.requiredMainFields());
    }

    public static ModuleUiDefinition fromPreviewRevision(PlatformPageDefinition page,
                                                         PlatformPresentationRevision revision,
                                                         String uiTreeJson,
                                                         DynamicPageCompilationContext context) {
        return fromRevision(page, revision, uiTreeJson, context.mainFieldTitles(), context.associations(), false,
                context.overviewMode(), context.requiredMainFields());
    }

    private static ModuleUiDefinition fromRevision(PlatformPageDefinition page,
                                                   PlatformPresentationRevision revision,
                                                   String uiTreeJson,
                                                   Map<String, String> mainEntityFieldTitles,
                                                   Map<String, DynamicAssociationViewDescriptor> associations,
                                                   boolean requirePublished) {
        return fromRevision(page, revision, uiTreeJson, mainEntityFieldTitles, associations, requirePublished,
                DynamicModuleOverviewMode.LIST_CARD, Set.of());
    }

    private static ModuleUiDefinition fromRevision(PlatformPageDefinition page,
                                                   PlatformPresentationRevision revision,
                                                   String uiTreeJson,
                                                   Map<String, String> mainEntityFieldTitles,
                                                   Map<String, DynamicAssociationViewDescriptor> associations,
                                                   boolean requirePublished,
                                                   DynamicModuleOverviewMode overviewMode,
                                                   Set<String> requiredMainFieldNames) {
        if (page == null) {
            throw new IllegalArgumentException("page definition must not be null");
        }
        if (page.getContractType() != PlatformPageContractType.MANAGEMENT) {
            throw new IllegalArgumentException("management presentation requires a management page contract: "
                    + page.getId());
        }
        if (revision == null) {
            throw new IllegalArgumentException("presentation revision must not be null");
        }
        if (requirePublished && revision.getStatus() != PlatformPresentationRevisionStatus.PUBLISHED) {
            throw new IllegalArgumentException("page revision must be published before runtime compilation: "
                    + revision.getId());
        }
        if (!PlatformPresentationTemplateCatalog.MANAGEMENT_ALIAS.equals(revision.getTemplateAlias())
                || revision.getTemplateVersion() == null
                || !Set.of(PlatformPresentationTemplateCatalog.MANAGEMENT_VERSION, PlatformPresentationTemplateCatalog.MODE_AWARE_VERSION, PlatformPresentationTemplateCatalog.MODE_AWARE_ACTION_VERSION).contains(revision.getTemplateVersion())) {
            throw new IllegalArgumentException("page revision requires management v1 template: " + revision.getId());
        }
        Map<String, String> fieldTitles = fieldTitles(mainEntityFieldTitles);
        Set<String> knownFields = knownMainFields(fieldTitles.keySet());
        Set<String> requiredFields = requiredMainFieldNames == null ? Set.of() : Set.copyOf(requiredMainFieldNames);
        Composition composition = composition(revision.getId(), uiTreeJson);
        Slot list = requireSlot(composition.slots(), "list", revision.getId());
        Slot form = requireSlot(composition.slots(), "form", revision.getId());
        ViewDefinition listView = view(ModuleUiViewCodes.DEFAULT_LIST, ModuleViewKind.LIST,
                list, knownFields, fieldTitles, requiredFields);
        PageDetailDefinition detail = new PageDetailDefinition(null, form.title(), null,
                view(ModuleUiViewCodes.DEFAULT_FORM, ModuleViewKind.FORM, form, knownFields, fieldTitles, requiredFields));
        if (composition.quickSearchFields() != null && !knownFields.containsAll(composition.quickSearchFields()))
            throw new IllegalArgumentException("Unknown quick search field");
        PageExplorerDefinition explorer = composition.explorer();
        if (explorer != null) {
            for (String field : java.util.stream.Stream.of(explorer.titleField(), explorer.secondaryField())
                    .filter(java.util.Objects::nonNull).toList()) {
                if (!knownFields.contains(field)) throw new IllegalArgumentException("Unknown explorer field: " + field);
            }
        }
        String searchPlaceholder = composition.listSearchPlaceholder() == null ? list.title()
                : composition.listSearchPlaceholder();
        ModulePageDefinition pageDefinition = switch (composition.mode() != null ? composition.mode() : overviewMode == null
                ? DynamicModuleOverviewMode.LIST_CARD : overviewMode) {
            case TREE_CARD -> new TreeManagementPageDefinition(null, null, detail, new PageTraitsDefinition(null), explorer, composition.quickSearchFields());
            case MICRO_LIST_CARD -> new FlatManagementPageDefinition(null,
                    explorer != null ? explorer : new PageExplorerDefinition(list.title(), searchPlaceholder, null, null, null, "title", null, false),
                    detail, new PageTraitsDefinition(null), composition.quickSearchFields());
            case LIST_CARD -> new ListDetailCardPageDefinition(null,
                    new PageListDefinition(searchPlaceholder, listView), detail, new PageTraitsDefinition(null), composition.quickSearchFields());
        };
        return new ModuleUiDefinition(page.getModuleAlias(), List.of(), pageDefinition,
                null, List.of(), List.of(), detailRelations(form, associations), composition.pageActions());
    }

    private static List<PageDetailRelationDefinition> detailRelations(
            Slot form, Map<String, DynamicAssociationViewDescriptor> associations) {
        Map<String, DynamicAssociationViewDescriptor> known = associations == null ? Map.of() : associations;
        return form.relations().stream().map(relation -> {
            DynamicAssociationViewDescriptor view = known.get(relation.code());
            if (view == null) {
                throw new IllegalArgumentException("management form references an unknown association view: "
                        + relation.code());
            }
            if (view.relationCode() == null || view.relationCode().isBlank()) {
                throw new IllegalArgumentException("management form association must be a child relation: "
                        + relation.code());
            }
            return new PageDetailRelationDefinition(view.code(), relation.title(), view.targetEntityAlias(),
                    view.relationCode(), true, true, relation.fields().stream().map(FieldNode::name).toList())
                    .withColumnProperties(relation.fields().stream().collect(java.util.stream.Collectors.toMap(
                            FieldNode::name, field -> new PageDetailRelationColumnProperties(field.label(),
                                    field.width() == null ? null : Integer.valueOf(field.width().replace("px", "")),
                                    field.align()))));
        }).toList();
    }

    private static ViewDefinition view(String viewCode, ModuleViewKind viewKind, Slot slot, Set<String> knownFields,
                                       Map<String, String> fieldTitles, Set<String> requiredFields) {
        List<ViewFieldDefinition> fields = slot.fields().stream()
                .map(field -> field(field, slot.slot(), knownFields, fieldTitles, requiredFields))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        List<FormGroupDefinition> groups = "form".equals(slot.slot())
                ? slot.groups().stream().map(group -> {
                    List<ViewFieldDefinition> groupFields = group.fields().stream()
                            .map(field -> field(field, slot.slot(), knownFields, fieldTitles, requiredFields)).toList();

                    return new FormGroupDefinition(group.code(), group.title(), group.subtitle(), groupFields);
                }).toList()
                : List.of();
        if ("form".equals(slot.slot())) {
            Map<String, ViewFieldDefinition> rootFields = new LinkedHashMap<>();
            for (int index = 0; index < slot.fields().size(); index++) rootFields.put(slot.fields().get(index).name(), fields.get(index));
            Map<String, FormGroupDefinition> byCode = new LinkedHashMap<>();
            groups.forEach(group -> byCode.put(group.groupCode(), group));
            fields.clear();
            for (var item : slot.order()) {
                if (item.field() != null) fields.add(rootFields.get(item.field()));
                else fields.addAll(byCode.get(item.group()).fields());
            }
        }
        return new ViewDefinition(viewCode, viewKind, ModuleUiClientType.WEB, slot.title(), fields,
                null, groups, List.of());
    }

    private static ViewFieldDefinition field(FieldNode field, String slot, Set<String> knownFields,
                                             Map<String, String> fieldTitles, Set<String> requiredFields) {
        if (field.name() == null || field.name().isBlank()) {
            throw new IllegalArgumentException("management " + slot + " slot contains a blank field");
        }
        String normalized = field.name().trim();
        if (!knownFields.contains(normalized)) {
            throw new IllegalArgumentException("management " + slot + " slot references an unknown main entity field: "
                    + normalized);
        }
        ViewFieldDefinition.Builder builder = ViewFieldDefinition.field(normalized);
        if (requiredFields.contains(normalized)) builder.required();
        String label = field.label() == null ? fieldTitles.get(normalized) : field.label();
        if (label != null) {
            builder.label(label);
        }
        if ("list".equals(slot)) {
            if (field.width() != null) {
                builder.width(field.width());
            }
            if (field.align() != null) {
                builder.align(field.align());
            }
        } else {
            if (field.columnSpan() != null) {
                builder.columnSpan(field.columnSpan());
            }
            if (Boolean.TRUE.equals(field.readOnly())) {
                builder.readOnly();
            }
        }
        return builder.build();
    }

    private static Set<String> knownMainFields(Collection<String> fields) {
        if (fields == null) {
            throw new IllegalArgumentException("main entity field names must not be null");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("main entity field names must not contain blank values");
            }
            normalized.add(field.trim());
        }
        return Set.copyOf(normalized);
    }

    private static Map<String, String> fieldTitles(Collection<String> fields) {
        if (fields == null) {
            throw new IllegalArgumentException("main entity field names must not be null");
        }
        LinkedHashMap<String, String> titles = new LinkedHashMap<>();
        for (String field : fields) {
            titles.put(field, null);
        }
        return fieldTitles(titles);
    }

    private static Map<String, String> fieldTitles(Map<String, String> fields) {
        if (fields == null) {
            throw new IllegalArgumentException("main entity field titles must not be null");
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String field = entry.getKey();
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("main entity field names must not contain blank values");
            }
            String title = entry.getValue();
            normalized.put(field.trim(), title == null || title.isBlank() ? null : title.trim());
        }
        return java.util.Collections.unmodifiableMap(normalized);
    }

    private static Composition composition(String revisionId, String uiTreeJson) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(uiTreeJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("management page revision UI tree must be valid JSON: " + revisionId,
                    exception);
        }
        DynamicModuleOverviewMode mode = null;
        PageExplorerDefinition explorer = null;
        List<String> quickSearchFields = null;
        List<PageActionDefinition> pageActions = List.of();
        if (root != null && Set.of(PlatformPresentationTemplateCatalog.MODE_AWARE_VERSION,
                PlatformPresentationTemplateCatalog.MODE_AWARE_ACTION_VERSION).contains(root.path("templateVersion").asInt())) {
            JsonNode normalized = PlatformPresentationTemplateCatalog.validateModeAwareTree(root);
            quickSearchFields = new java.util.ArrayList<>();
            for (JsonNode field : root.path("quickSearchFields")) quickSearchFields.add(field.asText());
            if (root.path("templateVersion").asInt() == PlatformPresentationTemplateCatalog.MODE_AWARE_ACTION_VERSION) {
                pageActions = new java.util.ArrayList<>();
                for (JsonNode action : root.path("actions")) {
                    pageActions.add(new PageActionDefinition(action.path("actionCode").asText(),
                            PageActionAnchor.valueOf(action.path("anchor").asText().toUpperCase(java.util.Locale.ROOT))));
                }
            }
            mode = DynamicModuleOverviewMode.valueOf(root.path("mode").asText());
            for (JsonNode node : root.path("nodes")) {
                if ("explorer".equals(node.path("slot").asText())) {
                    explorer = new PageExplorerDefinition(node.path("title").asText(),
                            root.path("props").path("list").path("searchPlaceholder").asText(null),
                            null, null, null, node.path("titleField").asText(), node.path("secondaryField").asText(null), false);
                }
            }
            root = normalized;
        }
        if (root == null || !root.isObject()
                || !PlatformPresentationTemplateCatalog.MANAGEMENT_ALIAS.equals(root.path("template").asText())
                || root.path("templateVersion").asInt(-1) != PlatformPresentationTemplateCatalog.MANAGEMENT_VERSION
                || !root.path("nodes").isArray()) {
            throw new IllegalArgumentException("management page revision UI tree does not match management v1: "
                    + revisionId);
        }
        LinkedHashMap<String, Slot> slots = new LinkedHashMap<>();
        for (JsonNode node : root.path("nodes")) {
            String slot = node.path("slot").asText(null);
            if (!"list".equals(slot) && !"form".equals(slot)) {
                throw new IllegalArgumentException("management page revision declares an unsupported slot: " + slot);
            }
            String title = node.path("title").asText(null);
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("management " + slot + " slot requires a title");
            }
            JsonNode fieldNodes = node.path("fields");
            if (!fieldNodes.isArray()) {
                throw new IllegalArgumentException("management " + slot + " slot requires a fields array");
            }
            List<FieldNode> fields = new java.util.ArrayList<>();
            fieldNodes.forEach(field -> fields.add(fieldNode(field, slot)));
            if (fields.stream().map(FieldNode::name).collect(java.util.stream.Collectors.toSet()).size() != fields.size()) {
                throw new IllegalArgumentException("management " + slot + " slot contains duplicate fields");
            }
            List<RelationNode> relations = "form".equals(slot) ? relationNodes(node.path("relations")) : List.of();
            List<GroupNode> groups = "form".equals(slot) ? groupNodes(node.path("groups"), slot) : List.of();
            Set<String> allFields = new LinkedHashSet<>(fields.stream().map(FieldNode::name).toList());
            groups.forEach(group -> group.fields().forEach(field -> allFields.add(field.name())));
            if (allFields.size() != fields.size() + groups.stream().mapToInt(group -> group.fields().size()).sum()) {
                throw new IllegalArgumentException("management form slot contains duplicate fields");
            }
            if (slots.put(slot, new Slot(slot, title.trim(), List.copyOf(fields), relations, groups, "form".equals(slot) ? PlatformPresentationTemplateCatalog.managementFormOrder(node) : List.of())) != null) {
                throw new IllegalArgumentException("management page revision declares duplicate " + slot + " slot");
            }
        }
        JsonNode listProperties = root.path("props").path("list");
        String searchPlaceholder = listProperties.path("searchPlaceholder").asText(null);
        if (searchPlaceholder != null) {
            searchPlaceholder = searchPlaceholder.trim();
            if (searchPlaceholder.isEmpty()) {
                searchPlaceholder = null;
            }
        }
        return new Composition(Map.copyOf(slots), searchPlaceholder, mode, explorer, quickSearchFields, pageActions);
    }

    private static List<RelationNode> relationNodes(JsonNode nodes) {
        if (nodes.isMissingNode()) return List.of();
        List<RelationNode> values = new java.util.ArrayList<>();
        nodes.forEach(node -> values.add(new RelationNode(node.path("relation").asText(null),
                node.path("title").asText(null), relationFields(node.path("fields")))));
        if (values.stream().map(RelationNode::code).collect(java.util.stream.Collectors.toSet()).size() != values.size()) {
            throw new IllegalArgumentException("management form slot contains duplicate relations");
        }
        return List.copyOf(values);
    }

    private static List<GroupNode> groupNodes(JsonNode nodes, String slot) {
        if (nodes.isMissingNode()) return List.of();
        List<GroupNode> values = new java.util.ArrayList<>();
        nodes.forEach(node -> {
            List<FieldNode> fields = new java.util.ArrayList<>();
            node.path("fields").forEach(field -> fields.add(fieldNode(field, slot)));
            values.add(new GroupNode(node.path("group").asText(null), node.path("title").asText(null),
                    node.path("subtitle").asText(null), List.copyOf(fields)));
        });
        if (values.stream().map(GroupNode::code).collect(java.util.stream.Collectors.toSet()).size() != values.size()) {
            throw new IllegalArgumentException("management form slot contains duplicate groups");
        }
        return List.copyOf(values);
    }

    private static List<FieldNode> relationFields(JsonNode fields) {
        if (fields.isMissingNode()) return List.of();
        List<FieldNode> values = new java.util.ArrayList<>();
        fields.forEach(field -> values.add(fieldNode(field, "list")));
        if (values.stream().anyMatch(field -> field.name() == null || field.name().isBlank())
                || values.stream().map(FieldNode::name).collect(java.util.stream.Collectors.toSet()).size() != values.size()) {
            throw new IllegalArgumentException("management form relation contains invalid fields");
        }
        return List.copyOf(values);
    }

    private static Slot requireSlot(Map<String, Slot> slots, String slot, String revisionId) {
        Slot value = slots.get(slot);
        if (value == null) {
            throw new IllegalArgumentException("management page revision requires a " + slot + " slot: " + revisionId);
        }
        return value;
    }

    private static FieldNode fieldNode(JsonNode node, String slot) {
        if (node.isTextual()) {
            return new FieldNode(node.asText(), null, null, null, null, null);
        }
        if (!node.isObject()) {
            return new FieldNode(null, null, null, null, null, null);
        }
        JsonNode properties = node.path("props");
        return new FieldNode(node.path("field").asText(null), properties.path("label").asText(null),
                properties.path("width").asText(null), properties.path("align").asText(null),
                properties.has("columnSpan") ? properties.path("columnSpan").asInt() : null,
                properties.has("readOnly") ? properties.path("readOnly").asBoolean() : null);
    }

    private record Slot(String slot, String title, List<FieldNode> fields, List<RelationNode> relations,
                        List<GroupNode> groups, List<PlatformPresentationTemplateCatalog.ManagementFormEntry> order) {
    }

    private record RelationNode(String code, String title, List<FieldNode> fields) {
    }

    private record GroupNode(String code, String title, String subtitle, List<FieldNode> fields) {
    }

    private record Composition(Map<String, Slot> slots, String listSearchPlaceholder,
                               DynamicModuleOverviewMode mode, PageExplorerDefinition explorer, List<String> quickSearchFields,
                               List<PageActionDefinition> pageActions) {
    }

    private record FieldNode(String name, String label, String width, String align, Integer columnSpan,
                             Boolean readOnly) {
    }
}
