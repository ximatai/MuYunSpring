package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Explicitly backfills the persisted system-field catalogue for an existing metadata relation. */
@Service
public class MetadataSystemFieldReconciliationService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final ModuleMetadataRelationService relationService;
    private final MetadataService metadataService;
    private final MetadataFieldService fieldService;

    public MetadataSystemFieldReconciliationService(ModuleMetadataRelationService relationService,
                                                    MetadataService metadataService,
                                                    MetadataFieldService fieldService) {
        this.relationService = Objects.requireNonNull(relationService);
        this.metadataService = Objects.requireNonNull(metadataService);
        this.fieldService = Objects.requireNonNull(fieldService);
    }

    @Transactional
    public void reconcile(String moduleAlias, String relationId) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        ModuleMetadataRelation relation = relationService.select(relationId);
        if (relation == null || !validModuleAlias.equals(relation.getModuleAlias())) {
            throw new PlatformException("元数据节点不属于当前模块：" + relationId);
        }
        Metadata metadata = metadataService.select(relation.getMetadataId());
        if (metadata == null) throw new PlatformException("元数据节点已不存在：" + relation.getMetadataId());
        MetadataCapabilityResolution resolution = MetadataCapabilityCatalog.resolve(metadata, relation.getRelationRole(),
                fieldService.list(Criteria.of().eq("metadataId", metadata.getId()), ALL));
        MetadataCapabilityGovernanceMutationContext.run(() -> {
            MetadataCapabilityManagedFieldMaterializer.materialize(fieldService, metadata, resolution.capabilities());
            return null;
        });
    }
}
