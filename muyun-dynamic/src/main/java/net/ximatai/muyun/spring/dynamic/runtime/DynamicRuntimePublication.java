package net.ximatai.muyun.spring.dynamic.runtime;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One process's publication boundary for entity and page projections.
 * Executions may run concurrently; publication waits for existing executions and excludes new ones
 * until installation, transaction completion and any failure withdrawal have all finished.
 */
public final class DynamicRuntimePublication {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public Scope execution() {
        return acquire(lock.readLock());
    }

    public Scope publication() {
        if (lock.getReadHoldCount() > 0 && !lock.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("cannot publish dynamic configuration inside a runtime execution");
        }
        return acquire(lock.writeLock());
    }

    private Scope acquire(Lock target) {
        target.lock();
        return new Scope(target);
    }

    /** Thread-confined; enclosing code must close on the same thread, including exceptional exits. */
    public static final class Scope implements AutoCloseable {
        private Lock lock;
        private Scope(Lock lock) { this.lock = lock; }
        @Override public void close() {
            if (lock != null) {
                lock.unlock();
                lock = null;
            }
        }
    }
}
