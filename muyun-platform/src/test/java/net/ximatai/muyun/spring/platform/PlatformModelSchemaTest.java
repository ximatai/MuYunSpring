package net.ximatai.muyun.spring.platform;

import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.database.core.builder.TableWrapper;
import net.ximatai.muyun.spring.common.schema.StaticEntityTableMapper;
import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.audit.RuntimeAuditRecord;
import net.ximatai.muyun.spring.platform.code.CodeLedgerEntry;
import net.ximatai.muyun.spring.platform.code.CodeRecycleEntry;
import net.ximatai.muyun.spring.platform.code.CodeRule;
import net.ximatai.muyun.spring.platform.code.CodeSequenceState;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleConfigVersion;
import net.ximatai.muyun.spring.platform.currency.Currency;
import net.ximatai.muyun.spring.platform.currency.ExchangeRate;
import net.ximatai.muyun.spring.platform.currency.ExchangeRateType;
import net.ximatai.muyun.spring.platform.currency.TenantCurrencySetting;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategory;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItem;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationFieldMapping;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationSplitGroupField;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationSplitPolicy;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuScheme;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldProtectionConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataView;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldAffect;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldFilter;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRule;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItem;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplate;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.workflow.WorkflowDefinition;
import net.ximatai.muyun.spring.platform.workflow.WorkflowDelegation;
import net.ximatai.muyun.spring.platform.workflow.WorkflowEvent;
import net.ximatai.muyun.spring.platform.workflow.WorkflowHistoryInstance;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstance;
import net.ximatai.muyun.spring.platform.workflow.WorkflowLinkDefinition;
import net.ximatai.muyun.spring.platform.workflow.WorkflowNodeDefinition;
import net.ximatai.muyun.spring.platform.workflow.WorkflowNodeInstance;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRouteInstance;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTask;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskCheck;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskCheckResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskDefinition;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskGuide;
import net.ximatai.muyun.spring.platform.workflow.WorkflowVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformModelSchemaTest {
    private final StaticEntityTableMapper mapper = new StaticEntityTableMapper();

    @Test
    void shouldMapApplicationAsSortableEnabledPlatformModel() {
        TableWrapper table = mapper.toTable(Application.class);

        assertThat(table.getName()).isEqualTo("platform_application");
        assertThat(columnNames(table))
                .contains("id", "title", "sort_order", "enabled", "system_managed")
                .doesNotContain("parent_id");
        assertThat(columnDefault(table, "system_managed")).isEqualTo("FALSE");
    }

    @Test
    void shouldMapModuleAsTreeEnabledPlatformModel() {
        TableWrapper table = mapper.toTable(PlatformModule.class);

        assertThat(table.getName()).isEqualTo("platform_module");
        assertThat(columnNames(table))
                .contains("id", "application_alias", "title", "parent_id", "sort_order", "enabled",
                        "module_kind", "system_managed")
                .doesNotContain("tenant_exposure");
        assertThat(table.getPrimaryKey().getLength()).isEqualTo(128);
        assertThat(table.getColumns().stream().filter(column -> "parent_id".equals(column.getName())).findFirst())
                .get()
                .satisfies(column -> assertThat(column.getLength()).isEqualTo(128));
        assertThat(table.getColumns().stream().filter(column -> "module_kind".equals(column.getName())).findFirst())
                .get()
                .satisfies(column -> assertThat(column.getLength()).isEqualTo(32));
    }

    @Test
    void shouldMapMetadataModelsAsPlatformTables() {
        assertThat(columnNames(mapper.toTable(Metadata.class)))
                .contains("id", "application_alias", "alias", "schema_name", "table_name", "title", "enabled", "sort_order");
        assertThat(columnNames(mapper.toTable(MetadataField.class)))
                .contains("id", "metadata_id", "field_name", "column_name", "field_spec_alias",
                        "field_ownership", "field_form", "owner_field_id", "field_role", "system_managed", "required",
                        "unique_field", "indexed", "sortable_field", "title_field")
                .doesNotContain("dictionary_application_alias", "dictionary_category_alias", "queryable");
        assertThat(columnNames(mapper.toTable(FieldSpec.class)))
                .contains("id", "alias", "title", "field_type", "default_length", "default_precision",
                        "default_scale", "default_query_operator", "query_operators",
                        "default_ui_control_alias", "ui_control_aliases")
                .doesNotContain("verify_regex");
        assertThat(columnType(mapper.toTable(FieldSpec.class), "query_operators"))
                .isEqualTo(ColumnType.JSON_SET);
        assertThat(columnType(mapper.toTable(FieldSpec.class), "ui_control_aliases"))
                .isEqualTo(ColumnType.JSON_SET);
        assertThat(columnNames(mapper.toTable(FieldUiControl.class)))
                .contains("id", "alias", "title", "default_field_spec_alias", "value_shape", "primary_value_key", "query_mode", "renderer_type", "icon",
                        "enabled", "sort_order");
        assertThat(columnNames(mapper.toTable(FieldUiControlProperty.class)))
                .contains("id", "field_ui_control_alias", "attribute_alias", "title", "value_field_spec_alias",
                        "default_value", "sort_order");
        assertThat(columnNames(mapper.toTable(FieldUiControlBinding.class)))
                .contains("id", "field_ui_control_alias", "value_key", "value_field_spec_alias", "title", "sort_order");
        assertThat(columnNames(mapper.toTable(MetadataFieldConfig.class)))
                .contains("id", "metadata_field_id", "relation_id", "dictionary_application_alias", "dictionary_category_alias",
                        "selection_mode", "field_length", "precision", "scale", "queryable", "default_query_operator",
                        "query_operators", "default_value", "validation_regex", "copyable", "write_protected")
                .doesNotContain("verify_regex");
        assertThat(columnType(mapper.toTable(MetadataFieldConfig.class), "query_operators"))
                .isEqualTo(ColumnType.JSON_SET);
        assertThat(columnNames(mapper.toTable(MetadataFieldProtectionConfig.class)))
                .contains("id", "metadata_field_id", "enabled", "encryption_mode", "signature_mode", "masking_policy");
        assertThat(columnNames(mapper.toTable(MetadataFieldReferenceConfig.class)))
                .contains("id", "metadata_field_id", "relation_id", "target_module_alias", "target_metadata_id",
                        "cardinality", "target_unavailable_policy", "projection_mappings")
                .doesNotContain("auto_title", "title_output_field");
        assertThat(columnNames(mapper.toTable(ModuleMetadataRelation.class)))
                .contains("id", "module_alias", "metadata_id", "relation_role", "parent_metadata_id",
                        "foreign_key", "relation_alias", "auto_populate", "sort_order")
                .doesNotContain("cascade_delete");
        assertThat(columnNames(mapper.toTable(ModuleMetadataField.class)))
                .contains("id", "relation_id", "metadata_field_id", "default_value", "cloneable",
                        "validation_regex", "dictionary_application_alias", "dictionary_category_alias",
                        "reference_module_alias", "reference_target_unavailable_policy", "reference_module_key_field", "reference_module_label_field",
                        "reference_generate_rule_id", "reference_query_template_id",
                        "reference_module_plus_fields", "unit_category_alias", "unit_mode",
                        "fixed_unit_code", "default_unit_code", "unit_field_id", "base_value_field_id",
                        "base_unit_category_alias", "base_unit_code", "unit_conversion_mode",
                        "conversion_scope_field_id", "unit_required",
                        "title", "sort_order");
        assertThat(columnType(mapper.toTable(ModuleMetadataField.class), "reference_module_plus_fields"))
                .isEqualTo(ColumnType.JSON_SET);
        assertThat(uniqueIndexes(mapper.toTable(ModuleMetadataField.class)))
                .contains(List.of("tenant_id", "relation_id", "metadata_field_id"));
        assertThat(columnNames(mapper.toTable(ModuleMetadataFieldFilter.class)))
                .contains("id", "module_metadata_field_id", "form_field_id", "reference_field_id",
                        "operator", "title", "sort_order");
        assertThat(columnNames(mapper.toTable(ModuleMetadataFieldAffect.class)))
                .contains("id", "module_metadata_field_id", "reference_field_id", "target_field_id",
                        "title", "sort_order");
        assertThat(columnNames(mapper.toTable(MetadataView.class)))
                .contains("id", "relation_id", "view_type", "title", "enabled", "sort_order");
        assertThat(columnNames(mapper.toTable(MetadataViewField.class)))
                .contains("id", "view_id", "metadata_field_id", "visible", "control_type", "field_ui_control_alias", "read_only",
                        "required_override", "title", "enabled", "sort_order");
        assertThat(columnNames(mapper.toTable(PlatformUiSet.class)))
                .contains("id", "module_alias", "alias", "set_type", "default_set", "title", "enabled", "sort_order");
        assertThat(uniqueIndexes(mapper.toTable(PlatformUiSet.class)))
                .contains(List.of("tenant_id", "module_alias", "alias"));
        assertThat(columnNames(mapper.toTable(PlatformUiConfig.class)))
                .contains("id", "ui_set_id", "client_type", "layout_json", "published", "title", "enabled", "sort_order");
        assertThat(uniqueIndexes(mapper.toTable(PlatformUiConfig.class)))
                .contains(List.of("tenant_id", "ui_set_id", "client_type"));
        assertThat(columnNames(mapper.toTable(PlatformUiConfigField.class)))
                .contains("id", "ui_config_id", "module_metadata_field_id", "field_ui_control_alias", "visible",
                        "visible_when", "read_only", "read_only_when", "required_override", "placeholder", "default_value", "width", "align",
                        "fixed_position", "max_display_lines", "title", "enabled", "sort_order");
        assertThat(uniqueIndexes(mapper.toTable(PlatformUiConfigField.class)))
                .contains(List.of("tenant_id", "ui_config_id", "module_metadata_field_id"));
        assertThat(columnNames(mapper.toTable(PlatformQueryTemplate.class)))
                .contains("id", "module_alias", "alias", "default_template", "published", "title",
                        "enabled", "sort_order");
        assertThat(uniqueIndexes(mapper.toTable(PlatformQueryTemplate.class)))
                .contains(List.of("tenant_id", "module_alias", "alias"));
        assertThat(columnNames(mapper.toTable(PlatformQueryItem.class)))
                .contains("id", "query_template_id", "parent_id", "group_operator", "module_metadata_field_id",
                        "operator", "default_value", "allow_external_value", "external_value_key", "time_zone",
                        "title", "enabled", "sort_order");
        assertThat(columnNames(mapper.toTable(PlatformModuleAction.class)))
                .contains("id", "module_alias", "entity_alias", "action_code", "permission_action_code", "title",
                        "category", "available_expression", "unavailable_message", "executor_type", "executor_key",
                        "action_level", "access_mode", "action_auth", "data_auth", "default_grant_policy",
                        "source_type", "source_id", "source_version_id",
                        "binding_type", "binding_id", "binding_alias",
                        "system_managed", "enabled", "sort_order")
                .doesNotContain("relation_id", "permission_code", "alias");
        assertThat(columnNames(mapper.toTable(ModuleMetadataFormulaRule.class)))
                .contains("id", "relation_id", "alias", "rule_kind", "rule_phase", "target_field",
                        "expression", "severity", "message_template", "stop_on_error", "enabled", "sort_order");
        assertThat(columnNames(mapper.toTable(LowCodeModuleConfigVersion.class)))
                .contains("id", "tenant_id", "module_alias", "version_no", "version_status", "current_version",
                        "package_snapshot_text", "package_hash", "summary_json", "source_version_id",
                        "archived_by", "archived_at", "remark");
        assertThat(uniqueIndexes(mapper.toTable(LowCodeModuleConfigVersion.class)))
                .contains(List.of("tenant_id", "module_alias", "version_no"));
    }

    @Test
    void shouldMapMenuModelsAsPlatformTables() {
        assertThat(columnNames(mapper.toTable(MenuScheme.class)))
                .contains("id", "tenant_id", "alias", "scope_type", "scope_id", "title", "enabled", "sort_order")
                .doesNotContain("parent_id", "application_alias");
        assertThat(columnNames(mapper.toTable(Menu.class)))
                .contains("id", "tenant_id", "scheme_id", "parent_id", "module_alias",
                        "open_mode", "route", "external_url", "page_mode", "default_ui_config_id", "default_query_template_id",
                        "entry_params_json", "title", "enabled", "sort_order")
                .doesNotContain("application_alias", "node_type");
    }

    @Test
    void shouldMapDictionaryModelsAsPlatformTables() {
        assertThat(columnNames(mapper.toTable(DictionaryCategory.class)))
                .contains("id", "tenant_id", "application_alias", "alias", "category_kind",
                        "parent_id", "title", "enabled", "sort_order");
        assertThat(columnNames(mapper.toTable(DictionaryItem.class)))
                .contains("id", "tenant_id", "category_id", "category_alias", "code",
                        "parent_id", "title", "enabled", "sort_order")
                .doesNotContain("application_alias");
        assertThat(uniqueIndexes(mapper.toTable(DictionaryCategory.class)))
                .contains(List.of("tenant_id", "application_alias", "alias"));
        assertThat(uniqueIndexes(mapper.toTable(DictionaryItem.class)))
                .contains(List.of("tenant_id", "category_id", "code"),
                        List.of("tenant_id", "category_id", "title"));
    }

    @Test
    void shouldMapCurrencyModelsAsPlatformTables() {
        assertThat(columnNames(mapper.toTable(Currency.class)))
                .contains("id", "tenant_id", "code", "numeric_code", "symbol",
                        "decimal_scale", "rounding_mode", "title", "enabled", "sort_order");
        assertThat(uniqueIndexes(mapper.toTable(Currency.class)))
                .contains(List.of("tenant_id", "code"));
        assertThat(columnNames(mapper.toTable(ExchangeRateType.class)))
                .contains("id", "tenant_id", "code", "system_managed", "title", "enabled", "sort_order");
        assertThat(uniqueIndexes(mapper.toTable(ExchangeRateType.class)))
                .contains(List.of("tenant_id", "code"));
        assertThat(columnNames(mapper.toTable(ExchangeRate.class)))
                .contains("id", "tenant_id", "from_currency_code", "to_currency_code", "rate_type_code",
                        "effective_date", "rate", "source", "title", "enabled", "sort_order");
        assertThat(uniqueIndexes(mapper.toTable(ExchangeRate.class)))
                .contains(List.of("tenant_id", "from_currency_code", "to_currency_code",
                        "rate_type_code", "effective_date"));
        assertThat(columnNames(mapper.toTable(TenantCurrencySetting.class)))
                .contains("id", "tenant_id", "base_currency_code", "title");
        assertThat(uniqueIndexes(mapper.toTable(TenantCurrencySetting.class)))
                .contains(List.of("tenant_id"));
    }

    @Test
    void shouldMapRuntimeAuditRecordAsPlatformTable() {
        TableWrapper table = mapper.toTable(RuntimeAuditRecord.class);

        assertThat(table.getName()).isEqualTo("platform_runtime_audit_record");
        assertThat(columnNames(table))
                .contains("id", "tenant_id", "event_id", "trace_id", "event_type", "module_alias",
                        "entity_alias", "record_id", "action_code", "executor_type", "action_level",
                        "result_type", "result_message", "refresh_requested", "redirect_to", "result_text",
                        "failure_stage", "error_message", "error_type", "system_context", "system_reason",
                        "operator_id", "operator_type", "authorization_decision", "authorization_permission_code",
                        "authorization_permission_action_code", "acting_delegation_id",
                        "acting_principal_user_id", "acting_principal_employee_id",
                        "acting_principal_organization_id", "acting_principal_department_id",
                        "acting_principal_employee_position_id", "mutation_source",
                        "payload_text", "occurred_at");
        assertThat(uniqueIndexes(table)).contains(List.of("tenant_id", "event_id"));
        assertThat(indexes(table)).contains(
                List.of("trace_id"),
                List.of("tenant_id", "action_code", "occurred_at"),
                List.of("tenant_id", "result_type", "occurred_at"),
                List.of("tenant_id", "acting_delegation_id", "occurred_at")
        );
        assertThat(columnType(table, "payload_text")).isEqualTo(ColumnType.TEXT);
        assertThat(columnType(table, "result_message")).isEqualTo(ColumnType.TEXT);
        assertThat(columnType(table, "redirect_to")).isEqualTo(ColumnType.TEXT);
        assertThat(columnType(table, "result_text")).isEqualTo(ColumnType.TEXT);
        assertThat(columnType(table, "error_message")).isEqualTo(ColumnType.TEXT);
    }

    @Test
    void shouldMapCodeLifecycleModelsAsPlatformTables() {
        assertThat(columnNames(mapper.toTable(CodeRule.class)))
                .contains("id", "module_alias", "entity_alias", "module_metadata_field_id",
                        "metadata_field_id", "field_name",
                        "field_role", "mode", "org_scope_type", "org_scope_id", "global_default",
                        "effective_from", "effective_to", "linked_update", "allow_recycle")
                .doesNotContain("field_code");
        assertThat(columnNames(mapper.toTable(CodeRecycleEntry.class)))
                .contains("id", "rule_id", "basis_key", "period_key", "recycled_value",
                        "source_record_id", "status");
        assertThat(columnNames(mapper.toTable(CodeLedgerEntry.class)))
                .contains("id", "rule_id", "module_alias", "entity_alias", "field_name", "code_value",
                        "basis_key", "period_key", "source_record_id", "status", "last_action");
        assertThat(columnDefault(mapper.toTable(CodeRecycleEntry.class), "basis_key")).isEqualTo("'__DEFAULT__'");
        assertThat(columnDefault(mapper.toTable(CodeRecycleEntry.class), "period_key")).isEqualTo("'__DEFAULT__'");
        assertThat(columnDefault(mapper.toTable(CodeRecycleEntry.class), "status")).isEqualTo("'AVAILABLE'");
        assertThat(columnDefault(mapper.toTable(CodeLedgerEntry.class), "basis_key")).isEqualTo("'__DEFAULT__'");
        assertThat(columnDefault(mapper.toTable(CodeLedgerEntry.class), "period_key")).isEqualTo("'__DEFAULT__'");
        assertThat(columnDefault(mapper.toTable(CodeLedgerEntry.class), "status")).isEqualTo("'ACTIVE'");
        assertThat(columnDefault(mapper.toTable(CodeLedgerEntry.class), "last_action")).isEqualTo("'ASSIGNED'");
        assertThat(uniqueIndexes(mapper.toTable(CodeSequenceState.class)))
                .contains(List.of("tenant_id", "rule_id", "basis_key", "period_key"));
        assertThat(uniqueIndexes(mapper.toTable(CodeLedgerEntry.class)))
                .contains(List.of("tenant_id", "rule_id", "code_value"));
        assertThat(uniqueIndexes(mapper.toTable(CodeRecycleEntry.class)))
                .contains(List.of("tenant_id", "rule_id", "basis_key", "period_key", "recycled_value"));
    }

    @Test
    void shouldMapGenerationFieldCoordinatesAsPlatformTables() {
        assertThat(columnNames(mapper.toTable(RecordGenerationFieldMapping.class)))
                .contains("id", "object_mapping_id", "source_field", "source_module_metadata_field_id",
                        "target_field", "target_module_metadata_field_id", "mapping_type",
                        "constant_value", "formula_expr", "default_value", "sort_order");
        assertThat(columnNames(mapper.toTable(RecordGenerationSplitPolicy.class)))
                .contains("id", "object_mapping_id", "quantity_field", "quantity_module_metadata_field_id",
                        "quantity_step", "sort_order");
        assertThat(columnNames(mapper.toTable(RecordGenerationSplitGroupField.class)))
                .contains("id", "split_policy_id", "field_name", "module_metadata_field_id",
                        "title", "sort_order");
    }

    @Test
    void shouldMapWorkflowModelsAsPlatformTables() {
        assertThat(columnNames(mapper.toTable(WorkflowDefinition.class)))
                .contains("id", "tenant_id", "application_alias", "module_alias", "alias",
                        "title", "approval_enabled", "action_code", "definition_status", "current_version_no",
                        "enabled", "sort_order")
                .doesNotContain("entity_alias");
        assertThat(uniqueIndexes(mapper.toTable(WorkflowDefinition.class)))
                .contains(List.of("tenant_id", "module_alias", "alias"));
        assertThat(columnNames(mapper.toTable(WorkflowVersion.class)))
                .contains("id", "definition_id", "version_no", "publish_status", "snapshot_text",
                        "semantic_json", "layout_json", "published_by", "published_at");
        assertThat(columnNames(mapper.toTable(WorkflowNodeDefinition.class)))
                .contains("id", "workflow_version_id", "node_key", "node_type", "approval_mode",
                        "milestone_type", "converge_mode", "converge_ratio", "task_definition_id",
                        "route_mode", "selector_node_key", "require_manual_selection_reason",
                        "allow_reject", "require_reject_reason", "allow_reject_return_to_me",
                        "allow_rollback", "require_rollback_reason", "allow_terminate",
                        "require_terminate_reason", "allow_add_sign",
                        "warning_duration_minutes", "overtime_duration_minutes", "participant_policy_text",
                        "node_config_text", "sort_order");
        assertThat(columnNames(mapper.toTable(WorkflowLinkDefinition.class)))
                .contains("id", "workflow_version_id", "route_key", "source_node_key", "target_node_key",
                        "condition_expression", "default_route", "route_config_text", "sort_order");
        assertThat(columnNames(mapper.toTable(WorkflowTaskDefinition.class)))
                .contains("id", "tenant_id", "module_alias", "alias", "title",
                        "manual_confirm", "task_config_text", "enabled", "sort_order")
                .doesNotContain("entity_alias");
        assertThat(columnNames(mapper.toTable(WorkflowTaskGuide.class)))
                .contains("id", "task_definition_id", "guide_key", "guide_kind", "target_module_alias",
                        "target_action_code", "guide_config_text", "title", "enabled", "sort_order");
        assertThat(columnNames(mapper.toTable(WorkflowTaskCheck.class)))
                .contains("id", "task_definition_id", "check_key", "check_kind", "expression",
                        "failure_message", "check_config_text", "title", "enabled", "sort_order");
        assertThat(columnNames(mapper.toTable(WorkflowInstance.class)))
                .contains("id", "tenant_id", "definition_id", "workflow_version_id", "version_no",
                        "module_alias", "record_id", "approval_enabled",
                        "auth_org_id",
                        "approval_status", "instance_status", "approval_completed_at", "started_by",
                        "started_at", "completed_at", "terminated_at", "current_node_keys",
                        "reject_resubmit_mode", "reject_return_node_key", "reject_return_owner_id",
                        "previous_instance_id",
                        "last_action_code", "last_action_reason", "last_operator_id", "last_operated_at",
                        "snapshot_text", "semantic_json", "layout_json")
                .doesNotContain("entity_alias");
        assertThat(columnNames(mapper.toTable(WorkflowNodeInstance.class)))
                .contains("id", "instance_id", "node_key", "node_title", "node_run_id", "node_type", "node_status", "approval_mode",
                        "milestone_type", "converge_mode", "converge_ratio", "route_id",
                        "route_mode", "selector_node_key", "require_manual_selection_reason",
                        "enter_route_id", "branch_run_id", "converge_run_id",
                        "required_route_count", "arrived_route_count", "completed_route_count",
                        "required_task_count", "completed_task_count", "approved_task_count",
                        "rejected_task_count", "rollback_target_node_key", "task_definition_id",
                        "participant_policy_text",
                        "allow_reject", "require_reject_reason", "allow_reject_return_to_me",
                        "allow_rollback", "require_rollback_reason", "allow_terminate",
                        "require_terminate_reason", "allow_add_sign",
                        "added_by_add_sign", "add_sign_source_node_key", "add_sign_operator_id", "add_sign_at",
                        "warning_duration_minutes", "overtime_duration_minutes",
                        "overtime_status", "warned_at", "overdue_at", "activated_at", "completed_at",
                        "node_snapshot_text");
        assertThat(columnNames(mapper.toTable(WorkflowRouteInstance.class)))
                .contains("id", "instance_id", "route_key", "route_run_id", "source_node_key", "target_node_key",
                        "branch_node_key", "branch_run_id", "converge_node_key", "converge_run_id",
                        "parent_route_id", "route_depth",
                        "route_status", "route_reason", "condition_matched", "default_route",
                        "selected_by", "selected_at", "selected_reason", "arrived_at", "closed_by_route_id", "closed_reason",
                        "invalidated_by_action_id", "invalidated_at",
                        "added_by_add_sign", "add_sign_source_node_key", "add_sign_operator_id", "add_sign_at");
        assertThat(columnNames(mapper.toTable(WorkflowTask.class)))
                .contains("id", "tenant_id", "instance_id", "node_instance_id", "task_kind",
                        "task_status", "parent_task_id", "origin_task_id", "assignment_kind",
                        "add_sign_mode", "owner_id", "original_assignee_id", "assignee_id", "actual_processor_id",
                        "delegated_from_user_id", "delegated_to_user_id", "principal_can_process",
                        "transferred_from_user_id", "decision",
                        "transferred_by", "transferred_at", "added_by", "added_at",
                        "check_status", "check_result_text", "result_message", "assignment_policy_text",
                        "assignment_snapshot_text", "delegation_policy_id", "due_at", "completed_at");
        assertThat(columnNames(mapper.toTable(WorkflowDelegation.class)))
                .contains("id", "tenant_id", "title", "principal_user_id", "delegate_user_id", "enabled",
                        "principal_can_process", "module_scope_type", "module_aliases",
                        "org_scope_type", "org_ids", "memo", "sort_order");
        assertThat(columnNames(mapper.toTable(WorkflowTaskCheckResult.class)))
                .contains("id", "task_id", "check_key", "check_run_id", "check_kind", "check_status", "passed",
                        "checked_at", "failure_message", "result_payload_text");
        assertThat(uniqueIndexes(mapper.toTable(WorkflowTaskCheckResult.class)))
                .contains(List.of("tenant_id", "task_id", "check_key", "check_run_id"));
        assertThat(columnNames(mapper.toTable(WorkflowEvent.class)))
                .contains("id", "tenant_id", "instance_id", "node_instance_id", "task_id", "event_type",
                        "action_code", "operator_id", "message", "payload_text", "occurred_at");
        assertThat(columnNames(mapper.toTable(WorkflowHistoryInstance.class)))
                .contains("snapshot_text", "semantic_json", "layout_json");
        assertThat(columnType(mapper.toTable(WorkflowInstance.class), "snapshot_text")).isEqualTo(ColumnType.TEXT);
        assertThat(columnType(mapper.toTable(WorkflowVersion.class), "semantic_json")).isEqualTo(ColumnType.TEXT);
        assertThat(columnType(mapper.toTable(WorkflowInstance.class), "layout_json")).isEqualTo(ColumnType.TEXT);
        assertThat(columnType(mapper.toTable(WorkflowHistoryInstance.class), "semantic_json")).isEqualTo(ColumnType.TEXT);
        assertThat(columnType(mapper.toTable(WorkflowNodeDefinition.class), "participant_policy_text"))
                .isEqualTo(ColumnType.TEXT);
        assertThat(columnType(mapper.toTable(WorkflowNodeInstance.class), "participant_policy_text"))
                .isEqualTo(ColumnType.TEXT);
        assertThat(columnType(mapper.toTable(WorkflowRouteInstance.class), "selected_reason"))
                .isEqualTo(ColumnType.TEXT);
        assertThat(columnType(mapper.toTable(WorkflowTask.class), "result_message")).isEqualTo(ColumnType.TEXT);
        assertThat(indexes(mapper.toTable(WorkflowRouteInstance.class)))
                .contains(List.of("instance_id", "route_key"), List.of("instance_id", "route_status"));
    }

    @Test
    void shouldMapStablePlatformDefaults() {
        assertThat(mapper.toTable(Menu.class).getPrimaryKey().getLength()).isEqualTo(128);
        assertThat(columnDefault(mapper.toTable(PlatformModule.class), "module_kind")).isEqualTo("'static'");
        assertThat(columnDefault(mapper.toTable(PlatformModule.class), "system_managed")).isEqualTo("FALSE");
        assertThat(columnDefault(mapper.toTable(PlatformModuleAction.class), "access_mode")).isEqualTo("'AUTH_REQUIRED'");
        assertThat(columnDefault(mapper.toTable(PlatformModuleAction.class), "action_auth")).isEqualTo("TRUE");
        assertThat(columnDefault(mapper.toTable(PlatformModuleAction.class), "data_auth")).isEqualTo("FALSE");
        assertThat(columnDefault(mapper.toTable(ModuleMetadataRelation.class), "relation_role")).isEqualTo("'main'");
        assertThat(columnDefault(mapper.toTable(MetadataFieldReferenceConfig.class), "cardinality")).isEqualTo("'ONE'");
        assertThat(columnDefault(mapper.toTable(MetadataFieldReferenceConfig.class), "target_unavailable_policy"))
                .isEqualTo("'PRESERVE_HISTORY'");
        assertThat(columnDefault(mapper.toTable(MetadataFieldConfig.class), "queryable")).isNull();
        assertThat(columnDefault(mapper.toTable(MetadataFieldProtectionConfig.class), "enabled")).isEqualTo("TRUE");
        assertThat(columnDefault(mapper.toTable(MetadataField.class), "required")).isEqualTo("FALSE");
        assertThat(columnDefault(mapper.toTable(MetadataViewField.class), "visible")).isEqualTo("TRUE");
        assertThat(columnDefault(mapper.toTable(RuntimeAuditRecord.class), "system_context")).isEqualTo("FALSE");
        assertThat(columnDefault(mapper.toTable(WorkflowDefinition.class), "approval_enabled")).isEqualTo("FALSE");
        assertThat(columnDefault(mapper.toTable(WorkflowInstance.class), "approval_status")).isEqualTo("'none'");
        assertThat(columnDefault(mapper.toTable(WorkflowInstance.class), "instance_status")).isEqualTo("'running'");
        assertThat(columnDefault(mapper.toTable(WorkflowRouteInstance.class), "route_status")).isEqualTo("'candidate'");
        assertThat(columnDefault(mapper.toTable(WorkflowTask.class), "assignment_kind")).isEqualTo("'normal'");
        assertThat(columnDefault(mapper.toTable(WorkflowTask.class), "check_status")).isEqualTo("'not_checked'");
        assertThat(columnDefault(mapper.toTable(WorkflowTaskDefinition.class), "manual_confirm")).isEqualTo("TRUE");
    }

    private Set<String> columnNames(TableWrapper table) {
        Set<String> names = new LinkedHashSet<>();
        if (table.getPrimaryKey() != null) {
            names.add(table.getPrimaryKey().getName());
        }
        table.getColumns().forEach(column -> names.add(column.getName()));
        return names;
    }

    private List<List<String>> uniqueIndexes(TableWrapper table) {
        return table.getIndexes().stream()
                .filter(index -> index.isUnique())
                .map(index -> List.copyOf(index.getColumns()))
                .toList();
    }

    private List<List<String>> indexes(TableWrapper table) {
        return table.getIndexes().stream()
                .map(index -> List.copyOf(index.getColumns()))
                .toList();
    }

    private ColumnType columnType(TableWrapper table, String columnName) {
        return table.getColumns().stream()
                .filter(column -> columnName.equals(column.getName()))
                .findFirst()
                .orElseThrow()
                .getType();
    }

    private String columnDefault(TableWrapper table, String columnName) {
        return table.getColumns().stream()
                .filter(column -> columnName.equals(column.getName()))
                .findFirst()
                .orElseThrow()
                .getDefaultValue();
    }
}
