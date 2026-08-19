package net.ximatai.muyun.spring.demo.notification;

import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.platform.notification.BusinessNotification;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationNavigateAction;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationRecordAction;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationRecipients;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** A manual school-demo trigger for verifying the platform business notification flow. */
@RestController
@Profile("school-demo")
@RequestMapping("/education.notification-demo")
public class DemoNotificationWebController {
    private final BusinessNotificationService notificationService;

    public DemoNotificationWebController(BusinessNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/trigger")
    public Map<String, String> trigger() {
        return publish(false);
    }

    @PostMapping("/trigger-dismissible")
    public Map<String, String> triggerDismissible() {
        return publish(true);
    }

    @PostMapping("/trigger-multiline")
    public Map<String, String> triggerMultiline() {
        CurrentUser currentUser = CurrentUserContext.currentUser()
                .orElseThrow(() -> new AuthenticationFailedException("authentication is required"));
        String notificationId = Ids.newId();
        notificationService.publish(new BusinessNotification(
                notificationId,
                "education.demo.multiline-reminder",
                "学生档案审核提醒",
                "教学管理演示 · 普通提醒",
                "学生：张晓明（2026 级软件工程）\n请在本周五 17:00 前完成学籍材料核验。\n材料包含：身份证明、入学登记表与家庭信息确认单。\n如信息存在差异，请先查看档案并联系辅导员。",
                true,
                new BusinessNotificationRecipients(false, List.of(), List.of(), List.of(), List.of(),
                        List.of(currentUser.userId())),
                List.of(
                        new BusinessNotificationNavigateAction("view", "查看学生", "education.student", null,
                                "LIST", Map.of(), false),
                        new BusinessNotificationRecordAction("approve", "同意", "education.notification-demo", notificationId,
                                "approve", Map.of(), false, null, true),
                        new BusinessNotificationRecordAction("reject", "拒绝", "education.notification-demo", notificationId,
                                "reject", Map.of(), true, "确认拒绝这份学生档案？", true))));
        return Map.of("notificationId", notificationId, "message", "多行内容演示提醒已发送到当前在线用户");
    }

    private Map<String, String> publish(boolean dismissible) {
        CurrentUser currentUser = CurrentUserContext.currentUser()
                .orElseThrow(() -> new AuthenticationFailedException("authentication is required"));
        String notificationId = Ids.newId();
        notificationService.publish(new BusinessNotification(
                notificationId,
                "education.demo.approval-arrived",
                dismissible ? "这是一条可关闭的提示" : "有一份学生档案需要确认",
                "教学管理演示",
                dismissible
                        ? "这类普通提醒可通过右上角关闭，不要求用户处理。"
                        : "这是一条实时业务提醒。你可以查看学生模块，或模拟同意、拒绝操作。",
                dismissible,
                new BusinessNotificationRecipients(false, List.of(), List.of(), List.of(), List.of(),
                        List.of(currentUser.userId())),
                List.of(
                        new BusinessNotificationNavigateAction("view", "查看学生", "education.student", null,
                                "LIST", Map.of(), false),
                        new BusinessNotificationRecordAction("approve", "同意", "education.notification-demo", notificationId,
                                "approve", Map.of(), false, null, true),
                        new BusinessNotificationRecordAction("reject", "拒绝", "education.notification-demo", notificationId,
                                "reject", Map.of(), true, "确认拒绝这份学生档案？", true))));
        return Map.of("notificationId", notificationId, "message", "演示提醒已发送到当前在线用户");
    }

    @PostMapping("/{actionCode:approve|reject}/{id}")
    public Map<String, String> executeRecordAction(@PathVariable String actionCode, @PathVariable String id) {
        CurrentUserContext.currentUser().orElseThrow(() -> new AuthenticationFailedException("authentication is required"));
        return Map.of("status", actionCode.equals("approve") ? "approved" : "rejected", "notificationId", id);
    }
}
