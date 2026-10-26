package net.ximatai.muyun.spring.iam.federation;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
public enum JoinApplicationStatus implements CodeTitleEnum {
    PENDING("pending", "待审核"), APPROVED("approved", "已批准"), REJECTED("rejected", "已拒绝");
    private final String code;
    private final String title;
    JoinApplicationStatus(String code, String title) { this.code = code; this.title = title; }
    public String getCode() { return code; }
    public String getTitle() { return title; }
}
