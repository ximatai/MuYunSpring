package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.assistant.AssistantConversationService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/platform.assistant-conversations")
public class AssistantConversationWebController {
    private final AssistantConversationService conversations;
    public AssistantConversationWebController(AssistantConversationService conversations) { this.conversations = conversations; }
    @GetMapping
    public List<AssistantConversationService.Summary> list(@RequestParam String scopeKey, @RequestParam(defaultValue = "1") int page) {
        return conversations.list(scopeKey, page);
    }
    @GetMapping("/{id}")
    public AssistantConversationService.Snapshot read(@PathVariable String id, @RequestParam String scopeKey) { return conversations.read(id, scopeKey); }
    @PutMapping("/{id}")
    public AssistantConversationService.Snapshot save(@PathVariable String id, @RequestParam String scopeKey, @RequestBody AssistantConversationService.Command command) {
        return conversations.save(id, scopeKey, command);
    }
}
