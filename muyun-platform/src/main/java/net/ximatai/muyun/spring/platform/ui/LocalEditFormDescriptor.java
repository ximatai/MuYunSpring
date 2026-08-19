package net.ximatai.muyun.spring.platform.ui;

import java.util.List;

/**
 * A published, source-neutral local-edit form contract.
 *
 * <p>The descriptor is signed into a {@link PlatformActionBlock}; clients use
 * it as the sole source of editable fields and submit shape.  It intentionally
 * contains resolved fields instead of a raw layout JSON or a client handler.</p>
 */
public record LocalEditFormDescriptor(
        String uiConfigId,
        List<PlatformResolvedUiField> fields,
        List<PlatformResolvedFieldUiControl> fieldUiControls,
        LocalEditSubmitContract submitContract
) {
    public LocalEditFormDescriptor {
        if (uiConfigId == null || uiConfigId.isBlank()) {
            throw new IllegalArgumentException("local edit form ui config id must not be blank");
        }
        uiConfigId = uiConfigId.trim();
        fields = fields == null ? List.of() : List.copyOf(fields);
        fieldUiControls = fieldUiControls == null ? List.of() : List.copyOf(fieldUiControls);
        submitContract = submitContract == null ? LocalEditSubmitContract.standard() : submitContract;
    }
}
