package net.ximatai.muyun.spring.dynamic.schema;

import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.title.TitleField;

import net.ximatai.muyun.database.core.builder.Column;
import net.ximatai.muyun.database.core.builder.TableWrapper;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraintDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicTableMapperTest {
    private final DynamicTableMapper mapper = new DynamicTableMapper();
    private final ModuleDefinitionValidator validator = new ModuleDefinitionValidator();

    @Test
    void shouldMapDynamicEntityToTableWithStandardColumnsAndIndexes() {
        EntityDefinition entity = contractEntity();

        TableWrapper table = mapper.toTable(entity);

        assertThat(table.getSchema()).isEqualTo(EntityDefinition.DEFAULT_SCHEMA_NAME);
        assertThat(table.getName()).isEqualTo("app_contract");
        assertThat(table.getComment()).isEqualTo("Contract");
        assertThat(table.getPrimaryKey().getName()).isEqualTo("id");
        assertThat(columnNames(table))
                .containsExactly(
                        "tenant_id", "version", "deleted", "deleted_at", "deleted_by", "created_by", "created_at", "updated_by", "updated_at",
                        "code", "name", "amount", "signed_at"
                );
        assertThat(table.getIndexes())
                .anySatisfy(index -> {
                    assertThat(index.isUnique()).isTrue();
                    assertThat(index.getColumns()).containsExactly("tenant_id", "code");
                })
                .anySatisfy(index -> {
                    assertThat(index.isUnique()).isFalse();
                    assertThat(index.getColumns()).containsExactly("signed_at");
                });
        assertThat(column(table, "name").isNullable()).isFalse();
        assertThat(column(table, "name").getLength()).isEqualTo(128);
        assertThat(column(table, "amount").getPrecision()).isEqualTo(18);
        assertThat(column(table, "amount").getScale()).isEqualTo(2);
    }

    @Test
    void shouldNormalizeBlankEntitySchemaToDefaultSchema() {
        EntityDefinition entity = new EntityDefinition(
                "contract",
                "",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("code", "Code")),
                java.util.Set.of(EntityCapability.CRUD)
        );

        assertThat(entity.schemaName()).isEqualTo(EntityDefinition.DEFAULT_SCHEMA_NAME);
        assertThat(mapper.toTable(entity).getSchema()).isEqualTo(EntityDefinition.DEFAULT_SCHEMA_NAME);
    }

    @Test
    void shouldMapDeclaredCompositeTenantUniqueConstraint() {
        EntityDefinition entity = new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("category", "Category"),
                        FieldDefinition.string("code", "Code")
                )
        ).withTenantUniqueConstraints(new TenantUniqueConstraintDefinition(List.of("category", "code"), ""));

        assertThat(entity.resolvedTenantUniqueConstraints())
                .extracting(TenantUniqueConstraintDefinition::fieldNames)
                .containsExactly(List.of("category", "code"));
        assertThat(mapper.toTable(entity).getIndexes()).anySatisfy(index -> {
            assertThat(index.isUnique()).isTrue();
            assertThat(index.getColumns()).containsExactly("tenant_id", "category", "code");
        });
    }

    @Test
    void shouldMapEntitySchemaToTableSchema() {
        EntityDefinition entity = new EntityDefinition(
                "contract",
                "tenant_a",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("code", "Code")),
                java.util.Set.of(EntityCapability.CRUD)
        );

        TableWrapper table = mapper.toTable(entity);

        assertThat(table.getSchema()).isEqualTo("tenant_a");
    }

    @Test
    void shouldKeepDynamicStandardColumnsAlignedWithStandardEntity() {
        TableWrapper table = mapper.toTable(contractEntity());

        List<String> dynamicColumns = new java.util.ArrayList<>();
        dynamicColumns.add(table.getPrimaryKey().getName());
        dynamicColumns.addAll(columnNames(table).stream()
                .filter(name -> name.equals("tenant_id")
                        || name.equals("version")
                        || name.equals("deleted")
                        || name.equals("deleted_at")
                        || name.equals("deleted_by")
                        || name.equals("created_by")
                        || name.equals("created_at")
                        || name.equals("updated_by")
                        || name.equals("updated_at"))
                .toList());

        assertThat(dynamicColumns).containsExactlyElementsOf(StandardEntitySchema.columnNames());
    }

    @Test
    void shouldMapRemovedDynamicFieldsToExplicitDroppedColumns() {
        EntityDefinition previous = new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64),
                        FieldDefinition.string("removedName", "Removed Name").column("removed_name").length(128)
                )
        );
        EntityDefinition next = new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("code", "Code").length(64))
        );

        TableWrapper table = mapper.toTable(next, previous);

        assertThat(table.getDroppedColumns()).containsExactly("removed_name");
    }

    @Test
    void shouldKeepDataScopeColumnsWhenDiffingDynamicFields() {
        EntityDefinition previous = new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64),
                        FieldDefinition.string("removedName", "Removed Name").column("removed_name").length(128)
                )
        ).withCapabilities(EntityCapability.DATA_SCOPE);
        EntityDefinition next = new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("code", "Code").length(64))
        ).withCapabilities(EntityCapability.DATA_SCOPE);

        TableWrapper table = mapper.toTable(next, previous);

        assertThat(table.getDroppedColumns()).containsExactly("removed_name");
        assertThat(table.getDroppedColumns()).doesNotContain("auth_user_id", "auth_assignee_ids");
    }

    @Test
    void shouldRejectUnsafeIdentifiersAndDuplicateColumns() {
        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract;drop",
                "Contract",
                List.of()
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("invalid table name");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("name", "Name"),
                        FieldDefinition.string("title", "Title").column("name")
                )
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("duplicate column name");
    }

    @Test
    void shouldRejectFieldsThatConflictWithStandardColumns() {
        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.bool("customDeleted", "Deleted").column("deleted"))
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("standard column");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("tenantId", "Tenant"))
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("standard field");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("authUserId", "Auth User").column("auth_user_id"))
        ).withCapabilities(EntityCapability.DATA_SCOPE))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("data scope ability field");
    }

    @Test
    void shouldValidateModuleEntityUniqueness() {
        EntityDefinition entity = contractEntity();
        ModuleDefinition module = new ModuleDefinition(
                "contract.app",
                "Contract App",
                List.of(entity, new EntityDefinition("contract_copy", "app_contract", "Contract Copy", List.of()))
        );

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("duplicate table name");
    }

    @Test
    void shouldRequireDottedModuleAlias() {
        ModuleDefinition module = new ModuleDefinition(
                "contract_app",
                "Contract App",
                List.of(contractEntity())
        );

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("invalid module alias");
    }

    @Test
    void shouldRejectModuleAliasIncompatibleWithPlatformAliasRule() {
        ModuleDefinition module = new ModuleDefinition(
                "contract.app-profile",
                "Contract App",
                List.of(contractEntity())
        );

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("invalid module alias");
    }

    @Test
    void shouldSupportConciseFieldFactoriesAndEntityCapabilities() {
        EntityDefinition entity = new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required().unique(),
                        FieldDefinition.titleField().required(),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2),
                        FieldDefinition.sortOrder()
                )
        ).withCapabilities(
                EntityCapability.CRUD,
                EntityCapability.SORT,
                EntityCapability.REFERENCE
        );

        TableWrapper table = mapper.toTable(entity);

        assertThat(entity.supports(EntityCapability.SORT)).isTrue();
        assertThat(table.getIndexes())
                .anySatisfy(index -> {
                    assertThat(index.isUnique()).isTrue();
                    assertThat(index.getColumns()).containsExactly("tenant_id", "code");
                })
                .anySatisfy(index -> {
                    assertThat(index.isUnique()).isFalse();
                    assertThat(index.getColumns()).containsExactly("sort_order");
                });
    }

    @Test
    void shouldNormalizeEntityCapabilities() {
        EntityDefinition entity = new EntityDefinition(
                "category",
                "app_category",
                "Category",
                List.of(
                        FieldDefinition.parentId(),
                        FieldDefinition.sortOrder()
                )
        ).withCapabilities(EntityCapability.TREE);

        TableWrapper table = mapper.toTable(entity);

        assertThat(entity.supports(EntityCapability.CRUD)).isTrue();
        assertThat(entity.supports(EntityCapability.SOFT_DELETE)).isTrue();
        assertThat(entity.supports(EntityCapability.LIFECYCLE)).isTrue();
        assertThat(entity.supports(EntityCapability.CACHE)).isTrue();
        assertThat(entity.supports(EntityCapability.TREE)).isTrue();
        assertThat(entity.supports(EntityCapability.SORT)).isTrue();
        assertThat(columnNames(table)).contains("parent_id", "sort_order");
    }

    @Test
    void shouldAddDataScopeColumnsFromCapability() {
        EntityDefinition entity = contractEntity().withCapabilities(EntityCapability.DATA_SCOPE);

        TableWrapper table = mapper.toTable(entity);

        assertThat(entity.supports(EntityCapability.DATA_SCOPE)).isTrue();
        assertThat(columnNames(table))
                .contains("auth_user_id", "auth_assignee_ids", "auth_member_ids",
                        "auth_organization_id", "auth_department_id", "auth_module_alias")
                .contains("code", "name");
    }

    @Test
    void shouldAddWorkflowAndApprovalAbilityColumns() {
        EntityDefinition workflowEntity = contractEntity().withCapabilities(EntityCapability.WORKFLOW);
        EntityDefinition approvalEntity = contractEntity().withCapabilities(EntityCapability.APPROVAL);

        assertThat(columnNames(mapper.toTable(workflowEntity)))
                .doesNotContain("approval_instance_id", "approval_status", "approval_submitted_by",
                        "approval_submitted_at", "approval_completed_at");
        assertThat(approvalEntity.supports(EntityCapability.WORKFLOW)).isTrue();
        assertThat(columnNames(mapper.toTable(approvalEntity)))
                .contains("approval_instance_id", "approval_status", "approval_submitted_by",
                        "approval_submitted_at", "approval_completed_at");
    }

    @Test
    void explicitlyMaterializedApprovalAndImplicitDataScopeProduceEachCanonicalColumnOnce() {
        var fields = new java.util.ArrayList<>(contractEntity().fields());
        fields.addAll(net.ximatai.muyun.spring.dynamic.metadata.DynamicAbilityFields.approvalFields());
        var entity = new EntityDefinition("contract", "app_contract", "Contract", fields)
                .withCapabilities(EntityCapability.APPROVAL, EntityCapability.DATA_SCOPE);
        var columns = columnNames(mapper.toTable(entity));
        assertThat(columns).doesNotHaveDuplicates();
        assertThat(columns).contains("approval_instance_id", "approval_status", "approval_submitted_at", "auth_user_id");
        var record = new net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord(entity);
        record.setApprovalStatus("processing");
        assertThat(record.getApprovalStatus()).isEqualTo("processing");
        var mapping = new net.ximatai.muyun.spring.dynamic.runtime.mapping.DynamicRecordMapping(entity);
        for (var field : net.ximatai.muyun.spring.dynamic.metadata.DynamicAbilityFields.approvalFields()) {
            assertThat(mapping.resolveColumnName(field.fieldName())).isEqualTo(field.columnName());
            assertThat(mapping.resolveFieldName(field.columnName())).isEqualTo(field.fieldName());
        }
    }

    @Test
    void capabilityColumnsRejectConflictingNamesAndTypesInsteadOfSilentlyOverwritingThem() {
        for (var conflict : List.of(FieldDefinition.string("businessValue", "Business").column("approval_status"),
                FieldDefinition.integer("approvalStatus", "Status").column("approval_status"),
                FieldDefinition.string("approvalStatus", "Status").column("wrong_status"))) {
            var entity = new EntityDefinition("contract", "app_contract", "Contract", List.of(conflict))
                    .withCapabilities(EntityCapability.APPROVAL);
            assertThatThrownBy(() -> mapper.toTable(entity)).isInstanceOf(ModuleDefinitionException.class)
                    .hasMessageContaining("platform capability storage");
        }
    }

    @Test
    void shouldClassifyBaselineFieldAndDefinitionCapabilitiesInSameCatalog() {
        assertThat(EntityCapability.CRUD.isBaseline()).isTrue();
        assertThat(EntityCapability.SOFT_DELETE.isBaseline()).isTrue();
        assertThat(EntityCapability.TREE.isDeclaredByEntityFields()).isTrue();
        assertThat(EntityCapability.REFERENCE.isDeclaredByEntityFields()).isTrue();
        assertThat(EntityCapability.ENABLE.isDeclaredByEntityFields()).isTrue();
        assertThat(EntityCapability.DATA_SCOPE.isDeclaredByEntityFields()).isTrue();
        assertThat(EntityCapability.WORKFLOW.isDeclaredByDefinition()).isTrue();
        assertThat(EntityCapability.APPROVAL.isDeclaredByEntityFields()).isTrue();
        assertThat(EntityCapability.CHILD_RELATION.isDeclaredByDefinition()).isTrue();
        assertThat(EntityCapability.REFERENCE_DEPENDENCY.isDeclaredByDefinition()).isTrue();
        assertThat(EntityCapability.EXCHANGE.isDeclaredByDefinition()).isTrue();

        EntityDefinition entity = contractEntity();

        assertThat(entity.supports(EntityCapability.CRUD)).isTrue();
        assertThat(entity.supports(EntityCapability.SOFT_DELETE)).isTrue();
        assertThat(entity.supports(EntityCapability.CACHE)).isTrue();
        assertThat(entity.supports(EntityCapability.TREE)).isFalse();
        assertThat(entity.supports(EntityCapability.ENABLE)).isFalse();
    }

    @Test
    void shouldRejectInvalidFieldTypeOptionsBeforeDdl() {
        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.decimal("amount", "Amount").precision(2, 10))
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("scale must not exceed precision");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.bool("enabled", "Enabled").length(8))
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("length only applies");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("name", "Name").precision(10, 2))
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("precision and scale only apply");
    }

    @Test
    void shouldRejectInvalidSortableFields() {
        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("name", "Name").sortable())
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("sortable field must be an integer type");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.sortOrder(),
                        FieldDefinition.integer("rankOrder", "Rank Order").column("rank_order").sortable()
                )
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("only have one sortable field");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.sortOrder())
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("requires SORT capability");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("code", "Code"))
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.SORT))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("SORT capability requires standard field sortOrder");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.integer("rankOrder", "Rank Order").column("rank_order").sortable())
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.SORT))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("SORT capability requires standard field sortOrder/sort_order");
    }

    @Test
    void shouldRejectInvalidTreeFields() {
        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "category",
                "app_category",
                "Category",
                List.of(FieldDefinition.sortOrder())
        ).withCapabilities(EntityCapability.TREE))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("TREE capability requires standard field parentId");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "category",
                "app_category",
                "Category",
                List.of(
                        FieldDefinition.string("parentId", "Parent").column("parent"),
                        FieldDefinition.sortOrder()
                )
        ).withCapabilities(EntityCapability.TREE))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("TREE capability requires standard field parentId/parent_id");
    }

    @Test
    void shouldRequireExplicitEnableCapabilityForEnabledField() {
        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.enabled())
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("enabled field requires ENABLE capability");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.bool("active", "Active").column("enabled"))
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("enabled field requires ENABLE capability");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("code", "Code"))
        ).withCapabilities(EntityCapability.ENABLE))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("ENABLE capability requires standard field enabled");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.bool("active", "Active").column("enabled"))
        ).withCapabilities(EntityCapability.ENABLE))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("ENABLE capability requires standard field enabled");

        EntityDefinition entity = new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.enabled())
        ).withCapabilities(EntityCapability.ENABLE);

        TableWrapper table = mapper.toTable(entity);

        assertThat(entity.supports(EntityCapability.ENABLE)).isTrue();
        assertThat(columnNames(table)).contains("enabled");
    }

    @Test
    void shouldRejectInvalidTitleFields() {
        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.decimal("amount", "Amount").title())
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("title field must be a text type");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").title(),
                        FieldDefinition.titleField()
                )
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("only have one title field");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.titleField())
        ))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("requires REFERENCE capability");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("code", "Code"))
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.REFERENCE))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("REFERENCE capability requires standard field title");

        assertThatThrownBy(() -> mapper.toTable(new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.string("name", "Name").title())
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.REFERENCE))).isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("REFERENCE capability requires standard field title/title");
    }

    private EntityDefinition contractEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required().unique(),
                        FieldDefinition.string("name", "Name").length(128).required(),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2),
                        FieldDefinition.timestamp("signedAt", "Signed At").column("signed_at").indexed()
                )
        );
    }

    private List<String> columnNames(TableWrapper table) {
        return table.getColumns().stream().map(Column::getName).toList();
    }

    private Column column(TableWrapper table, String name) {
        return table.getColumns().stream()
                .filter(column -> column.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
