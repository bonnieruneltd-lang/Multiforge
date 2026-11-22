package com.multiforge.protocol.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small fixed-size executor with a bounded queue and an explicit "busy" answer.
 *
 * <p>The chunk compression path needs to move work off the server thread without
 * ever dropping a reply: if the executor is saturated or already shut down the
 * caller must run the task itself. {@link #execute(Runnable)} therefore returns
 * {@code false} instead of throwing, so the caller can fall back to an inline
 * execution and the shutdown contract stays simple.</p>
 *
 * <p>Threading: every method is safe from any thread. Threads are daemon threads
 * so a stuck task can never keep the JVM alive after the server stopped.</p>
 */
public final class BoundedTaskExecutor implements AutoCloseable {

    private final ThreadPoolExecutor executor;
    private final String name;
    private final int queueCapacity;

    /**
     * @param threads          number of worker threads, at least one
     * @param queueCapacity    maximum number of queued tasks
     * @param threadNamePrefix prefix for the worker thread names
     */
    public BoundedTaskExecutor(int threads, int queueCapacity, String threadNamePrefix) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least one");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least one");
        }
        this.name = threadNamePrefix;
        this.queueCapacity = queueCapacity;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, threadNamePrefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory);
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Tries to queue a task.
     *
     * @param task work to run; must be safe on a background thread
     * @return {@code true} when the task was queued, {@code false} when the
     *         executor is saturated or shut down and the caller must run it
     */
    public boolean execute(Runnable task) {
        if (executor.isShutdown()) {
            return false;
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** @return number of tasks waiting for a worker */
    public int queued() {
        return executor.getQueue().size();
    }

    /** @return {@code true} once {@link #close(long)} started */
    public boolean isShutdown() {
        return executor.isShutdown();
    }

    /** Default drain timeout for {@link #close()}. */
    private static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 30_000L;

    /**
     * Stops accepting tasks and waits up to {@value #DEFAULT_CLOSE_TIMEOUT_MILLIS} ms
     * for the queued work to finish.
     */
    @Override
    public void close() {
        close(DEFAULT_CLOSE_TIMEOUT_MILLIS);
    }

    /**
     * Stops accepting tasks and waits for the queued work to finish.
     *
     * <p>Queued tasks are drained rather than discarded: they hold chunk replies
     * that were already promised to a peer. Only when the timeout elapses are the
     * remaining tasks interrupted.</p>
     *
     * @param timeoutMillis maximum time to wait for the queue to drain
     */
    public void close(long timeoutMillis) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "BoundedTaskExecutor[" + name + ", threads=" + executor.getCorePoolSize()
                + ", queued=" + queued() + ", capacity=" + queueCapacity + ']';
    }
}
