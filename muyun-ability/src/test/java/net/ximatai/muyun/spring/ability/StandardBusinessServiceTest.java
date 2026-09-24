package net.ximatai.muyun.spring.ability;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StandardBusinessServiceTest {
    @Test
    void updateCombinesExistingOverrideCommonValidationAndSnapshotValidationOnce() {
        List<String> hooks = new ArrayList<>();
        DemoPlainRecord stored = new DemoPlainRecord("Original");
        class Service extends StandardBusinessService<DemoPlainRecord> implements SoftDeleteAbility<DemoPlainRecord> {
            Service() { super("test.save", DemoPlainRecord.class, new InMemoryBaseDao<>()); }
            @Override public void beforeUpdate(DemoPlainRecord incoming) {
                hooks.add("public");
                super.beforeUpdate(incoming);
            }
            @Override protected void validateBeforeSave(DemoPlainRecord incoming) {
                hooks.add("save");
                incoming.setTitle(incoming.getTitle().trim());
            }
            @Override protected void validateBeforeUpdate(DemoPlainRecord incoming, DemoPlainRecord existing) {
                hooks.add("snapshot");
                assertThat(existing).isSameAs(stored);
                assertThat(existing.getTitle()).isEqualTo("Original");
                assertThat(incoming.getTitle()).isEqualTo("Updated");
            }
        }
        var service = new Service();
        service.insert(stored);
        hooks.clear();
        var incoming = new DemoPlainRecord(" Updated ");
        incoming.setId(stored.getId()); incoming.setVersion(stored.getVersion());
        assertThat(service.update(incoming)).isEqualTo(1);
        assertThat(hooks).containsExactly("public", "save", "snapshot");
    }
}
