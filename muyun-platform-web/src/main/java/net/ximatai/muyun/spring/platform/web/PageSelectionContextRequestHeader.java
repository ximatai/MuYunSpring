package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;

/** Parses the opaque selection reference sent by a page navigator extension. */
final class PageSelectionContextRequestHeader {
    static final String HEADER_NAME = "X-MuYun-Page-Selection";
    private static final ObjectMapper JSON = new ObjectMapper();

    private PageSelectionContextRequestHeader() {
    }

    static SelectionReference parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            SelectionReference reference = JSON.readValue(value, SelectionReference.class);
            if (reference == null || reference.kind() == null || reference.kind().isBlank()
                    || reference.key() == null || reference.key().isBlank()) throw invalid();
            return reference;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    record SelectionReference(String kind, String key) {
    }

    private static RuntimeException invalid() {
        return PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                "Page selection header must contain a kind and key");
    }
}
