package net.ximatai.muyun.spring.platform.assistant;

/** Browser answer to a selection shown in the current local assistant conversation. */
public record AssistantSelectionResponse(String interactionId, String optionId, String label) {
    public AssistantSelectionResponse {
        if (interactionId == null || interactionId.isBlank() || interactionId.length() > 256) {
            throw new IllegalArgumentException("assistant selection response interaction id is invalid");
        }
        if (optionId == null || !optionId.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException("assistant selection response option id is invalid");
        }
        if (label == null || label.isBlank() || label.length() > 80) {
            throw new IllegalArgumentException("assistant selection response label is invalid");
        }
    }
}
