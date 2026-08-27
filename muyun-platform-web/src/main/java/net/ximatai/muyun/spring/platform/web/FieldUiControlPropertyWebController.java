package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.web.NestedSortableCrudWebSupport;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticActionContribution(targetModule = FieldUiControlService.MODULE_ALIAS,
        resource = "field_ui_control_property", resourceTitle = "控件属性")
@RequestMapping("/platform.field_ui_control/{fieldUiControlAlias}/properties")
public class FieldUiControlPropertyWebController
        extends NestedSortableCrudWebSupport<FieldUiControlProperty, FieldUiControlPropertyService>
        implements StaticModuleUiContributor {

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(FieldUiControlService.MODULE_ALIAS)
                .editorContribution("field_ui_control_property", form -> form.title("控件属性")
                        .field("field_ui_control_property", "attributeAlias", field -> field.label("属性 alias")
                                .width("180px")
                                .required().enabledWhen(UiFormula.booleanExpression("!(PRESENT({id}))")))
                        .field("field_ui_control_property", "title", field -> field.label("属性名称")
                                .width("220px").required())
                        .field("field_ui_control_property", "valueFieldSpecAlias", field -> field.label("值字段规格")
                                .width("180px").recordPicker())
                        .field("field_ui_control_property", "defaultValue", field -> field.label("默认值")
                                .width("240px")))
                .build();
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        criteria.eq("fieldUiControlAlias", fieldUiControlAlias(request));
    }

    @Override
    protected void bindScope(FieldUiControlProperty record, HttpServletRequest request) {
        record.setFieldUiControlAlias(fieldUiControlAlias(request));
    }

    @Override
    protected boolean inScope(FieldUiControlProperty record, HttpServletRequest request) {
        return Objects.equals(record.getFieldUiControlAlias(), fieldUiControlAlias(request));
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "field UI control property does not belong to field UI control: "
                + fieldUiControlAlias(request) + "." + id;
    }

    private String fieldUiControlAlias(HttpServletRequest request) {
        return PlatformNameRules.requireIdentifier(pathVariable(request, "fieldUiControlAlias"), "fieldUiControlAlias");
    }
}
