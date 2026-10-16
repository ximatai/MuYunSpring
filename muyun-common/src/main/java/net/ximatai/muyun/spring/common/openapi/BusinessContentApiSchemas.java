package net.ximatai.muyun.spring.common.openapi;

import java.util.*;
import static net.ximatai.muyun.spring.common.openapi.PlatformApiDocument.*;

/** One source/history transport contract for static and dynamic business records. */
public final class BusinessContentApiSchemas {
    private BusinessContentApiSchemas() { }
    public static Map<String, Schema> schemas() {
        Map<String, Property> revision = new LinkedHashMap<>();
        revision.put("sourceKey", property("string", false));
        revision.put("revision", property("integer", false));
        revision.put("text", property("string", true));
        revision.put("sourceLanguage", property("string", true));
        revision.put("authorId", property("string", true));
        revision.put("createdAt", instant(false));
        revision.put("authoredAt", instant(true));
        revision.put("revisionAuthorId", property("string", true));
        revision.put("revisionKind", property("string", false));
        return Map.of(
                "BusinessContentRevision", object("BusinessContentRevision", revision),
                "BusinessContentRevisionList", new Schema("BusinessContentRevisionList", "array", null, List.of(), Map.of(),
                        property("BusinessContentRevision", false)),
                "BusinessContentField", object("BusinessContentField", Map.of(
                        "key", property("string", false), "policy", property("string", false))),
                "BusinessContentCurrentSource", object("BusinessContentCurrentSource", Map.of(
                        "content", property("BusinessContentField", false), "text", property("string", true),
                        "expectedRevision", property("integer", true), "source", property("BusinessContentRevision", true))),
                "BusinessContentSources", object("BusinessContentSources", Map.of(
                        "recordVersion", property("integer", true), "fields", property("object", false))));
    }
    private static Schema object(String name, Map<String, Property> properties) {
        return new Schema(name, "object", null, List.copyOf(properties.keySet()), properties, null);
    }
    private static Property property(String type, boolean nullable) {
        return new Property(type, null, true, nullable, false, null, null, null, null, null, List.of());
    }
    private static Property instant(boolean nullable) {
        return new Property("string", "date-time", true, nullable, false, null, null, null, null, null, List.of());
    }
}
