package net.ximatai.muyun.spring.ability;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class TransactionScopeSupport {
    private static final String SPRING_TRANSACTION_SYNCHRONIZATION_MANAGER =
            "org.springframework.transaction.support.TransactionSynchronizationManager";
    private static final String SPRING_TRANSACTION_SYNCHRONIZATION =
            "org.springframework.transaction.support.TransactionSynchronization";

    private TransactionScopeSupport() {
    }

    public static boolean isTransactionActive() {
        return springBoolean("isActualTransactionActive");
    }

    public static void afterCommitOrNow(Runnable action) {
        if (!isTransactionActive() || !springBoolean("isSynchronizationActive")) {
            action.run();
            return;
        }
        try {
            Class<?> manager = Class.forName(SPRING_TRANSACTION_SYNCHRONIZATION_MANAGER);
            Class<?> synchronization = Class.forName(SPRING_TRANSACTION_SYNCHRONIZATION);
            Object callback = Proxy.newProxyInstance(
                    synchronization.getClassLoader(),
                    new Class<?>[]{synchronization},
                    (proxy, method, args) -> {
                        if ("afterCommit".equals(method.getName())) {
                            try {
                                action.run();
                            } catch (RuntimeException e) {
                                throw new AfterCommitActionException(e);
                            }
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
            Method register = manager.getMethod("registerSynchronization", synchronization);
            register.invoke(null, callback);
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            action.run();
        }
    }

    public static void afterCompletionOrNow(Runnable committed, Runnable rolledBack) {
        if (!isTransactionActive() || !springBoolean("isSynchronizationActive")) {
            committed.run();
            return;
        }
        try {
            Class<?> manager = Class.forName(SPRING_TRANSACTION_SYNCHRONIZATION_MANAGER);
            Class<?> synchronization = Class.forName(SPRING_TRANSACTION_SYNCHRONIZATION);
            Object callback = Proxy.newProxyInstance(synchronization.getClassLoader(), new Class<?>[]{synchronization},
                    (proxy, method, args) -> {
                        if ("afterCompletion".equals(method.getName())) {
                            if (args != null && args.length == 1 && Integer.valueOf(0).equals(args[0])) committed.run();
                            else rolledBack.run();
                        }
                        return defaultValue(method.getReturnType());
                    });
            manager.getMethod("registerSynchronization", synchronization).invoke(null, callback);
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            committed.run();
        }
    }

    private static boolean springBoolean(String methodName) {
        try {
            Class<?> manager = Class.forName(SPRING_TRANSACTION_SYNCHRONIZATION_MANAGER);
            Method method = manager.getMethod(methodName);
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            return false;
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return 0;
    }

    public static final class AfterCommitActionException extends RuntimeException {
        public AfterCommitActionException(RuntimeException cause) {
            super(cause);
        }

        public RuntimeException unwrap() {
            return (RuntimeException) getCause();
        }
    }
}
