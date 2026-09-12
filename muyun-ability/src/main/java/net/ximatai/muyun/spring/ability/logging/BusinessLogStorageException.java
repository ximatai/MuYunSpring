package net.ximatai.muyun.spring.ability.logging;

/** Signals that a storage adapter did not persist a requested log fact. */
public class BusinessLogStorageException extends RuntimeException {
    public BusinessLogStorageException(String message, Throwable cause) { super(message, cause); }
}
