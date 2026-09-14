package net.ximatai.muyun.spring.common.identity;

import java.util.Optional;

/** Optional IAM collaborator used when an audit fact needs the employee's current department. */
public interface CurrentUserDepartmentResolver {
    CurrentUserDepartmentResolver NONE = currentUser -> Optional.empty();

    Optional<String> resolveDepartmentId(CurrentUser currentUser);
}
