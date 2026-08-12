package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.id.Ids;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Maintains storage-neutral asset ownership facts independently from business table columns. */
@Service
public class ManagedFileAssetReferenceService extends AbstractAbilityService<ManagedFileAssetReference>
        implements SoftDeleteAbility<ManagedFileAssetReference> {
    public static final String MODULE_ALIAS = "platform.managed_file_asset_reference";
    private static final PageRequest PAGE = PageRequest.of(1, 500);

    public ManagedFileAssetReferenceService(ManagedFileAssetReferenceDao dao) {
        super(MODULE_ALIAS, ManagedFileAssetReference.class, dao);
    }

    public void replaceFieldReferences(String tenantId, String moduleAlias, String recordId, String fieldName,
                                       Collection<String> assetIds) {
        Set<String> expected = normalizedIds(assetIds);
        List<ManagedFileAssetReference> existing = list(Criteria.of()
                        .eq("tenantId", required(tenantId, "tenantId"))
                        .eq("moduleAlias", required(moduleAlias, "moduleAlias"))
                        .eq("recordId", required(recordId, "recordId"))
                        .eq("fieldName", required(fieldName, "fieldName")),
                PAGE, Sort.asc("createdAt"));
        Set<String> retained = new LinkedHashSet<>();
        for (ManagedFileAssetReference reference : existing) {
            if (expected.contains(reference.getAssetId())) retained.add(reference.getAssetId());
            else delete(reference);
        }
        for (String assetId : expected) {
            if (retained.contains(assetId)) continue;
            ManagedFileAssetReference reference = new ManagedFileAssetReference();
            reference.setId(Ids.newId());
            reference.setTenantId(tenantId.trim());
            reference.setModuleAlias(moduleAlias.trim());
            reference.setRecordId(recordId.trim());
            reference.setFieldName(fieldName.trim());
            reference.setAssetId(assetId);
            insert(reference);
        }
    }

    public boolean isReferenced(String tenantId, String assetId) {
        return !list(Criteria.of().eq("tenantId", required(tenantId, "tenantId"))
                        .eq("assetId", required(assetId, "assetId")), PAGE, Sort.asc("createdAt"))
                .isEmpty();
    }

    private Set<String> normalizedIds(Collection<String> assetIds) {
        Set<String> result = new LinkedHashSet<>();
        if (assetIds == null) return result;
        for (String assetId : assetIds) result.add(required(assetId, "assetId"));
        return result;
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
