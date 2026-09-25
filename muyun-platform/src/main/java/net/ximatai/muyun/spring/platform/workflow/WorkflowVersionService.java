package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
public class WorkflowVersionService extends AbstractAbilityService<WorkflowVersion> implements
        SoftDeleteAbility<WorkflowVersion>,
        QueryAbility<WorkflowVersion> {
    public static final String MODULE_ALIAS = "platform.workflow.version";

    public WorkflowVersionService(BaseDao<WorkflowVersion, String> workflowVersionDao,
                                  WorkflowDefinitionService definitionService) {
        super(MODULE_ALIAS, WorkflowVersion.class, workflowVersionDao);
        this.definitionService = definitionService;
    }

    private final WorkflowDefinitionService definitionService;

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, WorkflowVersion.class, java.util.List.of("id", "definitionId", "versionNo", "publishStatus", "publishedBy", "publishedAt", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("versionNo"));
    }

    @Override
    public void beforeInsert(WorkflowVersion version) {
        normalizeAndValidate(version);
    }

    @Override
    public void beforeUpdate(WorkflowVersion version) {
        WorkflowVersion existing = selectIncludingDeleted(version.getId());
        if (existing != null && existing.getPublishStatus() == WorkflowPublishStatus.PUBLISHED) {
            throw new PlatformException("published workflow version cannot be changed");
        }
        normalizeAndValidate(version);
    }

    private void normalizeAndValidate(WorkflowVersion version) {
        if (definitionService.select(version.getDefinitionId()) == null) {
            throw new PlatformException("workflow definition not found: " + version.getDefinitionId());
        }
        if (version.getVersionNo() == null || version.getVersionNo() <= 0) {
            throw new PlatformException("workflow version number must be positive");
        }
        if (version.getPublishStatus() == null) {
            version.setPublishStatus(WorkflowPublishStatus.DRAFT);
        }
        rejectDuplicate(version, Criteria.of()
                        .eq("definitionId", version.getDefinitionId())
                        .eq("versionNo", version.getVersionNo()),
                "workflow version number must be unique within definition: " + version.getVersionNo());
    }
}
