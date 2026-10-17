package com.gtnh.dualoutputhatchme.api;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import appeng.api.storage.data.IAEStack;
import appeng.me.GridAccessException;

/** 只保存 AE 缓存中条目的引用；轮转未完成的条目，避免满网或大缓存饿死后面的输出。 */
public final class CacheFlushQueue<T extends IAEStack<T>> {

    private final ArrayDeque<T> entries = new ArrayDeque<>();
    // AE 条目的数量会变化，不能用依赖内容的 equals/hashCode 判断是否已经入队。
    private final Set<T> queued = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());

    public void add(T entry) {
        if (entry != null && entry.getStackSize() > 0L && this.queued.add(entry)) {
            this.entries.addLast(entry);
        }
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /** 每轮每种条目最多尝试一次；返回实际调用 AE 的次数，供物品与流体共享预算。 */
    public int flush(int budget, Inserter<T> inserter, Runnable onChanged) throws GridAccessException {
        int limit = Math.min(Math.max(0, budget), this.entries.size());
        int operations = 0;
        for (int i = 0; i < limit; i++) {
            T entry = this.entries.getFirst();
            long before = entry.getStackSize();
            if (before > 0L) {
                // GT 的流体插入方法会修改传入栈；网络异常时不能让它改坏真实缓存。
                T remainder = inserter.insert(entry.copy());
                long remaining = remainder == null ? 0L : remainder.getStackSize();
                operations++;
                if (remaining != before) {
                    entry.decStackSize(before - remaining);
                    onChanged.run();
                }
            }
            this.entries.removeFirst();
            if (entry.getStackSize() > 0L) {
                this.entries.addLast(entry);
            } else {
                this.queued.remove(entry);
            }
        }
        return operations;
    }

    @FunctionalInterface
    public interface Inserter<T> {

        T insert(T stack) throws GridAccessException;
    }
}
