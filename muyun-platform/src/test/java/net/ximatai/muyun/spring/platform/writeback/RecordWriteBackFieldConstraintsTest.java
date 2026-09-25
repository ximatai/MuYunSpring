package net.ximatai.muyun.spring.platform.writeback;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordWriteBackFieldConstraintsTest {
    @Test
    void standardInsertAndUpdateNormalizeRequiredMatchFields() {
        var service = new RecordWriteBackMatchRuleService(new TestMemoryDao<>());
        var rule = new RecordWriteBackMatchRule();
        rule.setRuleId(" rule-1 ");
        rule.setSourceField(" sourceId ");
        rule.setTargetField(" id ");
        service.insert(rule);
        assertThat(service.select(rule.getId()).getRuleId()).isEqualTo("rule-1");
        assertThat(rule.getSourceField()).isEqualTo("sourceId");
        assertThat(rule.getTargetField()).isEqualTo("id");

        rule.setTargetField(" targetId ");
        service.update(rule);
        assertThat(service.select(rule.getId()).getTargetField()).isEqualTo("targetId");
        rule.setSourceField(" ");
        assertThatThrownBy(() -> service.update(rule))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sourceField");
        var empty = new RecordWriteBackMatchRule();
        assertThatThrownBy(() -> service.insert(empty))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ruleId");
    }

    @Test
    void sourceFieldRemainsRequiredOnlyForFieldSource() {
        var service = new RecordWriteBackFieldRuleService(new TestMemoryDao<>());
        var constant = new RecordWriteBackFieldRule();
        constant.setRuleId(" rule-1 ");
        constant.setTargetField(" amount ");
        constant.setSourceType(RecordWriteBackFieldSourceType.CONSTANT);
        constant.setConstantValue("  value with spaces  ");
        service.insert(constant);
        assertThat(constant.getRuleId()).isEqualTo("rule-1");
        assertThat(constant.getConstantValue()).isEqualTo("  value with spaces  ");
        constant.setSourceType(RecordWriteBackFieldSourceType.FIELD);
        assertThatThrownBy(() -> service.update(constant))
                .isInstanceOf(PlatformException.class).hasMessageContaining("sourceField");
    }
}
