package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final PlatformPresentationTemplate MANAGEMENT = new PlatformPresentationTemplate(
            MANAGEMENT_ALIAS, MANAGEMENT_VERSION, PlatformPresentationClientType.WEB,
            java.util.Set.of(PlatformPageContractType.MANAGEMENT),
            "{\"template\":\"management\",\"templateVersion\":1,\"nodes\":[]}");
    private static final Map<String, List<PlatformPresentationTemplate>> TEMPLATES = Map.of(
            MANAGEMENT_ALIAS, List.of(MANAGEMENT));

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
        if (MANAGEMENT_ALIAS.equals(template.alias()) && template.version() == MANAGEMENT_VERSION) {
            validateManagementTree(root);
        }
    }

    /**
     * Validates the first platform-owned node-property schema at the template boundary.
     * Field-control attributes (for example a text input's placeholder) remain metadata facts;
     * these properties describe a field component after it is placed in a page slot.
     */
    private static void validateManagementTree(JsonNode root) {
        Set<String> slots = new java.util.LinkedHashSet<>();
        for (JsonNode node : root.path("nodes")) {
            String slot = node.path("slot").asText();
            if (!Set.of("list", "form").contains(slot) || !slots.add(slot)
                    || !node.path("title").isTextual() || node.path("title").asText().isBlank()
                    || !node.path("fields").isArray()) {
                throw invalidManagementTree();
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
        }
        if (!slots.equals(Set.of("list", "form"))) {
            throw invalidManagementTree();
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
