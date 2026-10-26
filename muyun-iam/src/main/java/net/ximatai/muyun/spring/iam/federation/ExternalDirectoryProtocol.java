package net.ximatai.muyun.spring.iam.federation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only vendor boundary. Pages are stable and departments are returned parent before child. */
public interface ExternalDirectoryProtocol {
    /** A complete scope cannot be read within the adapter's configured bounds; never truncate it. */
    final class ReadLimitExceededException extends IllegalArgumentException {
        public ReadLimitExceededException() {
            super("Directory read limit exceeded; select narrower synchronization roots");
        }
    }

    String providerType();
    default void validateConfiguration(Map<String, String> configuration) { }

    /** Configuration keys identifying the authority; immutable once any identity is bound. */
    default Set<String> identityScopeConfigurationKeys() {
        return Set.of();
    }

    Department department(IdentityProviderDescriptor provider, String id);
    /** Includes the requested root (also available through department), then its descendants parent-first. */
    Page<Department> departments(IdentityProviderDescriptor provider, String rootId, String cursor, int limit);
    /** Each item observes membership in the requested department; main/all departments are optional. */
    Page<Person> departmentPeople(IdentityProviderDescriptor provider, String departmentId, String cursor, int limit);
    /** Optional authoritative lookup used only for stale-source login verification. */
    default boolean supportsPersonLookup(IdentityProviderDescriptor provider) { return false; }
    default Person person(IdentityProviderDescriptor provider, String id) {
        throw new UnsupportedOperationException("Person lookup unavailable");
    }
    record Department(String id, String parentId, String title) { }
    /** Status is active/disabled/departed/unknown. Unknown proves presence without authorizing mutations. */
    record Person(String id, String title, String employeeNo, String primaryDepartmentId,
                  List<String> departmentIds, String status, String mobile, String email) {
        public Person { departmentIds = departmentIds == null ? List.of() : List.copyOf(departmentIds); }
        public Person withMemberships(String primary, List<String> departments) {
            return new Person(id, title, employeeNo, primary, departments, status, mobile, email);
        }
    }
    record Page<T>(List<T> items, String nextCursor) {
        public Page { items = List.copyOf(items); }
    }
}
