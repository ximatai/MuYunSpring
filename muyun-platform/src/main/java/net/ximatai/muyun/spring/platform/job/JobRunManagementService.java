package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.platform.localization.RecordTextSourceAbility;
import java.util.Map;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@DisablePlatformOperations({PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE})
public class JobRunManagementService extends AbstractAbilityService<JobRun> implements QueryAbility<JobRun>, RecordTextSourceAbility<JobRun> {
    public static final String MODULE_ALIAS = "platform.job_run";
    private final JobManagementAccess access;
    private final JobRegistry registry;

    public JobRunManagementService(JobRunDao dao, JobManagementAccess access, JobRegistry registry) {
        super(MODULE_ALIAS, JobRun.class, dao);
        this.access = Objects.requireNonNull(access);
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public Criteria activeCriteria(Criteria criteria) {
        return JobManagementQueryScope.apply(criteria, access);
    }

    @Override
    public Map<String, TextReference> recordTextSources(JobRun record) {
        if (!registry.contains(record.getJobType())) return Map.of();
        var type = registry.definition(record.getJobType());
        return Map.of("jobType", TextReference.code(type.nameKey(), type.code()));
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, JobRun.class,
                List.of("id", "jobType", "scope", "planId", "planRevision", "scheduledAt", "triggerType", "triggeredBy", "identityMode", "channel", "status", "attempts", "availableAt", "leaseUntil", "startedAt", "finishedAt", "cancelRequested", "progress", "resultCode", "traceId", "createdAt"),
                Sort.desc("createdAt"));
    }

    @Override
    public void beforeInsert(JobRun record) {
        throw new UnsupportedOperationException("job state is managed through JobService");
    }

    @Override
    public void beforeUpdate(JobRun record) {
        throw new UnsupportedOperationException("job state is managed through JobService");
    }

    @Override
    public void beforeDelete(String id) {
        throw new UnsupportedOperationException("job state is managed through JobService");
    }
}
