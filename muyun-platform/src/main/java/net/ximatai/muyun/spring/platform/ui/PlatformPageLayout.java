package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.Set;

/**
 * Source-layer decoder for a dynamic page layout.
 *
 * <p>The stored JSON remains the configuration wire format. Consumers must obtain its page root
 * here so publication, navigator extraction and Web descriptor adaptation do not each create a
 * separate parsing boundary.</p>
 */
public record PlatformPageLayout(int schemaVersion, JsonNode root) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Set<String> PAGE_ROOT_MEMBERS = Set.of(
            "schemaVersion", "template", "traits", "list", "detail", "explorer", "navigator", "treeResource",
            "querySummaries", "persistentQueries", "referenceCandidate", "referenceCandidates", "children",
            "childSections", "blocks");

    public static PlatformPageLayout decode(PlatformUiConfig config) {
        if (config == null || config.getLayoutJson() == null || config.getLayoutJson().isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(config.getLayoutJson());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("page layout JSON root must be an object: " + config.getId());
            }
            JsonNode version = root.get("schemaVersion");
            int schemaVersion = version == null || version.isNull() ? 1 : version.isInt() ? version.intValue() : -1;
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported page layout schemaVersion '" + schemaVersion
                        + "': " + config.getId());
            }
            return new PlatformPageLayout(schemaVersion, root);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("page layout JSON cannot be decoded: " + config.getId(), exception);
        }
    }

    public static JsonNode root(PlatformUiConfig config) {
        PlatformPageLayout layout = decode(config);
        return layout == null ? null : layout.root();
    }

    /** Rejects unowned page-root members before a published configuration can silently ignore them. */
    public void requireKnownPageRootMembers(PlatformUiConfig config) {
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!PAGE_ROOT_MEMBERS.contains(name)) {
                throw new IllegalArgumentException("unsupported page layout member '" + name + "': " + config.getId());
            }
        }
    }
}
