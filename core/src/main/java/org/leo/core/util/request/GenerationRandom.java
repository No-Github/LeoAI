package org.leo.core.util.request;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 生成过程的线程级随机源。未显式设置 seed 时保持原有 ThreadLocalRandom 行为；
 * 设置 seed 后，同一作用域内的随机序列可重复，便于复现生成问题。
 */
public final class GenerationRandom {

    private static final ThreadLocal<Random> SEEDED = new ThreadLocal<Random>();

    private GenerationRandom() {
    }

    public static Random current() {
        Random random = SEEDED.get();
        return random != null ? random : ThreadLocalRandom.current();
    }

    public static boolean isSeeded() {
        return SEEDED.get() != null;
    }

    public static Scope withSeed(long seed) {
        Random previous = SEEDED.get();
        SEEDED.set(new Random(seed));
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final Random previous;
        private boolean closed;

        private Scope(Random previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                SEEDED.remove();
            } else {
                SEEDED.set(previous);
            }
        }
    }
}
