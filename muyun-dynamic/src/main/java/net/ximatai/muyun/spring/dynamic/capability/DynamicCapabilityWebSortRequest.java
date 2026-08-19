package net.ximatai.muyun.spring.dynamic.capability;

/** Source-neutral sort/placement input used by the registered dynamic-web capability handlers. */
public record DynamicCapabilityWebSortRequest(String id, String previousId, String nextId, String parentId) {
    public boolean hasPreviousId() {
        return hasText(previousId);
    }

    public boolean hasNextId() {
        return hasText(nextId);
    }

    public boolean hasParentId() {
        return hasText(parentId);
    }

    public boolean hasPlacementIntent() {
        return hasPreviousId() || hasNextId() || hasParentId();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
