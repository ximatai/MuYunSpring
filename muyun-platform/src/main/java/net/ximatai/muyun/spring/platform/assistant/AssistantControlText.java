package net.ximatai.muyun.spring.platform.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.Set;

/** Bounded display provenance. Never a model-history fact, permission or executable proposal. */
final class AssistantControlText {
    private AssistantControlText() { }
    static void validate(JsonNode text) {
        if (text.toString().length() > 32000) throw invalid();
        int[] counts = {0, 0};
        read(text, 0, counts);
        if (counts[1] == 0) throw invalid();
    }
    private static void read(JsonNode text, int depth, int[] nodes) {
        if (++nodes[0] > 64 || depth > 4 || !text.isObject()) throw invalid();
        if (text.has("parts")) {
            properties(text, Set.of("parts"));
            JsonNode parts = text.get("parts");
            if (!parts.isArray() || parts.size() > 32) throw invalid();
            parts.forEach(part -> {
                if (part.isTextual()) string(part, 4000);
                else read(part, depth + 1, nodes);
            });
            return;
        }
        properties(text, Set.of("source", "args"));
        JsonNode source = text.path("source");
        if (!source.isObject()) throw invalid();
        properties(source, Set.of("sourceKind", "sourceId", "sourceVersion", "originalText"));
        if (!source.path("sourceKind").isTextual() || !"CODE".equals(source.path("sourceKind").asText())) throw invalid();
        string(source.path("sourceId"), 512);
        String id = source.path("sourceId").asText();
        if (!(id.matches("platform\\.screen\\.useAssistantConversation\\.[a-zA-Z0-9_.@-]+")
                || id.matches("platform\\.error\\.AI_[A-Z_]+"))) throw invalid();
        string(source.path("originalText"), 4000);
        String original = source.path("originalText").asText();
        if (original.isBlank() || !source.path("sourceVersion").isTextual()
                || !TextReference.versionOf(original).equals(source.path("sourceVersion").asText())) throw invalid();
        nodes[1]++;
        if (text.has("args")) {
            JsonNode args = text.get("args");
            if (!args.isObject() || args.size() > 16) throw invalid();
            args.fields().forEachRemaining(entry -> {
                if (!entry.getKey().matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) throw invalid();
                JsonNode value = entry.getValue();
                if (value.isTextual()) string(value, 4000);
                else if (value.isNumber()) { if (!Double.isFinite(value.asDouble())) throw invalid(); }
                else if (!value.isNull() && !value.isBoolean()) read(value, depth + 1, nodes);
            });
        }
    }
    private static void properties(JsonNode value, Set<String> allowed) {
        value.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid(); });
    }
    private static void string(JsonNode value, int max) {
        if (!value.isTextual() || value.asText().length() > max) throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid assistant control text presentation");
    }
}
