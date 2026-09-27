package net.ximatai.muyun.spring.platform.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Personal transcript storage. Historical text never restores an execution authorization. */
@Service
public class AssistantConversationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AssistantConversationDao conversations;
    public AssistantConversationService(AssistantConversationDao conversations) {
        this.conversations = Objects.requireNonNull(conversations);
    }
    public record Message(String role, String text) {}
    public record Content(String title, List<Message> messages, List<Message> history, String planId, String pendingRequest) {}
    public record Command(int expectedRevision, Content content) {}
    public record Snapshot(String id, int revision, Instant updatedAt, Content content) {}
    public record Summary(String id, String title, Instant updatedAt) {}

    public List<Summary> list(String scopeKey, int page) {
        validateScope(scopeKey);
        if (page < 1 || page > 10000) throw new IllegalArgumentException("无效页码");
        Scope scope = scope();
        Criteria criteria = Criteria.of().eq("ownerId", scope.owner()).eq("scopeKey", scopeKey);
        if (scope.tenant() == null) criteria.isNull("tenantId");
        else criteria.eq("tenantId", scope.tenant());
        return conversations.query(criteria, PageRequest.of(page, 30), Sort.desc("updatedAt")).stream()
                .map(row -> new Summary(row.getId(), row.getTitle(), row.getUpdatedAt())).toList();
    }
    public Snapshot read(String id, String scopeKey) {
        validateId(id); validateScope(scopeKey);
        return snapshot(requireOwner(conversations.findById(id), scopeKey, scope()));
    }
    @Transactional
    public Snapshot save(String id, String scopeKey, Command command) {
        validateId(id); validateScope(scopeKey);
        if (command == null || command.expectedRevision() < 0) throw new IllegalArgumentException("无效会话版本");
        String json = encode(command.content());
        Scope scope = scope();
        PlatformAbilityRuntime.lockMutationPartition("platform.assistant-conversation", id);
        var row = conversations.findById(id);
        if (row != null) {
            requireOwner(row, scopeKey, scope);
            if (row.getContentJson().equals(json)) return snapshot(row);
        }
        int revision = row == null ? 0 : row.getVersion() + 1;
        if (revision != command.expectedRevision())
            throw BusinessExceptions.warning("platform.assistant-conversation.stale", "会话已在其他窗口更新，请重新打开历史会话；当前内容保留");
        try (var ignored = scope.tenant() == null ? TenantContext.system("personal assistant history") : TenantContext.use(scope.tenant())) {
            Instant now = Instant.now();
            if (row == null) {
                row = new AssistantConversation();
                row.setId(id); row.setOwnerId(scope.owner()); row.setTenantId(scope.tenant());
                row.setScopeKey(scopeKey); row.setTitle(command.content().title()); row.setContentJson(json);
                EntityLifecycle.prepareInsert(row, now);
                conversations.insert(row);
            } else {
                int version = row.getVersion();
                row.setTitle(command.content().title()); row.setContentJson(json);
                EntityLifecycle.prepareUpdate(row, now);
                if (conversations.updateByIdAndVersion(row, version) != 1)
                    throw new IllegalStateException("Conversation changed while locked");
            }
            return snapshot(row);
        }
    }
    private AssistantConversation requireOwner(AssistantConversation row, String scopeKey, Scope scope) {
        if (row == null || !Objects.equals(row.getOwnerId(), scope.owner()) ||
                !Objects.equals(row.getTenantId(), scope.tenant()) || !row.getScopeKey().equals(scopeKey))
            throw new PlatformAccessDeniedException("会话不存在或无权访问");
        return row;
    }
    private record Scope(String owner, String tenant) {}
    private Scope scope() {
        var user = CurrentUserContext.currentUser().orElseThrow(() -> new PlatformAccessDeniedException("请先登录"));
        if (!user.system() && (user.tenantId() == null || user.tenantId().isBlank()))
            throw new PlatformAccessDeniedException("租户身份不完整");
        return new Scope(user.userId(), user.system() ? null : user.tenantId());
    }
    private static void validateId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,32}")) throw new IllegalArgumentException("无效会话标识");
    }
    private static void validateScope(String scope) {
        if (scope == null || scope.isBlank() || scope.length() > 1024) throw new IllegalArgumentException("无效会话范围");
    }
    private static String encode(Content content) {
        if (content == null || content.title() == null || content.title().isBlank() || content.title().length() > 120)
            throw new IllegalArgumentException("会话标题不能为空或超过 120 字");
        validateMessages(content.messages(), 1000, 32000, true);
        validateMessages(content.history(), 12, 4000, false);
        if (content.history().stream().mapToInt(message -> message.text().length()).sum() > 16000)
            throw new IllegalArgumentException("模型历史过长");
        if (content.pendingRequest() != null && content.pendingRequest().length() > 4000) throw new IllegalArgumentException("未完成请求过长");
        if (content.planId() != null) validateId(content.planId());
        try {
            String json = JSON.writeValueAsString(content);
            if (json.length() > 512000) throw new IllegalArgumentException("会话已达到保存上限，请开启新对话");
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("会话格式无效", error); }
    }
    private static void validateMessages(List<Message> messages, int max, int length, boolean status) {
        if (messages == null || messages.size() > max) throw new IllegalArgumentException("会话消息过多");
        for (var message : messages) {
            if (message == null || message.text() == null || message.text().length() > length ||
                    !("user".equals(message.role()) || "assistant".equals(message.role()) || status && "status".equals(message.role())))
                throw new IllegalArgumentException("会话消息格式无效");
        }
    }
    private Snapshot snapshot(AssistantConversation row) {
        try { return new Snapshot(row.getId(), row.getVersion() + 1, row.getUpdatedAt(), JSON.readValue(row.getContentJson(), Content.class)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("会话无法读取", error); }
    }
}
