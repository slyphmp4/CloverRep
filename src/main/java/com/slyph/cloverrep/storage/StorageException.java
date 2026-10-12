package com.slyph.cloverrep.storage;

/** A storage failure is distinct from a missing player or a zero reputation. */
public final class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
