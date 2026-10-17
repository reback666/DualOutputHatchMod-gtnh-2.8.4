package com.gtnh.dualoutputhatchme.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import appeng.api.storage.data.IAEItemStack;
import appeng.me.GridAccessException;

class CacheFlushQueueTest {

    @Test
    void retriesAfterStorageBecomesAvailableWithoutNewOutput() throws GridAccessException {
        CacheFlushQueue<IAEItemStack> queue = new CacheFlushQueue<>();
        IAEItemStack cached = stack(128L);
        AtomicInteger changes = new AtomicInteger();
        queue.add(cached);

        assertEquals(1, queue.flush(64, input -> input, changes::incrementAndGet));
        assertEquals(128L, cached.getStackSize());
        assertFalse(queue.isEmpty());
        assertEquals(0, changes.get());

        assertEquals(1, queue.flush(64, input -> null, changes::incrementAndGet));
        assertEquals(0L, cached.getStackSize());
        assertTrue(queue.isEmpty());
        assertEquals(1, changes.get());
    }

    @Test
    void partialInsertionKeepsExactLongAmountForNextRound() throws GridAccessException {
        CacheFlushQueue<IAEItemStack> queue = new CacheFlushQueue<>();
        IAEItemStack cached = stack(13_922_304_600L);
        queue.add(cached);

        assertEquals(1, queue.flush(64, input -> {
            input.decStackSize(1_000L);
            return input;
        }, () -> {}));
        assertEquals(13_922_303_600L, cached.getStackSize());
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.flush(64, input -> null, () -> {}));
        assertTrue(queue.isEmpty());
    }

    @Test
    void budgetAndRotationPreventBlockedEntriesFromStarvingLaterOutputs() throws GridAccessException {
        CacheFlushQueue<IAEItemStack> queue = new CacheFlushQueue<>();
        Set<Long> visited = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            queue.add(stack(i + 1L));
        }
        AtomicInteger attempts = new AtomicInteger();
        CacheFlushQueue.Inserter<IAEItemStack> tracked = input -> {
            attempts.incrementAndGet();
            visited.add(input.getStackSize());
            return input;
        };

        assertEquals(64, queue.flush(64, tracked, () -> {}));
        assertEquals(64, attempts.get());
        assertEquals(64, queue.flush(64, tracked, () -> {}));
        assertEquals(128, attempts.get());
        assertEquals(100, visited.size());
    }

    @Test
    void repeatedOutputOfSameTypeDoesNotDuplicateQueueEntry() throws GridAccessException {
        CacheFlushQueue<IAEItemStack> queue = new CacheFlushQueue<>();
        IAEItemStack cached = stack(64L);
        queue.add(cached);
        cached.incStackSize(64L);
        queue.add(cached);
        AtomicInteger attempts = new AtomicInteger();

        assertEquals(1, queue.flush(64, input -> {
            attempts.incrementAndGet();
            assertEquals(128L, input.getStackSize());
            return input;
        }, () -> {}));
        assertEquals(1, attempts.get());
    }

    @Test
    void failedInsertionCannotMutateOrForgetRealCache() throws GridAccessException {
        CacheFlushQueue<IAEItemStack> queue = new CacheFlushQueue<>();
        IAEItemStack cached = stack(9_000_000_000L);
        queue.add(cached);

        // GridAccessException 的构造依赖完整 AE 配置；用普通异常验证同一退出路径。
        assertThrows(IllegalStateException.class, () -> queue.flush(64, input -> {
            input.setStackSize(1L);
            throw new IllegalStateException("Simulated insertion failure");
        }, () -> {}));
        assertEquals(9_000_000_000L, cached.getStackSize());
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.flush(64, input -> null, () -> {}));
        assertTrue(queue.isEmpty());
    }

    @Test
    void outputReceivedDuringInsertionIsNotOverwritten() throws GridAccessException {
        CacheFlushQueue<IAEItemStack> queue = new CacheFlushQueue<>();
        IAEItemStack cached = stack(64L);
        queue.add(cached);

        assertEquals(1, queue.flush(64, input -> {
            cached.incStackSize(32L);
            queue.add(cached);
            return null;
        }, () -> {}));
        assertEquals(32L, cached.getStackSize());
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.flush(64, input -> null, () -> {}));
        assertTrue(queue.isEmpty());
    }

    @Test
    void emptyCacheAndZeroBudgetDoNotAccessNetwork() throws GridAccessException {
        CacheFlushQueue<IAEItemStack> queue = new CacheFlushQueue<>();
        CacheFlushQueue.Inserter<IAEItemStack> unexpected = input -> {
            throw new AssertionError("Unexpected network access");
        };
        assertEquals(0, queue.flush(64, unexpected, () -> {}));
        queue.add(stack(64L));
        assertEquals(0, queue.flush(0, unexpected, () -> {}));
        assertFalse(queue.isEmpty());
    }

    @Test
    void drainedEntryCanBeQueuedAgainForNewOutput() throws GridAccessException {
        CacheFlushQueue<IAEItemStack> queue = new CacheFlushQueue<>();
        IAEItemStack cached = stack(64L);
        queue.add(cached);
        assertEquals(1, queue.flush(64, input -> null, () -> {}));
        assertTrue(queue.isEmpty());

        cached.incStackSize(32L);
        queue.add(cached);
        assertEquals(1, queue.flush(64, input -> null, () -> {}));
        assertEquals(0L, cached.getStackSize());
        assertTrue(queue.isEmpty());
    }

    @Test
    void longMaximumIsNotRoundedDuringPartialInsertion() throws GridAccessException {
        CacheFlushQueue<IAEItemStack> queue = new CacheFlushQueue<>();
        IAEItemStack cached = stack(Long.MAX_VALUE);
        queue.add(cached);

        assertEquals(1, queue.flush(64, input -> {
            input.decStackSize(1L);
            return input;
        }, () -> {}));
        assertEquals(Long.MAX_VALUE - 1L, cached.getStackSize());
        assertEquals(1, queue.flush(64, input -> null, () -> {}));
        assertEquals(0L, cached.getStackSize());
        assertTrue(queue.isEmpty());
    }

    @Test
    void clearedEntriesAreRemovedWithoutNetworkAccess() throws GridAccessException {
        CacheFlushQueue<IAEItemStack> queue = new CacheFlushQueue<>();
        IAEItemStack cached = stack(64L);
        queue.add(null);
        queue.add(stack(0L));
        queue.add(cached);
        cached.setStackSize(0L);

        assertEquals(0, queue.flush(64, input -> { throw new AssertionError("Unexpected network access"); }, () -> {}));
        assertTrue(queue.isEmpty());
    }

    private static IAEItemStack stack(long amount) {
        return (IAEItemStack) Proxy.newProxyInstance(
            CacheFlushQueueTest.class.getClassLoader(),
            new Class<?>[] { IAEItemStack.class },
            new StackState(amount));
    }

    private static final class StackState implements InvocationHandler {

        private long amount;

        private StackState(long amount) {
            this.amount = amount;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getStackSize":
                    return this.amount;
                case "setStackSize":
                    this.amount = (long) args[0];
                    return proxy;
                case "incStackSize":
                    this.amount += (long) args[0];
                    return null;
                case "decStackSize":
                    this.amount -= (long) args[0];
                    return null;
                case "copy":
                    return stack(this.amount);
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "Stack(" + this.amount + ")";
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }
    }
}
