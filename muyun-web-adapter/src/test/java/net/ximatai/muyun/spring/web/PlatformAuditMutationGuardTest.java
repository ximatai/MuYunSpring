package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PlatformAuditMutationGuardTest {
    @Test void rejectsForgedAuditValuesAndPreservesUnchangedRoundTrips() {
        StandardEntity existing = new StandardEntity() { };
        existing.setCreatedBy("user-1");
        existing.setCreatedAt(Instant.parse("2026-09-08T01:00:00Z"));
        assertThatThrownBy(() -> PlatformAuditMutationGuard.validate(Map.of("createdBy", "user-2"), existing))
                .hasMessageContaining("不可修改");
        assertThatThrownBy(() -> PlatformAuditMutationGuard.validate(Map.of("createdBy", "user-1"), null))
                .hasMessageContaining("不可修改");
        assertThatCode(() -> PlatformAuditMutationGuard.validate(Map.of("createdBy", "user-1"), existing))
                .doesNotThrowAnyException();
        StandardEntity edit = new StandardEntity() { };
        PlatformAuditMutationGuard.validate(edit, existing);
        assertThat(edit.getCreatedBy()).isEqualTo("user-1");
        assertThat(edit.getCreatedAt()).isEqualTo(existing.getCreatedAt());
    }
}
