package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.dynamic.metadata.FieldStorageForm;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicQuerySchemasTest {
    @Test
    void shouldExposeDeclaredExternalCriteriaKeys() {
        QuerySchema schema = DynamicQuerySchemas.from("iam.employee", new DynamicEntityDescriptor(
                "employee", "职员", Set.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(), Arrays.asList("projectId", "projectId", " ", null));

        assertThat(schema.externalCriteria()).extracting(QuerySchema.ExternalCriteria::key)
                .containsExactly("projectId");
    }

    @Test
    void shouldMapDynamicFieldQueryDefinitionsToStandardQuerySchema() {
        DynamicEntityDescriptor descriptor = new DynamicEntityDescriptor(
                "employee",
                "职员",
                Set.of(),
                List.of(
                        field("employeeNo", "职员编号", FieldType.STRING, true, true,
                                "LIKE", List.of("EQ", "LIKE")),
                        field("enabled", "启用状态", FieldType.BOOLEAN, false, true,
                                "EQ", List.of("EQ")),
                        field("remark", "备注", FieldType.STRING, false, false,
                                null, List.of()),
                        field("secret", "内部字段", FieldType.STRING, false, false,
                                null, List.of())
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        QuerySchema schema = DynamicQuerySchemas.from("iam.employee", descriptor, List.of("employeeNo", "remark"));

        assertThat(schema.scopeName()).isEqualTo("iam.employee");
        assertThat(schema.entityAlias()).isEqualTo("employee");
        assertThat(schema.quickSearch().enabled()).isTrue();
        assertThat(schema.quickSearch().fields()).containsExactly("employeeNo", "remark");
        assertThat(schema.fields()).extracting(QuerySchema.Field::name)
                .containsExactly("employeeNo", "enabled");
        assertThat(schema.quickSearch().fieldSchemas()).extracting(QuerySchema.Field::name)
                .containsExactly("employeeNo", "remark");
        QuerySchema.Field employeeNo = schema.fields().getFirst();
        assertThat(employeeNo.title()).isEqualTo("职员编号");
        assertThat(employeeNo.valueType()).isEqualTo(QueryValueType.STRING);
        assertThat(employeeNo.operators()).containsExactly(QueryOperator.EQ, QueryOperator.LIKE);
        assertThat(employeeNo.defaultOperator()).isEqualTo(QueryOperator.LIKE);
        assertThat(employeeNo.quickSearch()).isTrue();
        assertThat(employeeNo.sortable()).isTrue();
        QuerySchema.Field enabled = schema.fields().get(1);
        assertThat(enabled.valueType()).isEqualTo(QueryValueType.BOOLEAN);
        assertThat(enabled.quickSearch()).isFalse();
        QuerySchema.Field remark = schema.quickSearch().fieldSchemas().get(1);
        assertThat(remark.operators()).containsExactly(QueryOperator.LIKE);
        assertThat(remark.defaultOperator()).isEqualTo(QueryOperator.LIKE);
        assertThat(remark.quickSearch()).isTrue();
    }

    @Test
    void shouldExposeDynamicReferenceFieldsAsControlledIdSelections() {
        DynamicReferenceDescriptor reference = new DynamicReferenceDescriptor(
                "order", "customerId", "crm", "customer", ReferenceCardinality.ONE, List.of(),
                "id", "name", null, null, Set.of(), List.of(), List.of());
        DynamicEntityDescriptor descriptor = new DynamicEntityDescriptor(
                "order", "订单", Set.of(), List.of(field("customerId", "客户", FieldType.STRING,
                false, true, "EQ", List.of("EQ"), reference)), List.of(), List.of(), List.of(), List.of());

        QuerySchema schema = DynamicQuerySchemas.from("sales.order", descriptor, List.of());

        assertThat(schema.fields()).singleElement().extracting(QuerySchema.Field::reference)
                .isEqualTo(new QuerySchema.Reference("crm", ReferenceCardinality.ONE, "name"));
    }

    private DynamicFieldDescriptor field(String fieldName,
                                         String title,
                                         FieldType type,
                                         boolean sortable,
                                         boolean queryable,
                                         String defaultOperator,
                                         List<String> operators) {
        return field(fieldName, title, type, sortable, queryable, defaultOperator, operators, null);
    }

    private DynamicFieldDescriptor field(String fieldName,
                                         String title,
                                         FieldType type,
                                         boolean sortable,
                                         boolean queryable,
                                         String defaultOperator,
                                         List<String> operators,
                                         DynamicReferenceDescriptor reference) {
        return new DynamicFieldDescriptor(
                fieldName,
                type,
                null,
                title,
                FieldStorageForm.PHYSICAL,
                false,
                false,
                false,
                sortable,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                reference,
                List.of(),
                new DynamicFieldQueryDescriptor(queryable, defaultOperator, operators),
                null,
                null,
                false,
                false,
                false,
                false,
                null,
                null
        );
    }
}
