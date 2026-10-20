package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class CodeRuntimeFacade {
    private final CodeGenerateService generateService;
    private final CodeRuleService ruleService;
    private final CodeLedgerEntryService ledgerEntryService;

    public CodeRuntimeFacade(CodeGenerateService generateService,
                             CodeRuleService ruleService,
                             CodeLedgerEntryService ledgerEntryService) {
        this.generateService = Objects.requireNonNull(generateService, "generateService must not be null");
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
        this.ledgerEntryService = Objects.requireNonNull(ledgerEntryService, "ledgerEntryService");
    }

    /** Candidate only: no sequence allocation and no record occupation. */
    public CodePreviewResult preview(CodeRuntimeCommand command) {
        return generateService.preview(Objects.requireNonNull(command, "command").toGenerateCommand());
    }

    @Transactional
    public CodeRuntimeResult generateOnly(CodeRuntimeCommand command) {
        Objects.requireNonNull(command, "command");
        ledgerEntryService.lockTarget(command.moduleAlias(), command.entityAlias(), command.fieldName());
        GenerateCodeResult generated = generate(command.withUniquenessChecker(ledgerAwareChecker(command)));
        return CodeRuntimeResult.generated(generated, false, null);
    }

    @Transactional
    public CodeRuntimeResult issueAndBind(CodeRuntimeCommand command) {
        if (command == null) {
            throw new PlatformException("Code runtime command must not be null");
        }
        if (command.sourceRecordId() == null || command.sourceRecordId().isBlank()) {
            throw new PlatformException("Code issueAndBind requires sourceRecordId");
        }
        ledgerEntryService.lockTarget(command.moduleAlias(), command.entityAlias(), command.fieldName());
        CodeLedgerEntry existing = ledgerEntryService.findActiveByTargetAndSourceRecord(
                command.moduleAlias(),
                command.entityAlias(),
                command.fieldName(),
                command.sourceRecordId()
        );
        if (existing != null) {
            CodeRule existingRule = ruleService.viewRuleTree(existing.getRuleId());
            return CodeRuntimeResult.bound(existing, existingRule);
        }
        var issuance = generateService.issue(command.withUniquenessChecker(ledgerAwareChecker(command)).toGenerateCommand());
        GenerateCodeResult generated = issuance.result();
        CodeRule rule = issuance.rule();
        ledgerEntryService.upsertActiveBinding(
                rule,
                generated.value(),
                generated.basisKey(),
                generated.periodKey(),
                command.sourceRecordId()
        );
        return CodeRuntimeResult.generated(generated, true, command.sourceRecordId());
    }

    private GenerateCodeResult generate(CodeRuntimeCommand command) {
        if (command == null) {
            throw new PlatformException("Code runtime command must not be null");
        }
        return generateService.generate(command.toGenerateCommand());
    }

    private CodeValueUniquenessChecker ledgerAwareChecker(CodeRuntimeCommand command) {
        return (resolved, generatedValue, context) -> {
            CodeValueUniquenessChecker checker = command.uniquenessChecker();
            if (checker != null && checker.exists(resolved, generatedValue, context)) {
                return true;
            }
            var staticTarget = ruleService.staticTarget(command.moduleAlias(), command.entityAlias());
            if (staticTarget.isPresent()) {
                var criteria = net.ximatai.muyun.database.core.orm.Criteria.of().eq(command.fieldName(), generatedValue);
                if (command.sourceRecordId() != null) criteria.ne("id", command.sourceRecordId());
                if (!staticTarget.get().list(criteria, new net.ximatai.muyun.database.core.orm.PageRequest(0, 1)).isEmpty()) return true;
            }
            CodeLedgerEntry entry = ledgerEntryService.findByTargetAndValue(command.moduleAlias(), command.entityAlias(), command.fieldName(), generatedValue);
            return entry != null
                    && (entry.getStatus() == CodeLedgerStatus.DISCARDED || entry.getStatus() == CodeLedgerStatus.ACTIVE
                    && (entry.getSourceRecordId() == null || !entry.getSourceRecordId().equals(command.sourceRecordId())));
        };
    }
}
