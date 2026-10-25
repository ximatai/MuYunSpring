package net.ximatai.muyun.spring.iam.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.runtime.PlatformRuntimeModeProvider;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import java.util.*;

/** Deterministic editable fixture in provider configuration, never a separate IAM writer. */
@Component
@Conditional(MockIdentityProviderCondition.class)
public class MockExternalDirectoryProtocol implements ExternalDirectoryProtocol {
    private static final ObjectMapper JSON = new ObjectMapper();
    public MockExternalDirectoryProtocol(PlatformRuntimeModeProvider mode) {
        if (!mode.isDevelopment()) throw new IllegalStateException("mock directory requires development mode");
    }
    public String providerType() { return "mock"; }
    public record Data(List<Department> departments, List<Person> people, List<String> failPages) { }
    private Data data(Map<String, String> configuration) {
        try {
            Data data = JSON.readValue(configuration.getOrDefault("directory", "{\"departments\":[],\"people\":[],\"failPages\":[]}"), Data.class);
            Objects.requireNonNull(data.departments()); Objects.requireNonNull(data.people());
            Set<String> ids = new HashSet<>();
            for (var d : data.departments()) {
                if (d.id() == null || !ids.add(d.id()) || d.title() == null) throw new IllegalArgumentException();
                if (d.parentId() != null && !ids.contains(d.parentId())) throw new IllegalArgumentException();
                List<String> ancestors = new ArrayList<>(); String parent = d.parentId();
                while (parent != null) {
                    ancestors.addFirst(parent); String key = parent;
                    parent = data.departments().stream().filter(v -> v.id().equals(key)).findFirst().orElseThrow().parentId();
                }
                if (!ancestors.equals(d.ancestorIds())) throw new IllegalArgumentException();
            }
            ids.clear();
            for (var p : data.people()) {
                if (p.id() == null || !ids.add(p.id()) || p.title() == null || !p.departmentIds().contains(p.primaryDepartmentId())
                        || !Set.of("active", "disabled", "departed").contains(p.status())) throw new IllegalArgumentException();
                for (String id : p.departmentIds()) if (data.departments().stream().noneMatch(d -> d.id().equals(id))) throw new IllegalArgumentException();
            }
            return data;
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid mock directory fixture (ordered departments, unique people, memberships and status required)"); }
    }
    public void validateConfiguration(Map<String, String> configuration) { data(configuration); }
    public Department department(IdentityProviderDescriptor provider, String id) {
        return data(provider.configuration()).departments().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }
    public Page<Department> departments(IdentityProviderDescriptor provider, String root, String cursor, int limit) {
        var data = data(provider.configuration());
        return page(data, "departments:" + root, data.departments().stream().filter(d -> d.id().equals(root) || d.ancestorIds().contains(root)).toList(), cursor, limit);
    }
    public Page<String> departmentPeople(IdentityProviderDescriptor provider, String department, String cursor, int limit) {
        var data = data(provider.configuration());
        return page(data, "people:" + department, data.people().stream().filter(p -> p.departmentIds().contains(department)).map(Person::id).toList(), cursor, limit);
    }
    public Person person(IdentityProviderDescriptor provider, String id) {
        return data(provider.configuration()).people().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }
    private <T> Page<T> page(Data data, String key, List<T> items, String cursor, int limit) {
        int offset = cursor == null ? 0 : Integer.parseInt(cursor);
        if (offset < 0 || offset > items.size() || limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid page");
        if (data.failPages() != null && data.failPages().contains(key + ":" + offset)) throw new IllegalStateException("MOCK_PAGE_UNAVAILABLE");
        int end = Math.min(offset + limit, items.size());
        return new Page<>(items.subList(offset, end), end == items.size() ? null : Integer.toString(end));
    }
}
