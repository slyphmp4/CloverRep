package com.slyph.cloverrep.storage;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Serializes storage operations without using the Minecraft tick thread. */
public final class StorageTaskQueue {
    private final Logger logger;
    private final ThreadPoolExecutor executor;
    private volatile Runnable finalizer = () -> { };

    public StorageTaskQueue(Logger logger) {
        this.logger = logger;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1024), runnable -> {
                    Thread thread = new Thread(runnable, "CloverRep-storage");
                    thread.setDaemon(true);
                    return thread;
                }) {
            @Override
            protected void terminated() {
                try {
                    finalizer.run();
                } catch (RuntimeException exception) {
                    logger.severe("Storage shutdown failed: " + exception.getMessage());
                }
            }
        };
    }

    public <T> CompletableFuture<T> submit(Supplier<T> operation) {
        Operation<T> task = new Operation<>(operation);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            task.result.completeExceptionally(exception);
        }
        return task.result;
    }

    public void close(Runnable afterDrain) {
        finalizer = afterDrain;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.severe("Storage queue did not drain within 30 seconds; cancelling queued operations.");
                cancel(executor.shutdownNow());
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            cancel(executor.shutdownNow());
            Thread.currentThread().interrupt();
        }
    }

    private void cancel(List<Runnable> tasks) {
        for (Runnable task : tasks) {
            if (task instanceof Operation<?> operation) {
                operation.result.completeExceptionally(new RejectedExecutionException("Plugin is shutting down."));
            }
        }
    }

    private static final class Operation<T> implements Runnable {
        private final Supplier<T> operation;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private Operation(Supplier<T> operation) {
            this.operation = operation;
        }

        @Override
        public void run() {
            try {
                result.complete(operation.get());
            } catch (Throwable exception) {
                result.completeExceptionally(exception);
            }
        }
    }
}
