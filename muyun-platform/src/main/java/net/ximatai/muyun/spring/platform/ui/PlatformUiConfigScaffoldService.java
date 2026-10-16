package net.ximatai.muyun.spring.platform.ui;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class PlatformUiConfigScaffoldService {
    private final PlatformUiSetService uiSetService;
    private final PlatformUiConfigService uiConfigService;
    private final PlatformUiConfigFieldService uiConfigFieldService;
    private final ModuleMetadataFieldService moduleFieldService;
    private final FieldSpecService fieldTypeService;
    private final FieldUiControlService fieldUiTypeService;

    public PlatformUiConfigScaffoldService(PlatformUiSetService uiSetService,
                                           PlatformUiConfigService uiConfigService,
                                           PlatformUiConfigFieldService uiConfigFieldService,
                                           ModuleMetadataFieldService moduleFieldService,
                                           FieldSpecService fieldTypeService,
                                           FieldUiControlService fieldUiTypeService) {
        this.uiSetService = uiSetService;
        this.uiConfigService = uiConfigService;
        this.uiConfigFieldService = uiConfigFieldService;
        this.moduleFieldService = moduleFieldService;
        this.fieldTypeService = fieldTypeService;
        this.fieldUiTypeService = fieldUiTypeService;
    }

    public List<String> scaffoldDefaultClientConfigs(String uiSetId) {
        List<String> ids = new ArrayList<>();
        ids.add(scaffoldClientConfig(uiSetId, PlatformUiClientType.WEB));
        ids.add(scaffoldClientConfig(uiSetId, PlatformUiClientType.APP));
        return List.copyOf(ids);
    }

    public String scaffoldClientConfig(String uiSetId, PlatformUiClientType clientType) {
        PlatformUiSet uiSet = uiSetService.requireUiSet(uiSetId);
        PlatformUiClientType requestedClientType = clientType == null ? PlatformUiClientType.WEB : clientType;
        PlatformUiConfig existing = existingConfig(uiSet.getId(), requestedClientType);
        if (existing != null) {
            return existing.getId();
        }
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setUiSetId(uiSet.getId());
        uiConfig.setClientType(requestedClientType);
        String uiConfigId = uiConfigService.insert(uiConfig);
        for (ModuleMetadataField moduleField : moduleFieldService.listMainByModuleAlias(uiSet.getModuleAlias())) {
            ResolvedModuleMetadataField resolved = moduleFieldService.resolve(moduleField.getId());
            PlatformUiConfigField field = new PlatformUiConfigField();
            field.setUiConfigId(uiConfigId);
            field.setModuleMetadataFieldId(resolved.moduleMetadataFieldId());
            field.setFieldUiControlAlias(defaultUiControlAlias(resolved));
            field.setVisible(Boolean.TRUE);
            field.setReadOnly(uiSet.getSetType() == PlatformUiSetType.DETAIL);
            field.setSortOrder(moduleField.getSortOrder());
            uiConfigFieldService.insert(field);
        }
        return uiConfigId;
    }

    private PlatformUiConfig existingConfig(String uiSetId, PlatformUiClientType clientType) {
        return uiConfigService.findByUiSetAndClient(uiSetId, clientType);
    }

    private String defaultUiControlAlias(ResolvedModuleMetadataField resolved) {
        FieldSpec fieldType = fieldTypeService.requireFieldType(resolved.fieldSpecAlias());
        if (fieldType.getDefaultUiControlAlias() != null && !fieldType.getDefaultUiControlAlias().isBlank()) {
            return fieldType.getDefaultUiControlAlias();
        }
        List<String> allowedAliases = fieldType.getUiControlAliases() == null
                ? List.of()
                : fieldType.getUiControlAliases().stream().filter(Objects::nonNull).toList();
        List<FieldUiControl> candidates = allowedAliases.isEmpty()
                ? fieldUiTypeService.listEnabledForDefaultFieldType(fieldType.getAlias())
                : fieldUiTypeService.listEnabledByAliases(allowedAliases).stream()
                .filter(type -> type.getDefaultFieldSpecAlias() == null
                        || type.getDefaultFieldSpecAlias().isBlank()
                        || Objects.equals(type.getDefaultFieldSpecAlias(), fieldType.getAlias()))
                .sorted(Comparator.comparing(FieldUiControl::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (candidates.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.platform-ui-config-scaffold.cannot-scaffold-ui-config-field-without-field-ui-control",
                    "Cannot scaffold UI config field without field UI control: {value1}.{value2}"), Map.of("value1", String.valueOf(resolved.fieldSpecAlias()), "value2", String.valueOf(resolved.fieldName())));
        }
        return candidates.getFirst().getAlias();
    }
}
