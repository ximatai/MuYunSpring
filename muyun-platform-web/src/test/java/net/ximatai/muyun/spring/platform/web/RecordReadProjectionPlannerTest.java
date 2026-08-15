package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.FieldReadAbility;
import net.ximatai.muyun.spring.ability.FieldReadPolicy;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.ability.security.FieldProtectionPlan;
import net.ximatai.muyun.spring.ability.security.ProtectedFieldAccessor;
import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.security.FieldSignatureMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecordReadProjectionPlannerTest {
    @Test
    void shouldPlanDefaultListProjectionFromResolvedDescriptor() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(staticDefinition(
                TestModulePages.listDetail("iam.employee", list -> list
                                .field("employeeNo")
                                .field("title")
                                .field("mobile", field -> field.visible(UiRule.constant(false)))
                                .field("enabled"))
        ));

        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel()
        );

        assertThat(projection.moduleAlias()).isEqualTo("iam.employee");
        assertThat(projection.viewCode()).isEqualTo("default_list");
        assertThat(projection.outputFields()).extracting(ViewFieldRef::fieldName)
                .containsExactly("employeeNo", "title", "enabled");
        assertThat(projection.internalReadFields()).containsExactly("id", "tenantId", "version");
        assertThat(projection.readFields()).containsExactly("id", "tenantId", "version",
                "employeeNo", "title", "enabled");
        assertThat(projection.postReadTransforms()).isEmpty();
    }

    @Test
    void shouldRejectProjectionFieldOutsideReadModel() {
        ResolvedModuleUiDescriptor descriptor = ModuleUiDescriptorCompiler.compile(
                TestModulePages.listDetail("iam.employee", list -> list.field("ghostField")));
        ResolvedModuleReadModel readModel = new ResolvedModuleReadModel(
                "iam.employee",
                "employee",
                List.of(new ResolvedModuleReadField("employee", null, "employeeNo", false))
        );

        assertThatThrownBy(() -> RecordReadProjectionPlanner.defaultList(descriptor, readModel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iam.employee.default_list.ghostField");
    }

    @Test
    void shouldKeepPlatformFieldsAndOnlyProjectDeclaredBusinessFields() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(staticDefinition(
                TestModulePages.listDetail("iam.employee", list -> list
                                .field("employeeNo")
                                .field("title")
                                .field("enabled"))
        ));
        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel()
        );
        ProjectionEmployee record = new ProjectionEmployee();
        record.setId("emp-1");
        record.setVersion(7);
        record.setDeletedAt(Instant.EPOCH);
        record.setTenantId("tenant-a");
        record.setDeleted(Boolean.FALSE);
        record.setCreatedAt(Instant.EPOCH);
        record.setEmployeeNo("E001");
        record.setTitle("张三");
        record.setMobile("13800000000");
        record.setEnabled(Boolean.TRUE);

        Map<String, Object> output = RecordReadProjectionProjector.project(record, projection);

        assertThat(output).containsEntry("id", "emp-1");
        assertThat(output).containsEntry("version", 7);
        assertThat(output).containsEntry("deletedAt", Instant.EPOCH);
        assertThat(output).containsEntry("employeeNo", "E001");
        assertThat(output).containsEntry("title", "张三");
        assertThat(output).containsEntry("enabled", Boolean.TRUE);
        assertThat(output).doesNotContainKeys("tenantId", "deleted", "createdAt", "mobile");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRecordFieldProtectionPostReadTransformsForProjectedFields() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(staticDefinition(
                TestModulePages.listDetail("iam.employee", list -> list
                                .field("employeeNo")
                                .field("mobile"))
        ));
        FieldProtectionAbility protectedService = mock(FieldProtectionAbility.class);
        ProtectedFieldAccessor mobile = protectedField("mobile", FieldMaskingPolicy.PHONE);
        ProtectedFieldAccessor secret = protectedField("secret", FieldMaskingPolicy.MIDDLE);
        when(protectedService.fieldProtectionPlan()).thenReturn(new FieldProtectionPlan(List.of(mobile, secret)));

        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel(),
                protectedService
        );

        assertThat(projection.outputFields()).extracting(ViewFieldRef::fieldName)
                .containsExactly("employeeNo", "mobile");
        assertThat(projection.postReadTransforms()).containsExactly("fieldProtection:mobile");
    }

    @Test
    void shouldParseRecordReadPostTransformContract() {
        RecordReadPostTransform transform = RecordReadPostTransform.fieldProtection("mobile");

        assertThat(transform.serialize()).isEqualTo("fieldProtection:mobile");
        assertThat(RecordReadPostTransform.parse(" fieldProtection:mobile "))
                .hasValue(transform);
        assertThat(RecordReadPostTransform.optionLoad("passwordStatusTitle").serialize())
                .isEqualTo("optionLoad:passwordStatusTitle");
        assertThat(RecordReadPostTransform.parse("optionLoad:passwordStatusTitle"))
                .hasValue(RecordReadPostTransform.optionLoad("passwordStatusTitle"));
        assertThat(RecordReadPostTransform.parse("fieldProtection"))
                .isEmpty();
        assertThat(RecordReadPostTransform.parse("fieldProtection:mobile:extra"))
                .isEmpty();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void shouldRecordOptionTitlePostReadTransformsForProjectedFields() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(StaticModuleDefinition.builder("iam", "iam.user", "用户管理")
                                                                                                 .parentModuleAlias(null)
                                                                                                 .entry(ModuleEntryType.ROUTE, "/iam/users", null)
                                                                                                 .capabilities(Set.of(EntityCapability.CRUD))
                                                                                                 .actions(List.of())
                                                                                                 .entities(List.of(new EntityDefinition(
                        "user",
                        "iam_user",
                        "User",
                        List.of(
                                FieldDefinition.string("username", "账号"),
                                FieldDefinition.string("passwordStatus", "密码状态")
                        )
                )))
                                                                                                 .uiDefinition(TestModulePages.listDetail("iam.user", list -> list
                                .field("username")
                                .field("passwordStatus"))
                        )
                                                                                                 .references(List.of())
                                                                                                 .readProjections(List.of())
                                                                                                 .modelClass(UserAccount.class)
                                                                                                 .projectionJoins(List.of())
                                                                                                 .build());
        CrudAbility recordService = mock(CrudAbility.class);
        when(recordService.modelClass()).thenReturn(UserAccount.class);

        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel(),
                recordService
        );

        assertThat(projection.postReadTransforms()).containsExactly("optionLoad:passwordStatusTitle");
    }

    @Test
    void shouldApplyFieldReadPolicyBeforeOutputProjection() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(staticDefinition(
                TestModulePages.listDetail("iam.employee", list -> list
                                .field("employeeNo")
                                .field("mobile"))
        ));
        FieldReadAbility readableService = new FieldReadAbility() {
            @Override
            public FieldReadPolicy fieldReadPolicy(ActionExecutionContext actionContext) {
                return FieldReadPolicy.readableFields(List.of("employeeNo"));
            }
        };

        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel(),
                readableService
        );

        assertThat(projection.fieldReadPolicies()).containsExactly("fieldReadPolicy:explicit");
        assertThat(projection.outputFields()).extracting(ViewFieldRef::fieldName)
                .containsExactly("employeeNo");
        assertThat(projection.readFields()).containsExactly("id", "tenantId", "version", "employeeNo");
    }

    @Test
    void shouldAttachQueryPermissionContextToReadProjection() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(staticDefinition(
                TestModulePages.listDetail("iam.employee", list -> list.field("employeeNo"))
        ));
        ActionExecutionContext actionContext = ActionExecutionContext.ofPlatformAction(
                "iam.employee",
                PlatformAction.QUERY,
                Set.of(),
                java.util.Optional.empty()
        );

        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel(),
                null,
                actionContext
        );

        assertThat(projection.actionCode()).isEqualTo("query");
        assertThat(projection.permissionCode()).isEqualTo("iam.employee:view");
        assertThat(projection.permissionActionCode()).isEqualTo("view");
    }

    @Test
    void shouldAttachRecycleBinQueryContextToTheSameListProjection() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(staticDefinition(
                TestModulePages.listDetail("iam.employee", list -> list.field("employeeNo"))
        ));
        ActionExecutionContext actionContext = ActionExecutionContext.ofPlatformAction(
                "iam.employee",
                PlatformAction.RECYCLE_BIN_QUERY,
                Set.of(),
                java.util.Optional.empty()
        );

        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(), compilation.readModel(), null, actionContext);

        assertThat(projection.actionCode()).isEqualTo("recycleBinQuery");
        assertThat(projection.outputFields()).extracting(ViewFieldRef::fieldName)
                .containsExactly("employeeNo");
    }

    @Test
    void shouldRejectProjectionWhenActionContextIsNotQuery() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(staticDefinition(
                TestModulePages.listDetail("iam.employee", list -> list.field("employeeNo"))
        ));
        ActionExecutionContext actionContext = ActionExecutionContext.ofPlatformAction(
                "iam.employee",
                PlatformAction.UPDATE,
                Set.of("emp-1"),
                java.util.Optional.empty()
        );

        assertThatThrownBy(() -> RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel(),
                null,
                actionContext
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires list query action context");
    }

    @Test
    void shouldRejectProjectionWhenActionContextModuleDiffers() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(staticDefinition(
                TestModulePages.listDetail("iam.employee", list -> list.field("employeeNo"))
        ));
        ActionExecutionContext actionContext = ActionExecutionContext.ofPlatformAction(
                "iam.department",
                PlatformAction.QUERY,
                Set.of(),
                java.util.Optional.empty()
        );

        assertThatThrownBy(() -> RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel(),
                null,
                actionContext
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action module alias mismatch");
    }

    @Test
    void shouldKeepNullValuesWhenProjectingRecordOutput() {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(staticDefinition(
                TestModulePages.listDetail("iam.employee", list -> list.field("employeeNo"))
        ));
        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel()
        );
        ProjectionEmployee record = new ProjectionEmployee();
        record.setId("emp-1");
        record.setVersion(7);

        Map<String, Object> output = RecordReadProjectionProjector.project(record, projection);

        assertThat(output).containsEntry("id", "emp-1");
        assertThat(output).containsEntry("version", 7);
        assertThat(output).containsEntry("deletedAt", null);
        assertThat(output).containsEntry("employeeNo", null);
    }

    private StaticModuleDefinition staticDefinition(ModuleUiDefinition uiDefinition) {
        return StaticModuleDefinition.builder("iam", "iam.employee", "职员管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/employees", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "employee",
                        "iam_employee",
                        "Employee",
                        List.of(
                                FieldDefinition.string("employeeNo", "职员编号"),
                                FieldDefinition.string("mobile", "手机号")
                        )
                )))
                       .uiDefinition(uiDefinition)
                       .build();
    }

    @SuppressWarnings("unchecked")
    private ProtectedFieldAccessor<ProjectionEmployee> protectedField(String fieldName,
                                                                      FieldMaskingPolicy maskingPolicy) {
        ProtectedFieldAccessor<ProjectionEmployee> field = mock(ProtectedFieldAccessor.class);
        when(field.fieldName()).thenReturn(fieldName);
        when(field.protection()).thenReturn(new FieldProtectionDefinition(
                FieldEncryptionMode.NONE,
                FieldSignatureMode.NONE,
                maskingPolicy
        ));
        return field;
    }

    public static final class ProjectionEmployee {
        private String id;
        private Integer version;
        private java.time.Instant deletedAt;
        private String tenantId;
        private Boolean deleted;
        private java.time.Instant createdAt;
        private String employeeNo;
        private String title;
        private String mobile;
        private Boolean enabled;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        public java.time.Instant getDeletedAt() {
            return deletedAt;
        }

        public void setDeletedAt(java.time.Instant deletedAt) {
            this.deletedAt = deletedAt;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public Boolean getDeleted() {
            return deleted;
        }

        public void setDeleted(Boolean deleted) {
            this.deleted = deleted;
        }

        public java.time.Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(java.time.Instant createdAt) {
            this.createdAt = createdAt;
        }

        public String getEmployeeNo() {
            return employeeNo;
        }

        public void setEmployeeNo(String employeeNo) {
            this.employeeNo = employeeNo;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getMobile() {
            return mobile;
        }

        public void setMobile(String mobile) {
            this.mobile = mobile;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }
}
