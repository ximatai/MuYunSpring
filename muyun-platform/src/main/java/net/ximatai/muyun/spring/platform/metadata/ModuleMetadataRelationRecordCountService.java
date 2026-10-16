package net.ximatai.muyun.spring.platform.metadata;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-relation-record-count.metadata-relation-does-not-belong-to-module",
                    "metadata relation does not belong to module: {value1}.{value2}"), Map.of("value1", String.valueOf(validModuleAlias), "value2", String.valueOf(relationId)));
        }
        Metadata metadata = metadataService.select(relation.getMetadataId());
        if (metadata == null) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.module-metadata-relation-record-count.metadata-relation-requires-existing-metadata",
                "metadata relation requires existing metadata: {value1}"), Map.of("value1", String.valueOf(relation.getMetadataId())));
        return new ModuleMetadataRelationRecordCount(relationId,
                recordService.count(validModuleAlias, metadata.getAlias(), Criteria.of()));
    }
}
