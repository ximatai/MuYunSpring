package net.ximatai.muyun.spring.platform.web.notification;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Generic HTTP projection; command handlers remain owned by the business module. */
@RestController
@RequestMapping("/platform/notifications/commands")
public class BusinessNotificationCommandController {
    private final BusinessNotificationCommandDispatcher dispatcher;

    public BusinessNotificationCommandController(BusinessNotificationCommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/{command}")
    public Object invoke(@PathVariable String command, @RequestBody BusinessNotificationCommandRequest request) {
        return dispatcher.dispatch(command, request);
    }
}
