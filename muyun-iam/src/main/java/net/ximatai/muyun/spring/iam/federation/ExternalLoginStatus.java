package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

/**
 * Observable state of an external login transaction. Only providers that report scan progress may add
 * intermediate states; the platform never invents a "scanned" state on their behalf.
 */
public enum ExternalLoginStatus implements CodeTitleEnum {
    PENDING("pending", "等待授权"),
    CONFIRMATION_REQUIRED("confirmationRequired", "请确认加入"),
    REVIEW_PENDING("reviewPending", "等待审核"),
    REVIEW_REJECTED("reviewRejected", "申请已拒绝"),
    SUCCEEDED("succeeded", "已完成"),
    DENIED("denied", "已拒绝"),
    EXPIRED("expired", "已过期");

    private final String code;
    private final String title;

    ExternalLoginStatus(String code, String title) {
        this.code = code;
        this.title = title;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getTitle() {
        return title;
    }
}
