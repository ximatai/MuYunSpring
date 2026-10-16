package net.ximatai.muyun.spring.platform.workflow;

import java.util.Map;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import org.springframework.stereotype.Service;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;

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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-version.published-workflow-versions-cannot-be-changed",
                    "published workflow version cannot be changed"));
        }
        normalizeAndValidate(version);
    }

    private void normalizeAndValidate(WorkflowVersion version) {
        if (definitionService.select(version.getDefinitionId()) == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-version.workflow-definition-not-found",
                    "workflow definition not found: {value1}"), Map.of("value1", String.valueOf(version.getDefinitionId())));
        }
        if (version.getVersionNo() == null || version.getVersionNo() <= 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-version.the-workflow-version-number-must-be-positive",
                    "workflow version number must be positive"));
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
