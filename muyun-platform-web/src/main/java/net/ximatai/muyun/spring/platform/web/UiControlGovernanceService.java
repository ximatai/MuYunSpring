package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ui.PlatformUiControlRulesService;
import net.ximatai.muyun.spring.platform.ui.UiControlRule;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Coordinates module rule changes atomically; UI predicates remain outside the save executor. */
@Service
public class UiControlGovernanceService {
    private final PlatformUiControlRulesService storage;
    private final PlatformModuleRuntimeContextService runtime;
    private final BusinessRuleGovernanceService businessRules;
    public UiControlGovernanceService(PlatformUiControlRulesService storage,
            PlatformModuleRuntimeContextService runtime, BusinessRuleGovernanceService businessRules) {
        this.storage = storage; this.runtime = runtime; this.businessRules = businessRules;
    }
    public record Snapshot(String baselineFingerprint, List<UiControlRule> rules, List<UiControlFormProjection.Form> forms) {}
    public Snapshot snapshot(String alias) {
        var state = storage.snapshot(alias);
        return new Snapshot(state.baselineFingerprint(), state.rules(),
                UiControlFormProjection.forms(runtime.contextWithoutUiControls(alias).uiDescriptor()));
    }
    static void validate(List<UiControlRule> rules, List<UiControlFormProjection.Form> forms) {
        Set<String> codes = new HashSet<>();
        FormulaEngine engine = new FormulaEngine();
        for (UiControlRule rule : rules) {
            if (rule == null || rule.code() == null || !rule.code().matches("[a-z][A-Za-z0-9]{0,63}") || !codes.add(rule.code()))
                throw new PlatformException("界面规则标识无效或重复");
            var form = forms.stream().filter(item -> item.key().equals(rule.formKey())).findFirst()
                    .orElseThrow(() -> new PlatformException("请选择有效的表单：" + rule.code()));
            Set<String> elements = new HashSet<>(form.elements().stream().map(UiControlFormProjection.Element::key).toList());
            if (rule.expression() == null || rule.expression().isBlank()) throw new PlatformException("请填写界面控制公式");
            engine.compileWebUiProgram(rule.expression());
            if (!elements.containsAll(engine.referencedFields(rule.expression())))
                throw new PlatformException("界面公式只能使用所选表单中的参与字段");
            if (rule.targets().isEmpty()) throw new PlatformException("请选择需要控制的界面元素");
            Set<String> selected = new HashSet<>();
            for (var target : rule.targets()) {
                if (!elements.contains(target.elementKey()) || !selected.add(target.elementKey()) || (!target.hide() && !target.readOnly()))
                    throw new PlatformException("请选择有效元素，并勾选隐藏或只读");
            }
        }
    }
    @Transactional
    public BusinessRuleApplyResult apply(String alias, BusinessRuleApplyCommand command) {
        if (command.uiRules() == null) return businessRules.apply(alias, command);
        var descriptor = runtime.contextWithoutUiControls(alias).uiDescriptor();
        validate(command.uiRules(), UiControlFormProjection.forms(descriptor));
        // Validate the combined predicates too, before any data is written.
        UiControlFormProjection.project(descriptor, command.uiRules());
        var coreCodes = new HashSet<>(command.rules().stream().map(BusinessRuleProposal::code).toList());
        if (command.uiRules().stream().anyMatch(rule -> coreCodes.contains(rule.code())))
            throw new PlatformException("规则标识不能重复");
        storage.replace(alias, command.uiBaselineFingerprint(), command.uiRules());
        return businessRules.apply(alias, command);
    }
}
