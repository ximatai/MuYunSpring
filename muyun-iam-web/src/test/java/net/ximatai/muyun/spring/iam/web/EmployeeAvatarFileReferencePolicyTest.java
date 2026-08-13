package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadFile;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadIntent;
import net.ximatai.muyun.spring.platform.attachment.FileReferenceUploadRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmployeeAvatarFileReferencePolicyTest {
    @AfterEach
    void clearCurrentUser() {
        CurrentUserContext.clear();
    }

    @Test
    void shouldAllowOnlyTheBoundEmployeeAvatar() {
        EmployeeAccountService accountService = mock(EmployeeAccountService.class);
        when(accountService.employeeIdOfUser("user-1")).thenReturn("employee-1");
        EmployeeAvatarFileReferencePolicy policy = new EmployeeAvatarFileReferencePolicy(accountService);

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "alice", "tenant-1"))) {
            assertThatCode(() -> policy.authorizeUpload(request("employee-1"))).doesNotThrowAnyException();
            assertThatThrownBy(() -> policy.authorizeUpload(request("employee-2")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("bound user account");
        }
    }

    private FileReferenceUploadRequest request(String employeeId) {
        return new FileReferenceUploadRequest("iam.employee", null, "avatarAssetId", Map.of("id", employeeId),
                new FileReferenceUploadFile("avatar.png", "image/png", 1024), FileReferenceUploadIntent.CREATE);
    }
}
