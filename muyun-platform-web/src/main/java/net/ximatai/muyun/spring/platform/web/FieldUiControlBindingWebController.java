package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.web.NestedSortableCrudWebSupport;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticActionContribution(targetModule = FieldUiControlService.MODULE_ALIAS,
        resource = "field_ui_control_binding", resourceTitle = "字段绑定")
@RequestMapping("/platform.field_ui_control/{fieldUiControlAlias}/bindings")
public class FieldUiControlBindingWebController
        extends NestedSortableCrudWebSupport<FieldUiControlBinding, FieldUiControlBindingService>
        implements StaticModuleUiContributor {

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(FieldUiControlService.MODULE_ALIAS)
                .editorContribution("field_ui_control_binding", form -> form.title("字段绑定")
                        .field("field_ui_control_binding", "valueKey", field -> field.label("值键")
                                .required().enabledWhen(UiFormula.booleanExpression("!(PRESENT({id}))")))
                        .field("field_ui_control_binding", "valueFieldSpecAlias", field -> field.label("值字段规格").required())
                        .field("field_ui_control_binding", "title", field -> field.label("标题").required()))
                .build();
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        criteria.eq("fieldUiControlAlias", fieldUiControlAlias(request));
    }

    @Override
    protected void bindScope(FieldUiControlBinding record, HttpServletRequest request) {
        record.setFieldUiControlAlias(fieldUiControlAlias(request));
    }

    @Override
    protected boolean inScope(FieldUiControlBinding record, HttpServletRequest request) {
        return Objects.equals(record.getFieldUiControlAlias(), fieldUiControlAlias(request));
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "field UI control binding does not belong to field UI control: "
                + fieldUiControlAlias(request) + "." + id;
    }

    private String fieldUiControlAlias(HttpServletRequest request) {
        return PlatformNameRules.requireIdentifier(pathVariable(request, "fieldUiControlAlias"), "fieldUiControlAlias");
    }
}
