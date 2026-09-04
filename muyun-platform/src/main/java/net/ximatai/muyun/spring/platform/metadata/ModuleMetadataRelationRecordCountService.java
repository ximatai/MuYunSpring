package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.stereotype.Service;

/** Resolves the business-record state that governs safe metadata structural edits. */
@Service
public class ModuleMetadataRelationRecordCountService {
    private final ModuleMetadataRelationService relationService;
    private final MetadataService metadataService;
    private final DynamicRecordService recordService;

    public ModuleMetadataRelationRecordCountService(ModuleMetadataRelationService relationService,
                                                    MetadataService metadataService,
                                                    DynamicRecordService recordService) {
        this.relationService = relationService;
        this.metadataService = metadataService;
        this.recordService = recordService;
    }

    public ModuleMetadataRelationRecordCount count(String moduleAlias, String relationId) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        ModuleMetadataRelation relation = relationService.select(relationId);
        if (relation == null || !validModuleAlias.equals(relation.getModuleAlias())) {
            throw new PlatformException("metadata relation does not belong to module: " + validModuleAlias + "." + relationId);
        }
        Metadata metadata = metadataService.select(relation.getMetadataId());
        if (metadata == null) throw new PlatformException("metadata relation requires existing metadata: " + relation.getMetadataId());
        return new ModuleMetadataRelationRecordCount(relationId,
                recordService.count(validModuleAlias, metadata.getAlias(), Criteria.of()));
    }
}
