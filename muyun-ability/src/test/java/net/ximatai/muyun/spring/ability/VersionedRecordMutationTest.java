package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionedRecordMutationTest {
    @Test
    void retriesWithLatestSnapshotAndDoesNotMutateReadObjects() {
        WritingDao dao = new WritingDao();
        Counter first = record(1, 2);
        Counter latest = record(2, 3);
        latest.password = "concurrent-password";
        dao.write = (row, version) -> version == 2 ? 1 : 0;
        AtomicInteger reads = new AtomicInteger();

        Counter result = VersionedRecordMutation.update(dao,
                () -> reads.getAndIncrement() == 0 ? first : latest, row -> row.count++);

        assertThat(result.count).isEqualTo(4);
        assertThat(result.password).isEqualTo("concurrent-password");
        assertThat(result.getVersion()).isEqualTo(3);
        assertThat(result.getUpdatedAt()).isNotNull();
        assertThat(first.count).isEqualTo(2);
        assertThat(latest.count).isEqualTo(3);
    }

    @Test
    void missingRecordDoesNotWriteAndDeletionDuringRetryDoesNotResurrectIt() {
        WritingDao dao = new WritingDao();
        assertThat(VersionedRecordMutation.update(dao, () -> null, row -> row.count++)).isNull();
        assertThat(dao.writes).isZero();
        AtomicInteger reads = new AtomicInteger();
        assertThat(VersionedRecordMutation.update(dao,
                () -> reads.getAndIncrement() == 0 ? record(1, 0) : null, row -> row.count++)).isNull();
        assertThat(dao.writes).isEqualTo(1);
    }

    @Test
    void refusesMissingVersionAndIdentityChanges() {
        WritingDao dao = new WritingDao();
        Counter missingVersion = record(1, 0);
        missingVersion.setVersion(null);
        assertThatThrownBy(() -> VersionedRecordMutation.update(dao, () -> missingVersion, row -> row.count++))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> VersionedRecordMutation.update(dao, () -> record(1, 0), row -> row.setId("other")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionedRecordMutation.update(dao, () -> record(1, 0), row -> row.setTenantId("other")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dao.writes).isZero();
    }

    @Test
    void persistentContentionFailsExplicitlyAndStorageErrorsAreNotRetried() {
        WritingDao dao = new WritingDao();
        assertThatThrownBy(() -> VersionedRecordMutation.update(dao, () -> record(1, 0), row -> row.count++))
                .isInstanceOf(OptimisticLockException.class);
        dao.writes = 0;
        dao.write = (row, version) -> { throw new IllegalStateException("database offline"); };
        assertThatThrownBy(() -> VersionedRecordMutation.update(dao, () -> record(1, 0), row -> row.count++))
                .isInstanceOf(IllegalStateException.class).hasMessage("database offline");
        assertThat(dao.writes).isEqualTo(1);
    }

    static class WritingDao extends InMemoryBaseDao<Counter> {
        int writes;
        java.util.function.BiFunction<Counter, Integer, Integer> write = (row, version) -> 0;

        @Override public int updateByIdAndVersion(Counter row, Integer version) {
            writes++;
            return write.apply(row, version);
        }
    }

    private Counter record(int version, int count) {
        Counter row = new Counter();
        row.setId("record-1");
        row.setTenantId("tenant-1");
        row.setVersion(version);
        row.count = count;
        return row;
    }

    static class Counter extends StandardEntity {
        int count;
        String password;
    }
}
