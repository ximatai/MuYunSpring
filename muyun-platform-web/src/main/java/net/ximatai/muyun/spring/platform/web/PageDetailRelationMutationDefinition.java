package net.ximatai.muyun.spring.platform.web;

/** Explicit static declaration of the mutations a direct relation may expose. */
public record PageDetailRelationMutationDefinition(boolean createAllowed,
                                                   boolean updateAllowed,
                                                   boolean deleteAllowed) {
    public PageDetailRelationMutationDefinition {
        if (!createAllowed && !updateAllowed && !deleteAllowed) {
            throw new IllegalArgumentException("managed detail relation must declare at least one mutation");
        }
    }

    public static PageDetailRelationMutationDefinition standardCrud() {
        return new PageDetailRelationMutationDefinition(true, true, true);
    }
}
