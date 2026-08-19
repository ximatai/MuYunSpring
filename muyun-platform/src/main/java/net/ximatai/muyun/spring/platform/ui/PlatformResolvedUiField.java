package net.ximatai.muyun.spring.platform.ui;

public record PlatformResolvedUiField(
        String uiConfigId,
        String moduleMetadataFieldId,
        String relationAlias,
        String metadataAlias,
        String fieldName,
        String columnName,
        String fieldTitle,
        String fieldSpecAlias,
        String fieldForm,
        String fieldUiControlAlias,
        Boolean visible,
        String visibleWhen,
        Boolean readOnly,
        String readOnlyWhen,
        Boolean requiredOverride,
        String placeholder,
        String defaultValue,
        Integer width,
        Integer columnSpan,
        String align,
        PlatformUiFixedPosition fixedPosition,
        Integer maxDisplayLines
) {
    /** Source-compatible constructor for projections created before conditional UI predicates were introduced. */
    public PlatformResolvedUiField(String uiConfigId,
                                   String moduleMetadataFieldId,
                                   String relationAlias,
                                   String metadataAlias,
                                   String fieldName,
                                   String columnName,
                                   String fieldTitle,
                                   String fieldSpecAlias,
                                   String fieldForm,
                                   String fieldUiControlAlias,
                                   Boolean visible,
                                   Boolean readOnly,
                                   Boolean requiredOverride,
                                   String placeholder,
                                   String defaultValue,
                                   Integer width,
                                   Integer columnSpan,
                                   String align,
                                   PlatformUiFixedPosition fixedPosition,
                                   Integer maxDisplayLines) {
        this(uiConfigId, moduleMetadataFieldId, relationAlias, metadataAlias, fieldName, columnName, fieldTitle,
                fieldSpecAlias, fieldForm, fieldUiControlAlias, visible, null, readOnly, null, requiredOverride,
                placeholder, defaultValue, width, columnSpan, align, fixedPosition, maxDisplayLines);
    }

    /** Source- and binary-compatible constructor for UI projections created before max display lines were introduced. */
    public PlatformResolvedUiField(String uiConfigId,
                                   String moduleMetadataFieldId,
                                   String relationAlias,
                                   String metadataAlias,
                                   String fieldName,
                                   String columnName,
                                   String fieldTitle,
                                   String fieldSpecAlias,
                                   String fieldForm,
                                   String fieldUiControlAlias,
                                   Boolean visible,
                                   Boolean readOnly,
                                   Boolean requiredOverride,
                                   String placeholder,
                                   String defaultValue,
                                   Integer width,
                                   Integer columnSpan,
                                   String align,
                                   PlatformUiFixedPosition fixedPosition) {
        this(uiConfigId, moduleMetadataFieldId, relationAlias, metadataAlias, fieldName, columnName, fieldTitle,
                fieldSpecAlias, fieldForm, fieldUiControlAlias, visible, null, readOnly, null, requiredOverride, placeholder,
                defaultValue, width, columnSpan, align, fixedPosition, null);
    }

    /** Source-compatible constructor for UI projections created before column spans were introduced. */
    public PlatformResolvedUiField(String uiConfigId,
                                   String moduleMetadataFieldId,
                                   String relationAlias,
                                   String metadataAlias,
                                   String fieldName,
                                   String columnName,
                                   String fieldTitle,
                                   String fieldSpecAlias,
                                   String fieldForm,
                                   String fieldUiControlAlias,
                                   Boolean visible,
                                   Boolean readOnly,
                                   Boolean requiredOverride,
                                   String placeholder,
                                   String defaultValue,
                                   Integer width,
                                   String align,
                                   PlatformUiFixedPosition fixedPosition) {
        this(uiConfigId, moduleMetadataFieldId, relationAlias, metadataAlias, fieldName, columnName, fieldTitle,
                fieldSpecAlias, fieldForm, fieldUiControlAlias, visible, null, readOnly, null, requiredOverride, placeholder,
                defaultValue, width, 1, align, fixedPosition, null);
    }
}
