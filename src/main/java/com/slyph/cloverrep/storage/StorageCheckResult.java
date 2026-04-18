package com.slyph.cloverrep.storage;

public record StorageCheckResult(
        boolean supported,
        boolean success,
        long latencyMillis,
        long playersCount,
        long votesCount,
        String errorMessage
) {
    public static StorageCheckResult unsupported(String errorMessage) {
        return new StorageCheckResult(false, false, -1L, -1L, -1L, errorMessage == null ? "" : errorMessage);
    }

    public static StorageCheckResult success(long latencyMillis, long playersCount, long votesCount) {
        return new StorageCheckResult(true, true, latencyMillis, playersCount, votesCount, "");
    }

    public static StorageCheckResult failure(String errorMessage) {
        return new StorageCheckResult(true, false, -1L, -1L, -1L, errorMessage == null ? "" : errorMessage);
    }
}
