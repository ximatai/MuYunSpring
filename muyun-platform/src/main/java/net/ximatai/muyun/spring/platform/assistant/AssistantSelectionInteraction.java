package net.ximatai.muyun.spring.platform.assistant;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A bounded single-choice interaction presented with an assistant message. */
public record AssistantSelectionInteraction(
        String interactionId,
        String prompt,
        InputPolicy inputPolicy,
        Presentation presentation,
        List<Option> options
) {
    public enum InputPolicy { FREE_TEXT_ALLOWED, SELECTION_REQUIRED }
    public enum Presentation { OPTIONS, CONFIRMATION }

    public record Option(String id, String label) {
        public Option {
            if (id == null || !id.matches("[A-Za-z0-9._:-]{1,64}")) {
                throw new IllegalArgumentException("assistant selection option id is invalid");
            }
            if (label == null || label.isBlank() || label.length() > 80) {
                throw new IllegalArgumentException("assistant selection option label is invalid");
            }
        }
    }

    public AssistantSelectionInteraction {
        if (interactionId == null || interactionId.isBlank() || interactionId.length() > 256) {
            throw new IllegalArgumentException("assistant selection interaction id is invalid");
        }
        if (prompt == null || prompt.isBlank() || prompt.length() > 500) {
            throw new IllegalArgumentException("assistant selection prompt is invalid");
        }
        if (inputPolicy == null || presentation == null) {
            throw new IllegalArgumentException("assistant selection policy and presentation are required");
        }
        options = options == null ? List.of() : List.copyOf(options);
        if (options.size() < 2 || options.size() > 8) {
            throw new IllegalArgumentException("assistant selection requires between 2 and 8 options");
        }
        Set<String> optionIds = new HashSet<>();
        for (Option option : options) {
            if (!optionIds.add(option.id())) {
                throw new IllegalArgumentException("assistant selection option ids must be unique");
            }
        }
        if (presentation == Presentation.CONFIRMATION
                && (inputPolicy != InputPolicy.SELECTION_REQUIRED
                || !options.stream().map(Option::id).toList().equals(List.of("confirm", "cancel")))) {
            throw new IllegalArgumentException("assistant confirmation requires ordered confirm and cancel options");
        }
    }
}
