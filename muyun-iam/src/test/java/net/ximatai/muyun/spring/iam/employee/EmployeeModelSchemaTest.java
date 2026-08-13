package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.builder.TableWrapper;
import net.ximatai.muyun.spring.common.schema.StaticEntityTableMapper;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeeModelSchemaTest {
    private final StaticEntityTableMapper mapper = new StaticEntityTableMapper();

    @Test
    void shouldMapEmployeeAsDepartmentScopedMasterData() {
        TableWrapper table = mapper.toTable(Employee.class);

        assertThat(table.getName()).isEqualTo("iam_employee");
        assertThat(columnNames(table))
                .contains("id", "tenant_id", "avatar_asset_id", "organization_id", "department_id", "employee_no",
                        "title", "gender", "mobile", "email", "sort_order", "enabled", "deleted", "version");
        assertThat(table.getColumns().stream().filter(column -> "organization_id".equals(column.getName())).findFirst())
                .get()
                .satisfies(column -> {
                    assertThat(column.getLength()).isEqualTo(32);
                    assertThat(column.isNullable()).isFalse();
                });
        assertThat(table.getColumns().stream().filter(column -> "department_id".equals(column.getName())).findFirst())
                .get()
                .satisfies(column -> {
                    assertThat(column.getLength()).isEqualTo(32);
                    assertThat(column.isNullable()).isFalse();
                });
        assertThat(table.getIndexes())
                .anySatisfy(index -> {
                    assertThat(index.isUnique()).isTrue();
                    assertThat(index.getColumns()).containsExactly("tenant_id", "organization_id", "employee_no");
                });
    }

    @Test
    void shouldAllowEmployeeAvatarUpToOneMegabyte() throws NoSuchFieldException {
        FileReference avatarReference = Employee.class.getDeclaredField("avatarAssetId")
                .getAnnotation(FileReference.class);

        assertThat(avatarReference.maxFileSizeBytes()).isEqualTo(1024 * 1024);
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
