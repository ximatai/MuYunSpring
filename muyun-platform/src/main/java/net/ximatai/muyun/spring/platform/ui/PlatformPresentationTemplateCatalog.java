package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        if (revision.getUiTreeJson() == null || revision.getUiTreeJson().isBlank()) {
            throw BusinessExceptions.warning("platform.presentation-revision.ui-tree-required",
                    "Presentation revision UI tree is required");
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(revision.getUiTreeJson());
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
    }
}
