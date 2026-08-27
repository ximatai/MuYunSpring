package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.databind.JsonNode;
import net.ximatai.muyun.spring.common.exception.PlatformException;

import java.util.ArrayList;
import java.util.List;

final class PlatformTaskBlockLayoutResolver {
    private PlatformTaskBlockLayoutResolver() {
    }

    static List<PlatformTaskBlock> resolve(PlatformUiConfig config) {
        String layoutJson = config.getLayoutJson();
        if (layoutJson == null || layoutJson.isBlank()) {
            return List.of();
        }
        JsonNode root = PlatformPageLayout.root(config);
        JsonNode blocks = root.get("blocks");
        if (blocks == null || !blocks.isArray()) {
            return List.of();
        }
        ArrayList<PlatformTaskBlock> resolved = new ArrayList<>();
        for (JsonNode block : blocks) {
            if (block == null || !block.isObject() || !"taskPanel".equals(text(block, "type"))) {
                continue;
            }
            String key = text(block, "key");
            if (key == null) {
                continue;
            }
            resolved.add(new PlatformTaskBlock(
                    config.getId(),
                    key,
                    text(block, "title"),
                    taskCheckType(text(block, "checkType")),
                    text(block, "associationViewCode"),
                    text(block, "queryTemplateId"),
                    text(block, "externalRecordIdKey"),
                    text(block, "diagnosticPath"),
                    text(block, "targetModuleAlias"),
                    text(block, "generationRuleId"),
                    positiveInt(block, "expectedCount"),
                    checks(block)
            ));
        }
        return resolved;
    }

    private static List<PlatformTaskCheckBlock> checks(JsonNode block) {
        JsonNode checks = block.get("checks");
        if (checks == null || checks.isNull()) {
            return List.of();
        }
        if (!checks.isArray()) {
            throw new PlatformException("taskPanel.checks must be array");
        }
        if (checks.isEmpty()) {
            throw new PlatformException("taskPanel.checks must not be empty");
        }
        ArrayList<PlatformTaskCheckBlock> resolved = new ArrayList<>();
        for (JsonNode check : checks) {
            if (check == null || !check.isObject()) {
                continue;
            }
            resolved.add(new PlatformTaskCheckBlock(
                    taskCheckType(text(check, "checkType")),
                    text(check, "associationViewCode"),
                    text(check, "queryTemplateId"),
                    text(check, "externalRecordIdKey"),
                    text(check, "targetModuleAlias"),
                    text(check, "generationRuleId"),
                    positiveInt(check, "expectedCount"),
                    firstText(text(check, "diagnosticPath"), text(block, "diagnosticPath"))
            ));
        }
        return resolved;
    }

    private static PlatformTaskCheckType taskCheckType(String value) {
        if (value == null) {
            return PlatformTaskCheckType.MANUAL;
        }
        try {
            return PlatformTaskCheckType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformException("Unsupported task check type: " + value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private static Integer positiveInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isInt() || value.asInt() <= 0) {
            throw new PlatformException("taskPanel." + field + " must be positive integer");
        }
        return value.asInt();
    }

    private static String firstText(String first, String second) {
        return first != null ? first : second;
    }
}
