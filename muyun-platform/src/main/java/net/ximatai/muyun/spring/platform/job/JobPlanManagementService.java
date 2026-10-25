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
@DisablePlatformOperations({PlatformAction.CREATE, PlatformAction.DELETE})
public class JobPlanManagementService extends AbstractAbilityService<JobPlan> implements QueryAbility<JobPlan>, RecordTextSourceAbility<JobPlan> {
    public static final String MODULE_ALIAS = "platform.job_plan";
    private final JobManagementAccess access;
    private final JobRegistry registry;
    private final JobService jobs;

    public JobPlanManagementService(JobPlanDao dao, JobManagementAccess access, JobService jobs, JobRegistry registry) {
        super(MODULE_ALIAS, JobPlan.class, dao);
        this.access = Objects.requireNonNull(access);
        this.registry = Objects.requireNonNull(registry);
        this.jobs = Objects.requireNonNull(jobs);
    }

    @Override
    public Criteria activeCriteria(Criteria criteria) {
        return JobManagementQueryScope.apply(criteria, access);
    }

    @Override
    public Map<String, TextReference> recordTextSources(JobPlan record) {
        if (!registry.contains(record.getJobType())) return Map.of();
        var type = registry.definition(record.getJobType());
        return Map.of("jobType", TextReference.code(type.nameKey(), type.code()));
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, JobPlan.class,
                List.of("id", "jobType", "scope", "objectKey", "scheduleKind", "expression", "zoneId", "intervalMillis", "initialDelayMillis", "enabled", "available", "managedBy", "identityMode", "nextFireAt", "consecutiveFailures", "lastStatus", "skippedCount", "configRevision", "createdAt"),
                Sort.desc("createdAt"));
    }

    @Override
    public void beforeInsert(JobPlan record) {
        throw new UnsupportedOperationException("job state is managed through JobService");
    }

    @Override
    public int update(JobPlan record) {
        jobs.configure(record);
        return 1;
    }

    @Override
    public void beforeUpdate(JobPlan record) {
        throw new UnsupportedOperationException("job state is managed through JobService");
    }

    @Override
    public void beforeDelete(String id) {
        throw new UnsupportedOperationException("job state is managed through JobService");
    }
}
