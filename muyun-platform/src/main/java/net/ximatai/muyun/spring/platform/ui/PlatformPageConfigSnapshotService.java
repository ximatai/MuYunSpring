package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

@Service
public class PlatformPageConfigSnapshotService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final PlatformUiSetService uiSetService;
    private final PlatformUiConfigService uiConfigService;
    private final PlatformUiConfigFieldService uiConfigFieldService;
    private final PlatformQueryTemplateService queryTemplateService;
    private final PlatformQueryItemService queryItemService;

    public PlatformPageConfigSnapshotService(PlatformUiSetService uiSetService,
                                             PlatformUiConfigService uiConfigService,
                                             PlatformUiConfigFieldService uiConfigFieldService,
                                             PlatformQueryTemplateService queryTemplateService,
                                             PlatformQueryItemService queryItemService) {
        this.uiSetService = uiSetService;
        this.uiConfigService = uiConfigService;
        this.uiConfigFieldService = uiConfigFieldService;
        this.queryTemplateService = queryTemplateService;
        this.queryItemService = queryItemService;
    }

    /** Published tenant presentation comes first; global presentation remains available by its stable IDs. */
    public PlatformPageConfigSnapshot snapshot(String moduleAlias) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        PlatformPageConfigSnapshot local = TenantContext.currentTenantId().isPresent()
                ? snapshotInScope(validAlias, TenantContext.currentTenantId().orElseThrow()) : null;
        PlatformPageConfigSnapshot global;
        try (TenantContext.Scope ignored = TenantContext.system("Read global published page configuration")) {
            global = snapshotInScope(validAlias, null);
        }
        if (local == null) return global;
        return new PlatformPageConfigSnapshot(validAlias,
                concat(local.uiSets(), global.uiSets()), concat(local.uiConfigs(), global.uiConfigs()),
                concat(local.uiFields(), global.uiFields()), concat(local.queryTemplates(), global.queryTemplates()),
                concat(local.queryItems(), global.queryItems()));
    }

    private PlatformPageConfigSnapshot snapshotInScope(String validAlias, String tenantId) {
        List<PlatformUiSet> uiSets = uiSetService.list(uiSetService.enabledCriteria(
                Criteria.of().eq("moduleAlias", validAlias).eqNullable("tenantId", tenantId)),
                ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
        List<PlatformUiConfig> uiConfigs = uiSets.isEmpty() ? List.of() : uiConfigService.list(
                uiConfigService.enabledCriteria(Criteria.of().in("uiSetId", uiSets.stream().map(PlatformUiSet::getId).toList())
                        .eq("published", Boolean.TRUE).eqNullable("tenantId", tenantId)),
                ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
        List<PlatformUiConfigField> uiFields = uiConfigs.isEmpty() ? List.of() : uiConfigFieldService.list(
                uiConfigFieldService.enabledCriteria(Criteria.of().in("uiConfigId", uiConfigs.stream().map(PlatformUiConfig::getId).toList())
                        .eqNullable("tenantId", tenantId)), ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
        List<PlatformQueryTemplate> queryTemplates = queryTemplateService.list(queryTemplateService.enabledCriteria(
                Criteria.of().eq("moduleAlias", validAlias).eq("published", Boolean.TRUE).eqNullable("tenantId", tenantId)),
                ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
        List<PlatformQueryItem> queryItems = queryTemplates.isEmpty() ? List.of() : queryItemService.list(
                queryItemService.enabledCriteria(Criteria.of().in("queryTemplateId", queryTemplates.stream().map(PlatformQueryTemplate::getId).toList())
                        .eqNullable("tenantId", tenantId)), ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD));
        return new PlatformPageConfigSnapshot(validAlias, uiSets, uiConfigs, uiFields, queryTemplates, queryItems);
    }

    private static <T> List<T> concat(List<T> local, List<T> global) {
        return Stream.concat(local.stream(), global.stream()).toList();
    }
}
