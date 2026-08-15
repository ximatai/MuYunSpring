package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.form.FormAbility;
import net.ximatai.muyun.spring.ability.form.FormDescriptor;
import net.ximatai.muyun.spring.ability.form.FormField;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinition;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinitionCatalog;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.StaticRecordReadProjectionService;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.option.DictionaryField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CrudWebFormSchemaTest {
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldExposeFormSchemaThroughCrudWebEndpoint() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new DemoRecordController(new DemoRecordService()))
                .build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(get("/demo.record/form/schema"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scopeName").value("demo.record"))
                    .andExpect(jsonPath("$.title").value("Demo Record"))
                    .andExpect(jsonPath("$.fields[0].name").value("title"))
                    .andExpect(jsonPath("$.fields[0].title").value("名称"))
                    .andExpect(jsonPath("$.fields[0].required").value(true));
        }
    }

    @Test
    void shouldPreferStaticModuleUiDefinitionForFormSchemaEndpoint() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new DemoRecordUiController(new DemoRecordService()))
                .build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(get("/demo.record.ui/form/schema"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scopeName").value("demo.record.ui"))
                    .andExpect(jsonPath("$.title").value("UI Demo Record"))
                    .andExpect(jsonPath("$.fields[0].name").value("title"))
                    .andExpect(jsonPath("$.fields[0].title").value("UI 名称"))
                    .andExpect(jsonPath("$.fields[0].required").value(true))
                    .andExpect(jsonPath("$.fields[0].readOnly").value(true))
                    .andExpect(jsonPath("$.fields[1].name").value("status"))
                    .andExpect(jsonPath("$.fields[1].controlType").value("SELECT"))
                    .andExpect(jsonPath("$.fields[1].optionBinding.sourceType").value("dictionary"))
                    .andExpect(jsonPath("$.fields[1].optionBinding.source").value("demo.status"))
                    .andExpect(jsonPath("$.fields[1].optionTitleField").value("statusTitle"))
                    .andExpect(jsonPath("$.fields[2].name").value("enabled"))
                    .andExpect(jsonPath("$.fields[2].valueType").value("BOOLEAN"))
                    .andExpect(jsonPath("$.fields[2].controlType").value("SWITCH"))
                    .andExpect(jsonPath("$.fields[3].name").value("showTitleArea"))
                    .andExpect(jsonPath("$.fields[3].valueType").value("BOOLEAN"))
                    .andExpect(jsonPath("$.fields[3].controlType").value("SWITCH"));
        }
    }

    @Test
    void shouldSelectChildResourceEditorForFormSchemaEndpoint() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new DemoRecordChildUiController(new DemoRecordService()))
                .build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(get("/demo.record.child/form/schema"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scopeName").value("demo.parent"))
                    .andExpect(jsonPath("$.title").value("Child UI Demo Record"))
                    .andExpect(jsonPath("$.fields[0].name").value("title"))
                    .andExpect(jsonPath("$.fields[0].title").value("子资源名称"));
        }
    }

    @Test
    void shouldProjectStaticModuleQueryThroughCrudWebEndpoint() throws Exception {
        DemoRecordUiController controller = new DemoRecordUiController(new DemoRecordService());
        controller.setStaticRecordReadProjectionService(new StaticRecordReadProjectionService(
                new StaticModuleDefinitionCatalog(List.of(demoStaticModuleDefinition()))
        ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .build();

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/demo.record.ui/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records[0].id").value("demo-1"))
                    .andExpect(jsonPath("$.records[0].title").value("Demo One"))
                    .andExpect(jsonPath("$.records[0].status").doesNotExist());
        }
    }

    @RestController
    @RequestMapping("/demo.record")
    private static final class DemoRecordController extends WebSupport<DemoRecordService>
            implements CrudWeb<DemoRecord, DemoRecordService> {
        private DemoRecordController(DemoRecordService service) {
            this.service = service;
        }
    }

    @RestController
    @RequestMapping("/demo.record.ui")
    private static final class DemoRecordUiController extends WebSupport<DemoRecordService>
            implements CrudWeb<DemoRecord, DemoRecordService>, StaticModuleUiContributor {
        private StaticRecordReadProjectionService staticRecordReadProjectionService;

        private DemoRecordUiController(DemoRecordService service) {
            this.service = service;
        }

        private void setStaticRecordReadProjectionService(StaticRecordReadProjectionService staticRecordReadProjectionService) {
            this.staticRecordReadProjectionService = staticRecordReadProjectionService;
        }

        @Override
        public StaticRecordReadProjectionService staticRecordReadProjectionService() {
            return staticRecordReadProjectionService;
        }

        @Override
        public ModuleUiDefinition moduleUiDefinition() {
            return ModuleUiDefinition.builder("demo.record.ui")
                    .page(PageTemplates.listDetailCard(page -> page
                            .list(list -> list.fields(fields -> fields
                                    .field("title", field -> field.label("UI 名称"))))
                            .detail(detail -> detail.editor(form -> form
                                    .title("UI Demo Record")
                                    .field("title", field -> field.label("UI 名称").required().readOnly())
                                    .field("status", field -> field.label("状态"))
                                    .field("enabled", field -> field.label("启用状态").uiType("enabledStatus"))
                                    .field("showTitleArea", field -> field.label("展示标题区"))))))
                    .build();
        }
    }

    @RestController
    @PlatformStaticActionContribution(targetModule = "demo.parent", resource = "demo_record", resourceTitle = "Demo Record")
    @RequestMapping("/demo.record.child")
    private static final class DemoRecordChildUiController extends WebSupport<DemoRecordService>
            implements CrudWeb<DemoRecord, DemoRecordService>, StaticModuleUiContributor {
        private DemoRecordChildUiController(DemoRecordService service) {
            this.service = service;
        }

        @Override
        public ModuleUiDefinition moduleUiDefinition() {
            return ModuleUiDefinition.builder("demo.parent")
                    .editorContribution("demo_record", form -> form
                            .title("Child UI Demo Record")
                            .field("demo_record", "title", field -> field.label("子资源名称").required()))
                    .build();
        }
    }

    private static final class DemoRecordService extends AbstractAbilityService<DemoRecord>
            implements FormAbility<DemoRecord> {
        private DemoRecordService() {
            super("demo.record", DemoRecord.class, dao());
        }

        @Override
        public FormDescriptor formDescriptor() {
            return FormDescriptor.builder("demo.record")
                    .title("Demo Record")
                    .field(FormField.of("title").withTitle("名称").asRequired())
                    .build();
        }

        @Override
        public PageResult<DemoRecord> pageQuery(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
            DemoRecord record = new DemoRecord();
            record.setId("demo-1");
            record.setTitle("Demo One");
            record.setStatus("draft");
            return PageResult.of(List.of(record), 1, pageRequest);
        }
    }

    private static StaticModuleDefinition demoStaticModuleDefinition() {
        return StaticModuleDefinition.builder("demo", "demo.record.ui", "UI Demo Record")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/demo-records", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "demo_record",
                        "demo_record",
                        "Demo Record",
                        List.of(
                                FieldDefinition.string("title", "名称"),
                                FieldDefinition.string("status", "状态")
                        )
                )))
                       .uiDefinition(ModuleUiDefinition.builder("demo.record.ui")
                        .page(PageTemplates.listDetailCard(page -> page
                                .list(list -> list.fields(fields -> fields
                                        .field("title", field -> field.label("UI 名称"))))
                                .detail(detail -> detail.editor(form -> form.field("title")))))
                        .build())
                       .build();
    }

    @Table(name = "demo_record", comment = "Demo Record")
    private static final class DemoRecord extends StandardEntity {
        @Column(name = "title", comment = "名称")
        private String title;

        @DictionaryField(source = "demo.status")
        @Column(name = "status", comment = "状态")
        private String status;

        @Column(name = "show_title_area", type = ColumnType.BOOLEAN, comment = "展示标题区")
        private Boolean showTitleArea;

        @OptionLoad(source = "status")
        private String statusTitle;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Boolean getShowTitleArea() {
            return showTitleArea;
        }

        public void setShowTitleArea(Boolean showTitleArea) {
            this.showTitleArea = showTitleArea;
        }

        public String getStatusTitle() {
            return statusTitle;
        }

        public void setStatusTitle(String statusTitle) {
            this.statusTitle = statusTitle;
        }
    }

    @SuppressWarnings("unchecked")
    private static BaseDao<DemoRecord, String> dao() {
        return mock(BaseDao.class);
    }
}
