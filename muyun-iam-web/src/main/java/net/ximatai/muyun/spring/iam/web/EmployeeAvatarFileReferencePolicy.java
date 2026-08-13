package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceFieldPolicy;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceReadRequest;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadRequest;
import org.springframework.stereotype.Component;

/** Keeps employee avatar upload and preview within the bound principal's own personnel record. */
@Component
public class EmployeeAvatarFileReferencePolicy implements FileReferenceFieldPolicy {
    private final EmployeeAccountService employeeAccountService;

    public EmployeeAvatarFileReferencePolicy(EmployeeAccountService employeeAccountService) {
        this.employeeAccountService = employeeAccountService;
    }

    @Override
    public boolean supportsField(String moduleAlias, String relationCode, String fieldName) {
        return "iam.employee".equals(moduleAlias) && relationCode == null && "avatarAssetId".equals(fieldName);
    }

    @Override
    public void authorizeUpload(FileReferenceUploadRequest request) {
        requireOwnEmployee(request.draft());
    }

    @Override
    public boolean readAvailable() {
        return true;
    }

    @Override
    public void authorizeRead(FileReferenceReadRequest request) {
        requireOwnEmployee(request.draft());
    }

    private void requireOwnEmployee(java.util.Map<String, Object> draft) {
        CurrentUser currentUser = CurrentUserContext.currentUser().orElseThrow(() -> new PlatformException(
                PlatformErrorCodes.ACCESS_DENIED, 403, "current user is required to access employee avatars"));
        String boundEmployeeId = employeeAccountService.employeeIdOfUser(currentUser.userId());
        Object recordId = draft.get("id");
        if (!(recordId instanceof String employeeId) || employeeId.isBlank() || !employeeId.equals(boundEmployeeId)) {
            throw new PlatformException(PlatformErrorCodes.ACCESS_DENIED, 403,
                    "employee avatars can only be accessed by their bound user account");
        }
    }
}
