package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.id.Ids;
import org.jdbi.v3.core.Jdbi;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * PostgreSQL-only sequence allocator. Register this implementation only for PostgreSQL deployments.
 */
public class PostgresCodeSequenceAllocator implements CodeSequenceAllocator {
    static final String SYSTEM_TENANT_ID = "__SYSTEM__";

    private static final String SQL = """
            insert into platform_code_sequence_state (
                id,
                tenant_id,
                version,
                deleted,
                created_at,
                updated_at,
                rule_id,
                basis_key,
                period_key,
                current_value
            ) values (
                :id,
                :tenantId,
                0,
                false,
                now(),
                now(),
                :ruleId,
                :basisKey,
                :periodKey,
                :startValue
            )
            on conflict (tenant_id, rule_id, basis_key, period_key)
            do update set
                current_value = case
                    when :resetOnOverflow and (
                        (cast(:maxValue as bigint) is not null
                            and platform_code_sequence_state.current_value + :stepValue > cast(:maxValue as bigint))
                        or (cast(:maxByLength as bigint) is not null
                            and platform_code_sequence_state.current_value + :stepValue > cast(:maxByLength as bigint))
                    ) then :startValue
                    else platform_code_sequence_state.current_value + :stepValue
                end,
                version = platform_code_sequence_state.version + 1,
                updated_at = now()
            where :resetOnOverflow
                or (
                    (cast(:maxValue as bigint) is null
                        or platform_code_sequence_state.current_value + :stepValue <= cast(:maxValue as bigint))
                    and (cast(:maxByLength as bigint) is null
                        or platform_code_sequence_state.current_value + :stepValue <= cast(:maxByLength as bigint))
                )
            returning current_value
            """;

    private final Jdbi jdbi;
    private volatile boolean databaseVerified;

    public PostgresCodeSequenceAllocator(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
    }

    @Override
    public long allocateNextValue(CodeSequenceAllocation allocation) {
        Objects.requireNonNull(allocation, "allocation must not be null");
        verifyPostgres();
        Long maxByLength = maxByLength(allocation.sequenceLength());
        if (!resetOnOverflow(allocation) && isOverflow(allocation.startValue(), allocation.maxValue(), maxByLength)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.postgres-code-sequence-allocator.code-sequence-overflow-for-rule",
                    "Code sequence overflow for rule: {value1}"), Map.of("value1", String.valueOf(allocation.ruleId())));
        }
        return jdbi.withHandle(handle -> handle.createQuery(SQL)
                .bindMap(params(allocation, maxByLength))
                .mapTo(Long.class)
                .findOne()
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.postgres-code-sequence-allocator.code-sequence-overflow-for-rule",
                        "Code sequence overflow for rule: {value1}"), Map.of("value1", String.valueOf(allocation.ruleId())))));
    }

    private void verifyPostgres() {
        if (databaseVerified) {
            return;
        }
        synchronized (this) {
            if (databaseVerified) {
                return;
            }
            String productName = jdbi.withHandle(handle -> {
                try {
                    return handle.getConnection().getMetaData().getDatabaseProductName();
                } catch (Exception e) {
                    throw new PlatformException("Unable to detect database product for code sequence allocation", e);
                }
            });
            if (productName == null || !productName.toLowerCase().contains("postgresql")) {
                throw new PlatformException("PostgresCodeSequenceAllocator requires PostgreSQL, current database: " + productName);
            }
            databaseVerified = true;
        }
    }

    private Map<String, Object> params(CodeSequenceAllocation allocation, Long maxByLength) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", Ids.newId());
        params.put("tenantId", effectiveTenantId(allocation.tenantId()));
        params.put("ruleId", allocation.ruleId());
        params.put("basisKey", allocation.basisKey());
        params.put("periodKey", allocation.periodKey());
        params.put("startValue", allocation.startValue());
        params.put("stepValue", allocation.stepValue());
        params.put("maxValue", allocation.maxValue());
        params.put("maxByLength", maxByLength);
        params.put("resetOnOverflow", resetOnOverflow(allocation));
        return params;
    }

    private String effectiveTenantId(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? SYSTEM_TENANT_ID : tenantId;
    }

    private boolean resetOnOverflow(CodeSequenceAllocation allocation) {
        return allocation.overflowPolicy() == CodeSequenceOverflowPolicy.RESET;
    }

    private boolean isOverflow(long next, Long maxValue, Long maxByLength) {
        return maxValue != null && next > maxValue
                || maxByLength != null && next > maxByLength;
    }

    private Long maxByLength(Integer sequenceLength) {
        if (sequenceLength == null || sequenceLength <= 0) {
            return null;
        }
        long max = 1L;
        for (int i = 0; i < sequenceLength; i++) {
            max *= 10L;
        }
        return max - 1L;
    }
}
