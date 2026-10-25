package net.ximatai.muyun.spring.iam.federation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only vendor boundary. Pages are stable and departments are returned parent before child. */
public interface ExternalDirectoryProtocol {
    String providerType();
    default void validateConfiguration(Map<String, String> configuration) { }

    /** Configuration keys identifying the authority; immutable once any identity is bound. */
    default Set<String> identityScopeConfigurationKeys() {
        return Set.of();
    }

    Department department(IdentityProviderDescriptor provider, String id);
    Page<Department> departments(IdentityProviderDescriptor provider, String rootId, String cursor, int limit);
    Page<String> departmentPeople(IdentityProviderDescriptor provider, String departmentId, String cursor, int limit);
    Person person(IdentityProviderDescriptor provider, String id);
    record Department(String id, String parentId, String title, List<String> ancestorIds) {
        public Department { ancestorIds = List.copyOf(ancestorIds); }
    }
    record Person(String id, String title, String employeeNo, String primaryDepartmentId,
                  List<String> departmentIds, String status, String mobile, String email) {
        public Person { departmentIds = List.copyOf(departmentIds); }
    }
    record Page<T>(List<T> items, String nextCursor) {
        public Page { items = List.copyOf(items); }
    }
}
