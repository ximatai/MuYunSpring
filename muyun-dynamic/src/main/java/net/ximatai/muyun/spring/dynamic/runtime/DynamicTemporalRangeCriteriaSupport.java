package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.time.BusinessTimeContext;
import net.ximatai.muyun.spring.common.time.BusinessTimeRange;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;

public final class DynamicTemporalRangeCriteriaSupport {
    private DynamicTemporalRangeCriteriaSupport() {
    }

    public static boolean appendInstantLocalDateRange(Criteria criteria,
                                                       String fieldName,
                                                       FieldType fieldType,
                                                       Object startInclusive,
                                                       Object endInclusive,
                                                       String timeZone,
                                                       PlatformTimeService timeService,
                                                       BusinessTimeContext defaultContext) {
        if (criteria == null || fieldType == null || !fieldType.isTemporal() || fieldType.isBusinessDate()
                || !PlatformTimeService.isLocalDateValue(startInclusive)
                || !PlatformTimeService.isLocalDateValue(endInclusive)) {
            return false;
        }
        PlatformTimeService effectiveTimeService = java.util.Objects.requireNonNull(timeService, "timeService");
        BusinessTimeRange range = effectiveTimeService.localDateClosedRangeToInstantRange(
                startInclusive,
                endInclusive,
                timeContext(timeZone, defaultContext)
        );
        criteria.gte(fieldName, range.startInclusive());
        criteria.lt(fieldName, range.endExclusive());
        return true;
    }

    private static BusinessTimeContext timeContext(String timeZone, BusinessTimeContext defaultContext) {
        BusinessTimeContext context = defaultContext == null ? BusinessTimeContext.empty() : defaultContext;
        if (timeZone == null || timeZone.isBlank()) {
            return context;
        }
        return context.withZone(PlatformTimeService.requireIanaZoneId(timeZone));
    }
}
