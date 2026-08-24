package net.ximatai.muyun.spring.ability.query;

import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.DictionaryField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySchemaTest {
    @Test
    void shouldExposeDescriptorAsFrontendConsumableSchema() {
        QueryDescriptor descriptor = QueryDescriptor.builder("iam.employee")
                .field(QueryField.of("employeeNo", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.LIKE)
                        .withTitle("职员编号")
                        .withQuickSearch()
                        .withSortable())
                .field(QueryField.of("enabled", QueryValueType.BOOLEAN, QueryOperator.EQ)
                        .withTitle("启用状态"))
                .externalCriteria("departmentScope", value -> null)
                .defaultSort(Sort.asc("employeeNo"))
                .build();

        QuerySchema schema = QuerySchema.from(descriptor);

        assertThat(schema.scopeName()).isEqualTo("iam.employee");
        assertThat(schema.entityAlias()).isNull();
        assertThat(schema.quickSearch().enabled()).isTrue();
        assertThat(schema.quickSearch().fields()).containsExactly("employeeNo");
        assertThat(schema.quickSearch().fieldSchemas()).singleElement()
                .extracting(QuerySchema.Field::title)
                .isEqualTo("职员编号");
        assertThat(schema.fields()).hasSize(2);
        assertThat(schema.fields().getFirst().name()).isEqualTo("employeeNo");
        assertThat(schema.fields().getFirst().title()).isEqualTo("职员编号");
        assertThat(schema.fields().getFirst().operators()).containsExactly(QueryOperator.EQ, QueryOperator.LIKE);
        assertThat(schema.fields().getFirst().defaultOperator()).isEqualTo(QueryOperator.LIKE);
        assertThat(schema.fields().getFirst().quickSearch()).isTrue();
        assertThat(schema.fields().getFirst().sortable()).isTrue();
        assertThat(schema.externalCriteria()).singleElement().satisfies(criteria -> {
            assertThat(criteria.key()).isEqualTo("departmentScope");
            assertThat(criteria.valueType()).isEqualTo("JSON");
            assertThat(criteria.providedBy()).isEqualTo("PAGE_CONTEXT");
        });
        assertThat(schema.defaultSorts()).singleElement().satisfies(sort -> {
            assertThat(sort.field()).isEqualTo("employeeNo");
            assertThat(sort.desc()).isFalse();
        });
    }

    @Test
    void shouldBuildDescriptorFromModelFieldsAndStaticConventions() {
        QueryDescriptor descriptor = QueryDescriptors.fromModel("platform.application",
                TypedQueryRecord.class,
                java.util.List.of("id", "code", "title", "enabled", "sortOrder", "createdAt"),
                Sort.asc("sortOrder"));

        QuerySchema schema = QuerySchema.from(descriptor);

        assertThat(schema.fields()).extracting(QuerySchema.Field::name)
                .containsExactly("id", "code", "title", "enabled", "sortOrder", "createdAt");
        assertThat(schema.quickSearch().enabled()).isTrue();
        assertThat(schema.quickSearch().fields()).containsExactly("code", "title");
        assertThat(schema.fields()).filteredOn(field -> field.name().equals("title"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.title()).isEqualTo("名称");
                    assertThat(field.defaultOperator()).isEqualTo(QueryOperator.LIKE);
                    assertThat(field.quickSearch()).isTrue();
                    assertThat(field.sortable()).isTrue();
                });
        assertThat(schema.fields()).filteredOn(field -> field.name().equals("enabled"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.title()).isEqualTo("启用状态");
                    assertThat(field.valueType()).isEqualTo(QueryValueType.BOOLEAN);
                    assertThat(field.operators()).containsExactly(QueryOperator.EQ);
                });
        assertThat(schema.fields()).filteredOn(field -> field.name().equals("sortOrder"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.valueType()).isEqualTo(QueryValueType.INTEGER);
                    assertThat(field.sortable()).isTrue();
                });
        assertThat(schema.fields()).filteredOn(field -> field.name().equals("createdAt"))
                .singleElement()
                .extracting(QuerySchema.Field::valueType)
                .isEqualTo(QueryValueType.INSTANT);
        QueryDescriptor effectiveDescriptor = QueryDescriptors.fromModel("platform.code_rule",
                TypedQueryRecord.class,
                java.util.List.of("effectiveFrom", "effectiveTo"));
        QuerySchema effectiveSchema = QuerySchema.from(effectiveDescriptor);
        assertThat(effectiveSchema.fields()).allSatisfy(field -> {
            assertThat(field.valueType()).isEqualTo(QueryValueType.DATETIME);
            assertThat(field.operators()).contains(
                    QueryOperator.GTE,
                    QueryOperator.LTE,
                    QueryOperator.BETWEEN);
        });
        assertThat(schema.defaultSorts()).singleElement()
                .satisfies(sort -> {
                    assertThat(sort.field()).isEqualTo("sortOrder");
                    assertThat(sort.desc()).isFalse();
                });
    }

    @Test
    void shouldBuildStaticDescriptorFromModelFieldTypes() {
        QueryDescriptor descriptor = QueryDescriptors.fromModel("test.invoice",
                TypedQueryRecord.class,
                java.util.List.of("title", "enabled", "sortOrder", "customFlag", "customAmount",
                        "businessDate", "localChangedAt", "code"),
                Sort.asc("sortOrder"));

        QuerySchema schema = QuerySchema.from(descriptor);

        assertThat(schema.fields()).filteredOn(field -> field.name().equals("title"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.valueType()).isEqualTo(QueryValueType.STRING);
                    assertThat(field.quickSearch()).isTrue();
                });
        assertThat(schema.fields()).filteredOn(field -> field.name().equals("enabled"))
                .singleElement()
                .extracting(QuerySchema.Field::valueType)
                .isEqualTo(QueryValueType.BOOLEAN);
        assertThat(schema.fields()).filteredOn(field -> field.name().equals("sortOrder"))
                .singleElement()
                .extracting(QuerySchema.Field::valueType)
                .isEqualTo(QueryValueType.INTEGER);
        assertThat(schema.fields()).filteredOn(field -> field.name().equals("customFlag"))
                .singleElement()
                .extracting(QuerySchema.Field::valueType)
                .isEqualTo(QueryValueType.BOOLEAN);
        assertThat(schema.fields()).filteredOn(field -> field.name().equals("customAmount"))
                .singleElement()
                .extracting(QuerySchema.Field::valueType)
                .isEqualTo(QueryValueType.DECIMAL);
        assertThat(schema.fields()).filteredOn(field -> field.name().equals("businessDate"))
                .singleElement()
                .extracting(QuerySchema.Field::valueType)
                .isEqualTo(QueryValueType.DATE);
        assertThat(schema.fields()).filteredOn(field -> field.name().equals("localChangedAt"))
                .singleElement()
                .extracting(QuerySchema.Field::valueType)
                .isEqualTo(QueryValueType.DATETIME);
        assertThat(schema.fields()).filteredOn(field -> field.name().equals("code"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.valueType()).isEqualTo(QueryValueType.STRING);
                    assertThat(field.quickSearch()).isTrue();
                });
        assertThat(schema.defaultSorts()).singleElement()
                .satisfies(sort -> assertThat(sort.field()).isEqualTo("sortOrder"));
    }

    @Test
    void shouldMergeStaticOptionFieldMetadataIntoQuerySchema() {
        QueryDescriptor descriptor = QueryDescriptor.builder("iam.employee")
                .field(QueryField.of("gender", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.IN)
                        .withTitle("性别")
                        .withQuickSearch())
                .build();

        QuerySchema schema = QuerySchema.from(descriptor, EmployeeOptionRecord.class);

        assertThat(schema.fields()).singleElement().satisfies(field -> {
            assertThat(field.name()).isEqualTo("gender");
            assertThat(field.optionBinding()).isEqualTo(OptionBinding.dictionary("iam", "gender"));
            assertThat(field.optionTitleField()).isEqualTo("genderTitle");
        });
        assertThat(schema.quickSearch().fieldSchemas()).singleElement()
                .satisfies(field -> assertThat(field.optionBinding())
                        .isEqualTo(OptionBinding.dictionary("iam", "gender")));
    }

    @Test
    void shouldKeepExplicitQueryOptionBindingWhenStaticOptionFieldAlsoExists() {
        QueryDescriptor descriptor = QueryDescriptor.builder("iam.employee")
                .field(QueryField.of("gender", QueryValueType.STRING, QueryOperator.EQ)
                        .withOptionBinding(OptionBinding.dictionary("crm", "gender")))
                .build();

        QuerySchema schema = QuerySchema.from(descriptor, EmployeeOptionRecord.class);

        assertThat(schema.fields()).singleElement().satisfies(field -> {
            assertThat(field.optionBinding()).isEqualTo(OptionBinding.dictionary("crm", "gender"));
            assertThat(field.optionTitleField()).isEqualTo("genderTitle");
        });
    }

    private static class EmployeeOptionRecord {
        @DictionaryField(source = "iam.gender")
        private String gender;

        @OptionLoad(source = "gender")
        private String genderTitle;
    }

    private static class TypedQueryRecord extends StandardEnabledSortableEntity {
        private boolean customFlag;

        private BigDecimal customAmount;

        private LocalDate businessDate;

        private LocalDateTime localChangedAt;

        private LocalDateTime effectiveFrom;

        private LocalDateTime effectiveTo;
    }
}
