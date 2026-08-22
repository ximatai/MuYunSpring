package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.builder.TableWrapper;
import net.ximatai.muyun.spring.common.schema.StaticEntityTableMapper;
import net.ximatai.muyun.spring.ability.child.ChildOf;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeePositionModelSchemaTest {
    private final StaticEntityTableMapper mapper = new StaticEntityTableMapper();

    @Test
    void shouldMapEmployeePositionAsRelationFact() {
        TableWrapper table = mapper.toTable(EmployeePosition.class);

        assertThat(table.getName()).isEqualTo("iam_employee_position");
        assertThat(columnNames(table))
                .contains("id", "tenant_id", "employee_id", "organization_id", "department_id",
                        "position_id", "primary_position", "enabled", "sort_order", "deleted", "version");
        assertThat(table.getIndexes())
                .anySatisfy(index -> {
                    assertThat(index.isUnique()).isTrue();
                    assertThat(index.getColumns()).containsExactly("tenant_id", "employee_id",
                            "organization_id", "department_id", "position_id");
                });
    }

    @Test
    void shouldDeclareEmployeeOwnershipForAggregateLifecycle() throws NoSuchFieldException {
        assertThat(EmployeePosition.class.getDeclaredField("employeeId").getAnnotation(ChildOf.class)).isNotNull();
    }

    private Set<String> columnNames(TableWrapper table) {
        Set<String> names = new LinkedHashSet<>();
        if (table.getPrimaryKey() != null) {
            names.add(table.getPrimaryKey().getName());
        }
        table.getColumns().forEach(column -> names.add(column.getName()));
        return names;
    }
}
