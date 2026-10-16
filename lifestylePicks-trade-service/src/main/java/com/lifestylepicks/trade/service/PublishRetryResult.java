package com.lifestylepicks.trade.service;

/** 同一次 Redis 原子调度返回的快照，时间以 Unix 毫秒表示。 */
public final class PublishRetryResult {
    private final long failureCount;
    private final long nextRetryAtMillis;
    private final long windowFailureCount;
    private final boolean alert;
    private final long pendingCount;

    public PublishRetryResult(long failureCount, long nextRetryAtMillis, long windowFailureCount,
                              boolean alert, long pendingCount) {
        this.failureCount = failureCount;
        this.nextRetryAtMillis = nextRetryAtMillis;
        this.windowFailureCount = windowFailureCount;
        this.alert = alert;
        this.pendingCount = pendingCount;
    }

    public boolean isScheduled() { return failureCount > 0; }
    public long getFailureCount() { return failureCount; }
    public long getNextRetryAtMillis() { return nextRetryAtMillis; }
    public long getWindowFailureCount() { return windowFailureCount; }
    public boolean isAlert() { return alert; }
    public long getPendingCount() { return pendingCount; }
}
