package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.dynamic.web.DynamicRecordWebController;
import net.ximatai.muyun.spring.iam.web.TenantWebController;
import net.ximatai.muyun.spring.iam.web.UserAccountWebController;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ActionEndpointAnnotationTest {
    @Test
    void shouldDescribeStandardCrudEndpointActionSemantics() throws Exception {
        assertThat(endpoint(CrudWeb.class, "query", WebQueryRequest.class).value()).isEqualTo(PlatformAction.QUERY);
        assertThat(endpoint(CrudWeb.class, "querySchema", String.class).value()).isEqualTo(PlatformAction.QUERY);
        assertThat(endpoint(CrudWeb.class, "formSchema", String.class).value()).isEqualTo(PlatformAction.VIEW);
        assertThat(endpoint(CrudWeb.class, "view", String.class).value()).isEqualTo(PlatformAction.VIEW);
        assertThat(endpoint(CrudWeb.class, "insert", EntityContract.class).value()).isEqualTo(PlatformAction.CREATE);
        assertThat(endpoint(CrudWeb.class, "update", String.class, EntityContract.class).value()).isEqualTo(PlatformAction.UPDATE);
        assertThat(endpoint(CrudWeb.class, "delete", String.class, RecordActionWebRequest.class).value())
                .isEqualTo(PlatformAction.DELETE);
    }

    @Test
    void shouldDescribeAbilityEndpointActionSemantics() throws Exception {
        assertThat(endpoint(EnableWeb.class, "enable", String.class, RecordActionWebRequest.class).value())
                .isEqualTo(PlatformAction.ENABLE);
        assertThat(endpoint(EnableWeb.class, "disable", String.class, RecordActionWebRequest.class).value())
                .isEqualTo(PlatformAction.DISABLE);
        assertThat(endpoint(SortWeb.class, "sort", String.class, SortWebRequest.class).value()).isEqualTo(PlatformAction.SORT);
        assertThat(endpoint(TreeWeb.class, "tree", HttpServletRequest.class, boolean.class).value())
                .isEqualTo(PlatformAction.TREE);
        assertThat(endpoint(ReferenceWeb.class, "reference", String.class, Object.class).value())
                .isEqualTo(PlatformAction.REFERENCE);
    }

    @Test
    void shouldNotTreatActionListEndpointsAsBusinessActionEndpoints() throws Exception {
        assertThat(ActionWeb.class.getMethod("actions").getAnnotation(ActionEndpoint.class)).isNull();
    }

    @Test
    void shouldKeepActionEndpointWhenDynamicControllerOverridesStandardWebMethods() throws Exception {
        assertThat(endpoint(DynamicRecordWebController.class, "sort",
                HttpServletRequest.class, String.class, TreeSortWebRequest.class).value()).isEqualTo(PlatformAction.SORT);
        assertThat(endpoint(DynamicRecordWebController.class, "querySchema", String.class).value())
                .isEqualTo(PlatformAction.QUERY);
        Class<?> referenceRequestType = Class.forName("net.ximatai.muyun.spring.dynamic.web.DynamicWebReferenceRequest");
        assertThat(endpoint(DynamicRecordWebController.class, "reference", String.class, referenceRequestType).value())
                .isEqualTo(PlatformAction.REFERENCE);
    }

    @Test
    void shouldDescribeUserManagementEndpointActionSemantics() throws Exception {
        CustomActionEndpoint endpoint = customEndpoint(UserAccountWebController.class, "changePassword",
                String.class, UserAccountWebController.ChangePasswordRequest.class);
        assertThat(endpoint.value()).isEqualTo("changePassword");
        assertThat(endpoint.level()).isEqualTo(PlatformActionLevel.RECORD);
        assertThat(endpoint.dataAuth()).isTrue();
        assertThat(endpoint.recordIdPathVariable()).isEqualTo("id");

        CustomActionEndpoint resetEndpoint = customEndpoint(UserAccountWebController.class, "resetPassword",
                String.class);
        assertThat(resetEndpoint.value()).isEqualTo("resetPassword");
        assertThat(resetEndpoint.level()).isEqualTo(PlatformActionLevel.RECORD);
        assertThat(resetEndpoint.dataAuth()).isTrue();
        assertThat(resetEndpoint.recordIdPathVariable()).isEqualTo("id");

        CustomActionEndpoint forceLogoutEndpoint = customEndpoint(UserAccountWebController.class, "forceLogout",
                String.class);
        assertThat(forceLogoutEndpoint.value()).isEqualTo("forceLogout");
        assertThat(forceLogoutEndpoint.level()).isEqualTo(PlatformActionLevel.RECORD);
        assertThat(forceLogoutEndpoint.dataAuth()).isTrue();
        assertThat(forceLogoutEndpoint.recordIdPathVariable()).isEqualTo("id");

        CustomActionEndpoint sessionsEndpoint = customEndpoint(UserAccountWebController.class, "activeSessions",
                String.class, HttpServletRequest.class);
        assertThat(sessionsEndpoint.value()).isEqualTo("sessions");
        assertThat(sessionsEndpoint.level()).isEqualTo(PlatformActionLevel.RECORD);
        assertThat(sessionsEndpoint.dataAuth()).isTrue();
        assertThat(sessionsEndpoint.recordIdPathVariable()).isEqualTo("id");

        CustomActionEndpoint revokeSessionEndpoint = customEndpoint(UserAccountWebController.class, "revokeSession",
                String.class, String.class, HttpServletRequest.class);
        assertThat(revokeSessionEndpoint.value()).isEqualTo("revokeSession");
        assertThat(revokeSessionEndpoint.level()).isEqualTo(PlatformActionLevel.RECORD);
        assertThat(revokeSessionEndpoint.dataAuth()).isTrue();
        assertThat(revokeSessionEndpoint.recordIdPathVariable()).isEqualTo("id");

        CustomActionEndpoint revokeSessionsEndpoint = customEndpoint(UserAccountWebController.class, "revokeSessions",
                String.class, UserAccountWebController.RevokeSessionsRequest.class, HttpServletRequest.class);
        assertThat(revokeSessionsEndpoint.value()).isEqualTo("revokeSessions");
        assertThat(revokeSessionsEndpoint.level()).isEqualTo(PlatformActionLevel.RECORD);
        assertThat(revokeSessionsEndpoint.dataAuth()).isTrue();
        assertThat(revokeSessionsEndpoint.recordIdPathVariable()).isEqualTo("id");
    }

    @Test
    void shouldDescribeRecycleBinLifecycleActionsAsIndependentPlatformActions() throws Exception {
        assertThat(endpoint(RecycleBinWeb.class, "recycleBin", WebQueryRequest.class).value())
                .isEqualTo(PlatformAction.RECYCLE_BIN_QUERY);
        assertThat(endpoint(RecycleBinWeb.class, "viewRecycleBinRecord", String.class).value())
                .isEqualTo(PlatformAction.RECYCLE_BIN_QUERY);
        assertThat(endpoint(RecycleBinWeb.class, "restoreFromRecycleBin", String.class).value())
                .isEqualTo(PlatformAction.RECYCLE_BIN_RESTORE);
        assertThat(endpoint(RecycleBinPurgeWeb.class, "purgeFromRecycleBin", String.class).value())
                .isEqualTo(PlatformAction.RECYCLE_BIN_PURGE);
        assertThat(RecycleBinWeb.class.getMethod("restoreFromRecycleBin", String.class)
                .getAnnotation(BusinessMutation.class)).isNotNull();
        assertThat(RecycleBinPurgeWeb.class.getMethod("purgeFromRecycleBin", String.class)
                .getAnnotation(BusinessMutation.class)).isNotNull();
    }

    private ActionEndpoint endpoint(Class<?> type, String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = type.getMethod(methodName, parameterTypes);
        return method.getAnnotation(ActionEndpoint.class);
    }

    private CustomActionEndpoint customEndpoint(Class<?> type, String methodName, Class<?>... parameterTypes)
            throws Exception {
        Method method = type.getMethod(methodName, parameterTypes);
        return method.getAnnotation(CustomActionEndpoint.class);
    }
}
