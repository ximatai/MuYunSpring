package net.ximatai.muyun.spring.common.platform;

/** A business service can contribute a domain action without depending on a workflow or Web controller. */
public interface ModuleRecordActionExecutor {
    boolean supports(String moduleAlias, String actionCode);
    Object execute(ModuleRecordActionCommand command);

    /** Trusted task orchestration only: the caller has verified the current TODO, operator and frozen guide. */
    default Object executeApprovalBusiness(ModuleRecordActionCommand command) { return execute(command); }
}
