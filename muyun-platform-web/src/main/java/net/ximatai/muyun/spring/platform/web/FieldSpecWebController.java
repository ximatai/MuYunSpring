package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = FieldSpecService.MODULE_ALIAS, title = "字段规格")
@PlatformMenu(parent = PlatformMenuGroups.MODELING, title = "字段规格", order = 40)
@StaticModuleOpenApi
@RequestMapping("/platform.field_spec")
public class FieldSpecWebController extends WebSupport<FieldSpecService> implements
        CrudWeb<FieldSpec, FieldSpecService>,
        SystemScope<FieldSpecService>,
        StaticModuleUiContributor {
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(FieldSpecService.MODULE_ALIAS)
                .listView(list -> list
                        .title("字段规格列表")
                        .flatManagementTemplate(template -> template
                                .explorerTitle("字段规格列表")
                                .explorerSearchPlaceholder("搜索字段规格名称或 alias")
                                .emptyDescription("暂无字段规格")
                                .detailEmptyDescription("请选择字段规格，或新建字段规格")
                                .createTitle("新建字段规格")
                                .recordLabel("字段规格")
                                .fallbackTitle("未命名字段规格"))
                        .field("alias", field -> field.label("规格 alias").width("180px"))
                        .field("title", field -> field.label("规格名称").width("220px"))
                        .field("fieldType", field -> field.label("运行时类型").width("150px"))
                        .field("defaultQueryOperator", field -> field.label("默认查询操作符").width("160px"))
                        .field("enabled", field -> field.label("状态").uiType("enabledStatus")
                                .width("90px").align("center")))
                .formView(form -> form
                        .title("字段规格")
                        .field("alias", field -> field.label("规格 alias").required()
                                .enabledWhen(UiFormula.booleanExpression("!(PRESENT({id}))")))
                        .field("title", field -> field.label("规格名称").required())
                        .field("fieldType", field -> field.label("运行时类型").required())
                        .field("defaultLength", field -> field.label("默认长度"))
                        .field("defaultPrecision", field -> field.label("默认精度"))
                        .field("defaultScale", field -> field.label("默认小数位"))
                        .field("defaultQueryOperator", field -> field.label("默认查询操作符"))
                        .field("queryOperators", field -> field.label("允许查询操作符"))
                        .field("defaultUiControlAlias", field -> field.label("默认 UI 控件"))
                        .field("uiControlAliases", field -> field.label("允许 UI 控件"))
                        .field("enabled", field -> field.label("启用状态").uiType("enabledStatus")))
                .build();
    }
}
