package com.slyph.cloverrep.storage;

public record StorageBackupResult(boolean success, String fileName, String errorMessage) {
    public static StorageBackupResult success(String fileName) {
        return new StorageBackupResult(true, fileName, "");
    }

    public static StorageBackupResult failure(String errorMessage) {
        return new StorageBackupResult(false, "", errorMessage == null ? "" : errorMessage);
    }
}
