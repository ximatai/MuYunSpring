package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/** Ownership of a position, independent of the employee's local enabled state. */
public enum EmploymentSource implements CodeTitleEnum {
    MANUAL("manual", "手工维护"), DIRECTORY("directory", "通讯录同步");
    private final String code;
    private final String title;
    EmploymentSource(String code, String title) { this.code = code; this.title = title; }
    public String getCode() { return code; }
    public String getTitle() { return title; }
}
