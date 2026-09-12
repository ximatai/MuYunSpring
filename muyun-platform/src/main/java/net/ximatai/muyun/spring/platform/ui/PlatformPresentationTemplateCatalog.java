package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Registry for platform-owned composition roots.  A revision supplies nodes only inside one
 * registered root; it cannot invent a client surface at publish time.
 */
@Component
public class PlatformPresentationTemplateCatalog {
    public static final String MANAGEMENT_ALIAS = "management";
    public static final int MANAGEMENT_VERSION = 1;
    public static final int MODE_AWARE_VERSION = 2;
    /** Adds fixed, platform-owned action anchors while retaining the v2 page skeleton. */
    public static final int MODE_AWARE_ACTION_VERSION = 3;
    public static final int MANAGED_ACTION_VERSION = 4;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final PlatformPresentationTemplate MANAGEMENT = new PlatformPresentationTemplate(
            MANAGEMENT_ALIAS, MANAGEMENT_VERSION, PlatformPresentationClientType.WEB,
            java.util.Set.of(PlatformPageContractType.MANAGEMENT),
            "{\"template\":\"management\",\"templateVersion\":1,\"nodes\":[]}");
    private static final Map<String, List<PlatformPresentationTemplate>> TEMPLATES = Map.of(
            MANAGEMENT_ALIAS, List.of(MANAGEMENT, new PlatformPresentationTemplate(
                    MANAGEMENT_ALIAS, MODE_AWARE_VERSION, PlatformPresentationClientType.WEB,
                    java.util.Set.of(PlatformPageContractType.MANAGEMENT),
                    "{\"template\":\"management\",\"templateVersion\":2,\"mode\":\"LIST_CARD\",\"quickSearchFields\":[],\"nodes\":[{\"slot\":\"list\",\"title\":\"记录列表\",\"fields\":[]},{\"slot\":\"form\",\"title\":\"详情 / 表单\",\"fields\":[]}]}"),
                    new PlatformPresentationTemplate(MANAGEMENT_ALIAS, MODE_AWARE_ACTION_VERSION,
                            PlatformPresentationClientType.WEB, java.util.Set.of(PlatformPageContractType.MANAGEMENT),
                            "{\"template\":\"management\",\"templateVersion\":3,\"mode\":\"LIST_CARD\",\"quickSearchFields\":[],\"actions\":[],\"nodes\":[{\"slot\":\"list\",\"title\":\"记录列表\",\"fields\":[]},{\"slot\":\"form\",\"title\":\"详情 / 表单\",\"fields\":[]}]}"),
                    new PlatformPresentationTemplate(MANAGEMENT_ALIAS, MANAGED_ACTION_VERSION,
                            PlatformPresentationClientType.WEB, java.util.Set.of(PlatformPageContractType.MANAGEMENT),
                            "{\"template\":\"management\",\"templateVersion\":4,\"mode\":\"LIST_CARD\",\"quickSearchFields\":[],\"actions\":[],\"nodes\":[{\"slot\":\"list\",\"title\":\"记录列表\",\"fields\":[]},{\"slot\":\"form\",\"title\":\"详情 / 表单\",\"fields\":[]}]}")));

    public record ManagementSkeleton(String mode, String title, String navigationTitle,
                                     String fieldGroupTitle, boolean columns, int maxIdentityFields) {}

    public static List<ManagementSkeleton> managementSkeletons() {
        return List.of(
                new ManagementSkeleton("TREE_CARD", "树 + 卡片", "树导航", "节点展示", false, 2),
                new ManagementSkeleton("LIST_CARD", "列表 + 卡片", "记录列表", "列表展示字段", true, 0),
                new ManagementSkeleton("MICRO_LIST_CARD", "微列表 + 卡片", "记录导航", "条目展示", false, 2));
    }

    public List<PlatformPresentationTemplate> listFor(PlatformPresentationClientType clientType,
                                                      PlatformPageContractType pageContractType) {
        return TEMPLATES.values().stream().flatMap(List::stream)
                .filter(template -> template.clientType() == clientType)
                .filter(template -> template.supportedPageContracts().contains(pageContractType)).toList();
    }

    public PlatformPresentationTemplate require(String alias, Integer version,
                                                PlatformPresentationClientType clientType,
                                                PlatformPageContractType pageContractType) {
        PlatformPresentationTemplate template = TEMPLATES.getOrDefault(alias, List.of()).stream()
                .filter(candidate -> candidate.version() == (version == null ? -1 : version))
                .findFirst().orElseThrow(() -> BusinessExceptions.warning(
                        "platform.presentation-template.not-found",
                        "Presentation template is not registered: " + alias + " v" + version));
        if (template.clientType() != clientType) {
            throw BusinessExceptions.warning("platform.presentation-template.client-unsupported",
                    "Presentation template does not support client: " + clientType);
        }
        if (!template.supportedPageContracts().contains(pageContractType)) {
            throw BusinessExceptions.warning("platform.presentation-template.page-contract-unsupported",
                    "Presentation template does not support page contract: " + pageContractType);
        }
        return template;
    }

    public void validateUiTree(PlatformPresentationRevision revision,
                               PlatformPresentationTemplate template) {
        validateUiTree(revision == null ? null : revision.getUiTreeJson(), template);
    }

    /** Validates an unsaved tree against the same template schema used by publication. */
    public void validateUiTree(String uiTreeJson, PlatformPresentationTemplate template) {
        if (uiTreeJson == null || uiTreeJson.isBlank()) {
            throw BusinessExceptions.warning("platform.presentation-revision.ui-tree-required",
                    "Presentation revision UI tree is required");
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(uiTreeJson);
        } catch (JsonProcessingException exception) {
            throw BusinessExceptions.warning("platform.presentation-revision.ui-tree-invalid",
                    "Presentation revision UI tree must be valid JSON");
        }
        if (root == null || !root.isObject() || !Objects.equals(template.alias(), root.path("template").asText())
                || template.version() != root.path("templateVersion").asInt(-1)
                || !root.path("nodes").isArray()) {
            throw BusinessExceptions.warning("platform.presentation-revision.ui-tree-template-mismatch",
                    "Presentation revision UI tree does not match its template contract");
        }
        if (template.version() == MODE_AWARE_VERSION || template.version() == MODE_AWARE_ACTION_VERSION || template.version() == MANAGED_ACTION_VERSION) {
            validateModeAwareTree(root);
        } else if (MANAGEMENT_ALIAS.equals(template.alias())) {
            validateManagementTree(root);
        }
    }

    /** Fixed navigation slots; the form contract remains shared across all three modes. */
    public static JsonNode validateModeAwareTree(JsonNode root) {
        if (root == null || !root.isObject()) throw invalidManagementTree();
        int version = root.path("templateVersion").asInt(-1);
        if (!Set.of(MODE_AWARE_VERSION, MODE_AWARE_ACTION_VERSION, MANAGED_ACTION_VERSION).contains(version)) throw invalidManagementTree();
        JsonNode searchFields = root.path("quickSearchFields");
        if (!searchFields.isArray()) throw invalidManagementTree();
        Set<String> uniqueSearchFields = new java.util.LinkedHashSet<>();
        for (JsonNode field : searchFields) {
            if (!field.isTextual() || field.asText().isBlank() || !uniqueSearchFields.add(field.asText()))
                throw invalidManagementTree();
        }
        String mode = root.path("mode").asText();
        if (!Set.of("TREE_CARD", "LIST_CARD", "MICRO_LIST_CARD").contains(mode)) throw invalidManagementTree();
        if (!"LIST_CARD".equals(mode) && (root.has("querySummaries") || root.has("persistentQueries"))) {
            throw invalidManagementTree();
        }
        if (version >= MODE_AWARE_ACTION_VERSION) validateManagementActions(root.path("actions"), version == MANAGED_ACTION_VERSION);
        else if (root.has("actions")) throw invalidManagementTree();
        var normalized = ((com.fasterxml.jackson.databind.node.ObjectNode) root).deepCopy();
        normalized.remove("mode");
        normalized.remove("quickSearchFields");
        normalized.remove("actions");
        normalized.put("templateVersion", MANAGEMENT_VERSION);
        if (!"LIST_CARD".equals(mode)) {
            boolean found = false;
            for (JsonNode node : normalized.path("nodes")) {
                if ("list".equals(node.path("slot").asText())) throw invalidManagementTree();
                if (!"explorer".equals(node.path("slot").asText())) continue;
                if (found || !node.path("titleField").isTextual() || node.path("titleField").asText().isBlank()
                        || !node.path("fields").isArray() || !node.path("fields").isEmpty()) throw invalidManagementTree();
                if (node.has("secondaryField") && (!node.path("secondaryField").isTextual()
                        || node.path("secondaryField").asText().isBlank())) throw invalidManagementTree();
                var explorer = (com.fasterxml.jackson.databind.node.ObjectNode) node;
                explorer.remove("titleField");
                explorer.remove("secondaryField");
                explorer.put("slot", "list");
                found = true;
            }
            if (!found) throw invalidManagementTree();
        }
        validateManagementTree(normalized);
        return normalized;
    }

    private static void validateManagementActions(JsonNode actions, boolean managed) {
        if (!actions.isArray()) throw invalidManagementTree();
        Set<String> codes = new java.util.LinkedHashSet<>();
        for (JsonNode action : actions) {
            if (!action.isObject() || !validActionProperties(action, managed) || !action.path("actionCode").isTextual()
                    || action.path("actionCode").asText().isBlank() || !codes.add((managed ? action.path("anchor").asText() + ":" : "") + action.path("actionCode").asText())
                    || !action.path("anchor").isTextual()
                    || !Set.of("page", "detail", "form").contains(action.path("anchor").asText())) {
                throw invalidManagementTree();
            }
        }
    }

    private static boolean validActionProperties(JsonNode action, boolean managed) {
        var names = action.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (Set.of("actionCode", "anchor").contains(name)) continue;
            if (!managed) return false;
            if ("title".equals(name) && action.path(name).isTextual() && !action.path(name).asText().isBlank()) continue;
            if ("hidden".equals(name) && action.path(name).isBoolean()) continue;
            return false;
        }
        return action.has("actionCode") && action.has("anchor");
    }

    /**
     * Validates the first platform-owned node-property schema at the template boundary.
     * Field-control attributes (for example a text input's placeholder) remain metadata facts;
     * these properties describe a field component after it is placed in a page slot.
     */
    private static void validateManagementTree(JsonNode root) {
        validateManagementRootProperties(root);
        Set<String> slots = new java.util.LinkedHashSet<>();
        for (JsonNode node : root.path("nodes")) {
            String slot = node.path("slot").asText();
            if (!Set.of("list", "form").contains(slot) || !slots.add(slot)
                    || !node.path("title").isTextual() || node.path("title").asText().isBlank()
                    || !node.path("fields").isArray()) {
                throw invalidManagementTree();
            }
            java.util.Iterator<String> nodeNames = node.fieldNames();
            while (nodeNames.hasNext()) {
                String name = nodeNames.next();
                if (!Set.of("slot", "title", "fields", "relations", "groups", "order").contains(name)
                        || ("relations".equals(name) && !"form".equals(slot))) {
                    throw invalidManagementTree();
                }
            }
            Set<String> fields = new java.util.LinkedHashSet<>();
            for (JsonNode field : node.path("fields")) {
                String fieldName = field.isTextual() ? field.asText() : field.path("field").asText();
                if (fieldName.isBlank() || !fields.add(fieldName)) {
                    throw invalidManagementTree();
                }
                if (!field.isTextual()) {
                    validateManagementFieldProperties(slot, field);
                }
            }
            validateManagementRelations(node.path("relations"));
            validateManagementGroups(slot, node.path("groups"), fields);
            if ("form".equals(slot)) managementFormOrder(node);
            else if (node.has("order")) throw invalidManagementTree();
        }
        if (!slots.equals(Set.of("list", "form"))) {
            throw invalidManagementTree();
        }
    }

    /** One root placement; group members stay owned by the group. */
    public record ManagementFormEntry(String field, String group) {}

    /** Validates a complete permutation; older declarations retain fields-then-groups order. */
    public static List<ManagementFormEntry> managementFormOrder(JsonNode node) {
        List<ManagementFormEntry> declared = new java.util.ArrayList<>();
        node.path("fields").forEach(field -> declared.add(new ManagementFormEntry(
                field.isTextual() ? field.asText() : field.path("field").asText(), null)));
        node.path("groups").forEach(group -> declared.add(new ManagementFormEntry(null, group.path("group").asText())));
        if (!node.has("order")) return List.copyOf(declared);
        JsonNode order = node.path("order");
        if (!order.isArray() || order.size() != declared.size()) throw invalidManagementTree();
        Set<ManagementFormEntry> remaining = new java.util.HashSet<>(declared);
        List<ManagementFormEntry> result = new java.util.ArrayList<>();
        for (JsonNode entry : order) {
            if (!entry.isObject() || entry.size() != 1
                    || !(entry.path("field").isTextual() || entry.path("group").isTextual())) throw invalidManagementTree();
            ManagementFormEntry item = entry.has("field")
                    ? new ManagementFormEntry(entry.path("field").asText(), null)
                    : new ManagementFormEntry(null, entry.path("group").asText());
            if (!remaining.remove(item)) throw invalidManagementTree();
            result.add(item);
        }
        if (!remaining.isEmpty()) throw invalidManagementTree();
        return List.copyOf(result);
    }

    /** Form groups are a typed template node, never an arbitrary nested property bag. */
    private static void validateManagementGroups(String slot, JsonNode groups, Set<String> placedFields) {
        if (groups.isMissingNode()) return;
        if (!"form".equals(slot) || !groups.isArray()) throw invalidManagementTree();
        Set<String> codes = new java.util.LinkedHashSet<>();
        for (JsonNode group : groups) {
            if (!group.isObject() || !group.path("group").isTextual()
                    || !group.path("group").asText().matches("[a-z][a-z0-9_]{0,62}")
                    || !group.path("title").isTextual() || group.path("title").asText().isBlank()
                    || !group.path("fields").isArray() || !codes.add(group.path("group").asText())) {
                throw invalidManagementTree();
            }
            java.util.Iterator<String> names = group.fieldNames();
            while (names.hasNext()) {
                if (!Set.of("group", "title", "subtitle", "fields").contains(names.next())) throw invalidManagementTree();
            }
            if (group.has("subtitle") && (!group.path("subtitle").isTextual() || group.path("subtitle").asText().isBlank())) {
                throw invalidManagementTree();
            }
            for (JsonNode field : group.path("fields")) {
                String fieldName = field.isTextual() ? field.asText() : field.path("field").asText();
                if (fieldName.isBlank() || !placedFields.add(fieldName)) throw invalidManagementTree();
                if (!field.isTextual()) validateManagementFieldProperties("form", field);
            }
        }
    }

    /** Detail relations are template-owned components, not pseudo fields in the form field namespace. */
    private static void validateManagementRelations(JsonNode relations) {
        if (relations.isMissingNode()) return;
        if (!relations.isArray()) throw invalidManagementTree();
        Set<String> codes = new java.util.LinkedHashSet<>();
        for (JsonNode relation : relations) {
            if (!relation.isObject() || (relation.size() != 2 && relation.size() != 3)
                    || !relation.path("relation").isTextual() || relation.path("relation").asText().isBlank()
                    || !relation.path("title").isTextual() || relation.path("title").asText().isBlank()
                    || !codes.add(relation.path("relation").asText())) {
                throw invalidManagementTree();
            }
            java.util.Iterator<String> names = relation.fieldNames();
            while (names.hasNext()) {
                if (!Set.of("relation", "title", "fields").contains(names.next())) throw invalidManagementTree();
            }
            JsonNode fields = relation.path("fields");
            if (!fields.isMissingNode()) {
                if (!fields.isArray()) throw invalidManagementTree();
                Set<String> fieldNames = new java.util.LinkedHashSet<>();
                for (JsonNode field : fields) {
                    String name = field.isTextual() ? field.asText() : field.path("field").asText();
                    if (name.isBlank() || !fieldNames.add(name)) throw invalidManagementTree();
                    if (!field.isTextual()) {
                        validateManagementFieldProperties("list", field);
                        JsonNode width = field.path("props").path("width");
                        if (!width.isMissingNode()) {
                            if (!width.asText().matches("[1-9]\\d*px")) throw invalidManagementTree();
                            try {
                                Integer.parseInt(width.asText().replace("px", ""));
                            } catch (NumberFormatException ex) {
                                throw invalidManagementTree();
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * management v1 has one intentionally narrow root property surface.  Keeping it at the
     * template boundary prevents a revision from becoming an untyped JSON property bag while
     * still letting the list's already-supported query prompt be governed with the composition.
     */
    private static void validateManagementRootProperties(JsonNode root) {
        java.util.Iterator<String> rootNames = root.fieldNames();
        while (rootNames.hasNext()) {
            if (!Set.of("template", "templateVersion", "nodes", "props", "querySummaries", "persistentQueries")
                    .contains(rootNames.next())) {
                throw invalidManagementTree();
            }
        }
        validateQuerySummaries(root.path("querySummaries"));
        validatePersistentQueries(root.path("persistentQueries"));
        JsonNode properties = root.path("props");
        if (properties.isMissingNode()) {
            return;
        }
        if (!properties.isObject()) {
            throw invalidManagementTree();
        }
        java.util.Iterator<String> propertyNames = properties.fieldNames();
        while (propertyNames.hasNext()) {
            if (!"list".equals(propertyNames.next())) {
                throw invalidManagementTree();
            }
        }
        JsonNode list = properties.path("list");
        if (list.isMissingNode()) {
            return;
        }
        if (!list.isObject() || list.size() != 1 || !list.has("searchPlaceholder")
                || !list.path("searchPlaceholder").isTextual()
                || list.path("searchPlaceholder").asText().isBlank()) {
            throw invalidManagementTree();
        }
    }

    private static void validateQuerySummaries(JsonNode summaries) {
        if (summaries.isMissingNode()) return;
        if (!summaries.isArray()) throw invalidManagementTree();
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (JsonNode summary : summaries) {
            if (!summary.isObject() || !summary.path("key").isTextual() || summary.path("key").asText().isBlank()
                    || !keys.add(summary.path("key").asText()) || !summary.path("label").isTextual()
                    || summary.path("label").asText().isBlank() || !summary.path("source").isTextual()) {
                throw invalidManagementTree();
            }
            String source = summary.path("source").asText();
            if (!Set.of("MATCHED_COUNT", "SUM", "CONTRIBUTOR", "GROUPED").contains(source)) throw invalidManagementTree();
            java.util.Iterator<String> names = summary.fieldNames();
            while (names.hasNext()) {
                if (!Set.of("key", "label", "source", "fieldName", "contributorKey", "groupByField").contains(names.next())) {
                    throw invalidManagementTree();
                }
            }
            boolean hasField = summary.has("fieldName") && !summary.path("fieldName").isNull();
            boolean hasContributor = summary.has("contributorKey") && !summary.path("contributorKey").isNull();
            boolean hasGroup = summary.has("groupByField") && !summary.path("groupByField").isNull();
            if ((hasField && (!summary.path("fieldName").isTextual() || summary.path("fieldName").asText().isBlank()
                    || summary.path("fieldName").asText().contains(".")))
                    || (hasContributor && (!summary.path("contributorKey").isTextual() || summary.path("contributorKey").asText().isBlank()))
                    || (hasGroup && (!summary.path("groupByField").isTextual() || summary.path("groupByField").asText().isBlank()
                    || summary.path("groupByField").asText().contains(".")))
                    || ("SUM".equals(source) && !hasField)
                    || (!"SUM".equals(source) && !"GROUPED".equals(source) && hasField)
                    || ("CONTRIBUTOR".equals(source) != hasContributor)
                    || ("GROUPED".equals(source) != hasGroup)) {
                throw invalidManagementTree();
            }
        }
    }

    private static void validatePersistentQueries(JsonNode controls) {
        if (controls.isMissingNode()) return;
        if (!controls.isArray()) throw invalidManagementTree();
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (JsonNode control : controls) {
            if (!control.isObject() || !control.path("id").isTextual() || control.path("id").asText().isBlank()
                    || !ids.add(control.path("id").asText().trim()) || !control.path("label").isTextual()
                    || control.path("label").asText().isBlank() || !control.path("source").isTextual()
                    || !"FIELD".equals(control.path("source").asText()) || !control.path("fieldName").isTextual()
                    || control.path("fieldName").asText().isBlank() || !control.path("operator").isTextual()) {
                throw invalidManagementTree();
            }
            java.util.Iterator<String> names = control.fieldNames();
            while (names.hasNext()) {
                if (!Set.of("id", "label", "source", "fieldName", "operator", "defaultValues").contains(names.next())) {
                    throw invalidManagementTree();
                }
            }
            try {
                QueryOperator.from(control.path("operator").asText());
            } catch (IllegalArgumentException exception) {
                throw invalidManagementTree();
            }
            if (control.has("defaultValues") && !control.path("defaultValues").isArray()) {
                throw invalidManagementTree();
            }
        }
    }

    private static void validateManagementFieldProperties(String slot, JsonNode field) {
        if (!field.isObject() || !field.path("field").isTextual()) {
            throw invalidManagementTree();
        }
        java.util.Iterator<String> fieldNames = field.fieldNames();
        while (fieldNames.hasNext()) {
            if (!Set.of("field", "props").contains(fieldNames.next())) {
                throw invalidManagementTree();
            }
        }
        JsonNode properties = field.path("props");
        if (properties.isMissingNode()) {
            return;
        }
        if (!properties.isObject()) {
            throw invalidManagementTree();
        }
        Set<String> allowed = "list".equals(slot)
                ? Set.of("label", "width", "align")
                : Set.of("label", "columnSpan", "readOnly");
        java.util.Iterator<String> names = properties.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalidManagementTree();
            }
        }
        if (properties.has("label") && (!properties.path("label").isTextual()
                || properties.path("label").asText().isBlank())) {
            throw invalidManagementTree();
        }
        if (properties.has("width") && (!properties.path("width").isTextual()
                || !properties.path("width").asText().matches("\\d+(px|%)"))) {
            throw invalidManagementTree();
        }
        if (properties.has("align") && (!properties.path("align").isTextual()
                || !Set.of("left", "center", "right").contains(properties.path("align").asText()))) {
            throw invalidManagementTree();
        }
        if (properties.has("columnSpan") && (!properties.path("columnSpan").canConvertToInt()
                || properties.path("columnSpan").asInt() < 1 || properties.path("columnSpan").asInt() > 2)) {
            throw invalidManagementTree();
        }
        if (properties.has("readOnly") && !properties.path("readOnly").isBoolean()) {
            throw invalidManagementTree();
        }
    }

    private static RuntimeException invalidManagementTree() {
        return BusinessExceptions.warning("platform.presentation-revision.ui-tree-management-invalid",
                "Management v1 UI tree contains an unsupported slot, field node, or component property");
    }
}
