package net.ximatai.muyun.spring.platform.code.fixture;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.*;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.code.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
import net.ximatai.muyun.spring.common.model.constraint.Required;

import java.util.Arrays;

@Getter @Setter
@Table(name = "code_static_contract", comment = "Static business coding contract")
public class StaticCodeRecord extends StandardEntity {
    @net.ximatai.muyun.spring.common.model.constraint.NormalizeText
    @BusinessCode(primary = true)
    @Column(name = "document_no", type = ColumnType.VARCHAR, length = 64)
    private String documentNo;
    @BusinessCode(trigger = CodeTrigger.EXPLICIT)
    @Column(name = "confirmation_no", type = ColumnType.VARCHAR, length = 64)
    private String confirmationNo;
    private java.util.List<StaticCodeLine> lines;
    @Column(name = "business_organization_id", type = ColumnType.VARCHAR, length = 64)
    private String businessOrganizationId;
    @Required
    @Column(name = "subject", type = ColumnType.VARCHAR, length = 100)
    private String subject;
    @Column(name = "device_type", type = ColumnType.VARCHAR, length = 64)
    private DeviceType deviceType;

    public enum DeviceType implements CodeTitleEnum {
        MR_GLASSES("mr_glasses"), SAFETY_HELMET("safety_helmet"), UNMAPPED("unmapped");
        private final String code;
        DeviceType(String code) { this.code = code; }
        public String getCode() { return code; }
        public String getTitle() { return code; }
        @JsonCreator
        public static DeviceType fromCode(String code) {
            return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst().orElseThrow();
        }
    }
}
