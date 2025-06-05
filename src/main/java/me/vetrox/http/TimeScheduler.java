package me.vetrox.http;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TimeScheduler {

    private static final ThreadFactory namedThreadFactory = new NamedThreadFactory("007-executor-");
    private final ScheduledExecutorService scheduler;
    private final Map<Long, ScheduledTask> scheduledTasks = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong();

    public TimeScheduler(int corePoolSize) {
        scheduler = Executors.newScheduledThreadPool(corePoolSize, namedThreadFactory);
    }

    public long submit(Date executionTime, Runnable task) {
        long id = idGenerator.incrementAndGet();
        long delay = executionTime.getTime() - System.currentTimeMillis();
        ScheduledTask scheduledTask = new ScheduledTask(id, executionTime);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                task.run();
            } finally {
                scheduledTasks.remove(id);
            }
        }, delay, TimeUnit.MILLISECONDS);
        scheduledTask.setFuture(future);
        scheduledTasks.put(id, scheduledTask);
        return id;
    }

    public List<ScheduledTask> getScheduledTasks() {
        return scheduledTasks.values().stream().sorted().toList();
    }

    public ScheduledTask getScheduledTask(long id) {
        return scheduledTasks.get(id);
    }

    public static class ScheduledTask implements Comparable<ScheduledTask> {

        private final long id;
        private final Date executionTime;
        private ScheduledFuture<?> future;

        public ScheduledTask(long id, Date executionTime) {
            this.id = id;
            this.executionTime = executionTime;
        }

        public Date getExecutionTime() {
            return executionTime;
        }

        public long getId() {
            return id;
        }

        public ScheduledFuture<?> getFuture() {
            return future;
        }

        public void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Override
        public int compareTo(ScheduledTask other) {
            return this.executionTime.compareTo(other.executionTime);
        }
    }

}
