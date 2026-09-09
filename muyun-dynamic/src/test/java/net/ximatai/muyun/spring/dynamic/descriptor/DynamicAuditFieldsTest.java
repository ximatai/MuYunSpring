package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class DynamicAuditFieldsTest {
    @Test void exposesReadOnlyReferencesWithoutDeclaringBusinessColumns() {
        EntityDefinition entity = new EntityDefinition("task", "test_task", "任务", List.of(FieldDefinition.string("title", "标题")));
        var descriptor = DynamicEntityDescriptor.from(entity);
        assertThat(entity.fields()).extracting(FieldDefinition::fieldName).containsExactly("title");
        assertThat(descriptor.fields()).filteredOn(field -> PlatformFieldPolicy.isAudit(field.fieldName()))
                .hasSize(4).allSatisfy(field -> {
                    assertThat(field.writeProtected()).isTrue();
                    assertThat(field.copyable()).isFalse();
                    assertThat(field.query().queryable()).isTrue();
                });
        assertThat(descriptor.fields()).filteredOn(field -> "createdBy".equals(field.fieldName()))
                .singleElement().satisfies(field -> assertThat(field.reference().targetModuleAlias()).isEqualTo("iam.user"));
        assertThat(PlatformFieldPolicy.find("authUserId").composable()).isFalse();
        DynamicRecord record = new DynamicRecord(entity);
        record.setCreatedAt(Instant.parse("2026-09-08T01:00:00Z"));
        assertThat(record.getValue("createdAt")).isEqualTo(record.getCreatedAt());
        assertThatThrownBy(() -> record.setValue("createdBy", "forged")).hasMessageContaining("unknown dynamic field");
        assertThatCode(() -> new DynamicQueryCriteriaBuilder(entity).build(List.of(
                new DynamicQueryCondition("createdBy", DynamicQueryOperator.EQ, List.of("user-1")))))
                .doesNotThrowAnyException();
    }
}
