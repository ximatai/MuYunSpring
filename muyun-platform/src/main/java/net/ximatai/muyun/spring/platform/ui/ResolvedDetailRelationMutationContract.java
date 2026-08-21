package net.ximatai.muyun.spring.platform.ui;

/**
 * Server-issued mutation permission for one direct detail relation.
 *
 * <p>This is deliberately independent from the read contract: a relation remains read-only
 * unless its compiled execution plan explicitly supplies this contract.</p>
 */
public record ResolvedDetailRelationMutationContract(
        boolean createAllowed,
        boolean updateAllowed,
        boolean deleteAllowed,
        String createActionCode,
        String updateActionCode,
        String deleteActionCode
) {
    public ResolvedDetailRelationMutationContract(boolean createAllowed, boolean updateAllowed, boolean deleteAllowed) {
        this(createAllowed, updateAllowed, deleteAllowed, null, null, null);
    }

    public ResolvedDetailRelationMutationContract {
        createActionCode = normalizeAllowedAction(createAllowed, createActionCode, "create");
        updateActionCode = normalizeAllowedAction(updateAllowed, updateActionCode, "update");
        deleteActionCode = normalizeAllowedAction(deleteAllowed, deleteActionCode, "delete");
    }

    public boolean hasAnyMutation() {
        return createAllowed || updateAllowed || deleteAllowed;
    }

    private static String normalizeAllowedAction(boolean allowed, String actionCode, String operation) {
        if (!allowed) return null;
        if (actionCode == null || actionCode.isBlank()) {
            throw new IllegalArgumentException(operation + " action code must not be blank");
        }
        return net.ximatai.muyun.spring.common.util.PlatformNameRules.requireActionCode(actionCode, operation + " action code");
    }
}
