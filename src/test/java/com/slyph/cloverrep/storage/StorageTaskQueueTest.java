package com.slyph.cloverrep.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class StorageTaskQueueTest {
    @Test
    void operationsStayOrderedAndShutdownFlushesAfterLastOperation() throws Exception {
        StorageTaskQueue queue = new StorageTaskQueue(Logger.getAnonymousLogger());
        List<Integer> order = new ArrayList<>();
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            int value = index;
            futures.add(queue.submit(() -> {
                assertEquals("CloverRep-storage", Thread.currentThread().getName());
                order.add(value);
                return value;
            }));
        }
        queue.close(() -> order.add(100));
        for (int index = 0; index < 100; index++) {
            assertEquals(index, futures.get(index).get(1, TimeUnit.SECONDS));
            assertEquals(index, order.get(index));
        }
        assertEquals(100, order.getLast());
        assertTrue(queue.submit(() -> 1).isCompletedExceptionally());
    }

    @Test
    void failureDoesNotPreventFollowingOperations() throws Exception {
        StorageTaskQueue queue = new StorageTaskQueue(Logger.getAnonymousLogger());
        try {
            CompletableFuture<Integer> failed = queue.submit(() -> { throw new IllegalStateException("test"); });
            assertThrows(Exception.class, () -> failed.get(2, TimeUnit.SECONDS));
            assertEquals(42, queue.submit(() -> 42).get(2, TimeUnit.SECONDS));
        } finally {
            queue.close(() -> { });
        }
    }

    @Test
    void overloadIsRejectedWithoutBlockingTheCaller() throws Exception {
        StorageTaskQueue queue = new StorageTaskQueue(Logger.getAnonymousLogger());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            queue.submit(() -> {
                started.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    throw new AssertionError(exception);
                }
                return null;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 1024; index++) {
                assertFalse(queue.submit(() -> 1).isCompletedExceptionally());
            }
            assertTrue(queue.submit(() -> 2).isCompletedExceptionally());
        } finally {
            release.countDown();
            queue.close(() -> { });
        }
    }
}
