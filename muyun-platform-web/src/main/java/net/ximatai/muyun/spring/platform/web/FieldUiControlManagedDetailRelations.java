package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPropertyService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlValueShape;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationParentConstraint;
import org.springframework.stereotype.Component;

/** Domain-owned relation adapters; the generic gateway never knows field-control URLs or fields. */
final class FieldUiControlManagedDetailRelations {
    private FieldUiControlManagedDetailRelations() { }

    @Component
    static final class Properties implements StaticManagedDetailRelationHandler<FieldUiControl, FieldUiControlProperty> {
        private final FieldUiControlPropertyService service;
        Properties(FieldUiControlPropertyService service) { this.service = service; }
        public String parentModuleAlias() { return FieldUiControlService.MODULE_ALIAS; }
        public String relationCode() { return "properties"; }
        public String parentBinding() { return "fieldUiControlAlias"; }
        public String childEntityAlias() { return "field_ui_control_property"; }
        public Class<FieldUiControlProperty> childModelClass() { return FieldUiControlProperty.class; }
        public CrudAbility<FieldUiControlProperty> childService() { return service; }
        public Criteria criteriaFor(FieldUiControl parent) { return Criteria.of().eq("fieldUiControlAlias", parent.getAlias()); }
        public void bindParent(FieldUiControlProperty child, FieldUiControl parent) { child.setFieldUiControlAlias(parent.getAlias()); }
        public boolean belongsTo(FieldUiControlProperty child, FieldUiControl parent) { return parent.getAlias().equals(child.getFieldUiControlAlias()); }
    }

    @Component
    static final class Bindings implements StaticManagedDetailRelationHandler<FieldUiControl, FieldUiControlBinding> {
        private final FieldUiControlBindingService service;
        Bindings(FieldUiControlBindingService service) { this.service = service; }
        public String parentModuleAlias() { return FieldUiControlService.MODULE_ALIAS; }
        public String relationCode() { return "bindings"; }
        public String parentBinding() { return "fieldUiControlAlias"; }
        public String childEntityAlias() { return "field_ui_control_binding"; }
        public Class<FieldUiControlBinding> childModelClass() { return FieldUiControlBinding.class; }
        public CrudAbility<FieldUiControlBinding> childService() { return service; }
        public Criteria criteriaFor(FieldUiControl parent) { return Criteria.of().eq("fieldUiControlAlias", parent.getAlias()); }
        public void bindParent(FieldUiControlBinding child, FieldUiControl parent) { child.setFieldUiControlAlias(parent.getAlias()); }
        public boolean belongsTo(FieldUiControlBinding child, FieldUiControl parent) { return parent.getAlias().equals(child.getFieldUiControlAlias()); }
        public ResolvedDetailRelationParentConstraint parentConstraint() {
            return new ResolvedDetailRelationParentConstraint("valueShape", "COMPOSITE");
        }
        public boolean availableFor(FieldUiControl parent) {
            return parent.getValueShape() == FieldUiControlValueShape.COMPOSITE;
        }
    }
}
