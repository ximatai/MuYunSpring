package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.platform.ui.PlatformPageContractType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog;

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
     * compiled main metadata field namespace; relation and virtual fields are intentionally out of
     * scope for this first management template.
     */
    public static ModuleUiDefinition fromPublishedRevision(PlatformPageDefinition page,
                                                           PlatformPresentationRevision revision,
                                                           Collection<String> mainEntityFieldNames) {
        return fromRevision(page, revision, revision == null ? null : revision.getUiTreeJson(),
                fieldTitles(mainEntityFieldNames), true);
    }

    /** Dynamic sources supply their metadata titles here; an explicit tree label still wins. */
    public static ModuleUiDefinition fromPublishedRevision(PlatformPageDefinition page,
                                                           PlatformPresentationRevision revision,
                                                           Map<String, String> mainEntityFieldTitles) {
        return fromRevision(page, revision, revision == null ? null : revision.getUiTreeJson(),
                mainEntityFieldTitles, true);
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
        return fromRevision(page, revision, uiTreeJson, fieldTitles(mainEntityFieldNames), false);
    }

    public static ModuleUiDefinition fromPreviewRevision(PlatformPageDefinition page,
                                                         PlatformPresentationRevision revision,
                                                         String uiTreeJson,
                                                         Map<String, String> mainEntityFieldTitles) {
        return fromRevision(page, revision, uiTreeJson, mainEntityFieldTitles, false);
    }

    private static ModuleUiDefinition fromRevision(PlatformPageDefinition page,
                                                   PlatformPresentationRevision revision,
                                                   String uiTreeJson,
                                                   Map<String, String> mainEntityFieldTitles,
                                                   boolean requirePublished) {
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
                || revision.getTemplateVersion() != PlatformPresentationTemplateCatalog.MANAGEMENT_VERSION) {
            throw new IllegalArgumentException("page revision requires management v1 template: " + revision.getId());
        }
        Map<String, String> fieldTitles = fieldTitles(mainEntityFieldTitles);
        Set<String> knownFields = knownMainFields(fieldTitles.keySet());
        Composition composition = composition(revision.getId(), uiTreeJson);
        Slot list = requireSlot(composition.slots(), "list", revision.getId());
        Slot form = requireSlot(composition.slots(), "form", revision.getId());
        return new ModuleUiDefinition(page.getModuleAlias(), List.of(),
                new ListDetailCardPageDefinition(null,
                        new PageListDefinition(composition.listSearchPlaceholder() == null ? list.title()
                                : composition.listSearchPlaceholder(),
                                view(ModuleUiViewCodes.DEFAULT_LIST, ModuleViewKind.LIST,
                                list, knownFields, fieldTitles)),
                        new PageDetailDefinition(null, form.title(), null,
                                view(ModuleUiViewCodes.DEFAULT_FORM, ModuleViewKind.FORM, form, knownFields, fieldTitles)),
                        new PageTraitsDefinition(null)),
                null, List.of(), List.of());
    }

    private static ViewDefinition view(String viewCode, ModuleViewKind viewKind, Slot slot, Set<String> knownFields,
                                       Map<String, String> fieldTitles) {
        List<ViewFieldDefinition> fields = slot.fields().stream()
                .map(field -> field(field, slot.slot(), knownFields, fieldTitles))
                .toList();
        return new ViewDefinition(viewCode, viewKind, ModuleUiClientType.WEB, slot.title(), fields,
                null, List.of(), List.of());
    }

    private static ViewFieldDefinition field(FieldNode field, String slot, Set<String> knownFields,
                                             Map<String, String> fieldTitles) {
        if (field.name() == null || field.name().isBlank()) {
            throw new IllegalArgumentException("management " + slot + " slot contains a blank field");
        }
        String normalized = field.name().trim();
        if (!knownFields.contains(normalized)) {
            throw new IllegalArgumentException("management " + slot + " slot references an unknown main entity field: "
                    + normalized);
        }
        ViewFieldDefinition.Builder builder = ViewFieldDefinition.field(normalized);
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
            if (slots.put(slot, new Slot(slot, title.trim(), List.copyOf(fields))) != null) {
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
        return new Composition(Map.copyOf(slots), searchPlaceholder);
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

    private record Slot(String slot, String title, List<FieldNode> fields) {
    }

    private record Composition(Map<String, Slot> slots, String listSearchPlaceholder) {
    }

    private record FieldNode(String name, String label, String width, String align, Integer columnSpan,
                             Boolean readOnly) {
    }
}
