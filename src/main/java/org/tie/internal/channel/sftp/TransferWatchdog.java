package org.tie.internal.channel.sftp;

import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpProgressMonitor;
import java.time.Duration;

/**
 * Aborts a transfer that makes no progress for {@code timeout}: JSch retries socket read timeouts, so
 * a stalled download or upload would otherwise wait forever. When it expires it disconnects the
 * session, which makes the pending SFTP call fail.
 */
final class TransferWatchdog implements SftpProgressMonitor, AutoCloseable {

    private final long timeoutNanos;
    private final Thread thread;
    private volatile long lastProgress = System.nanoTime();
    private volatile boolean done;
    private volatile boolean expired;

    TransferWatchdog(Session session, Duration timeout) {
        this.timeoutNanos = timeout.toNanos();
        long pollMillis = Math.max(10, Math.min(250, timeout.toMillis() / 4));
        this.thread = Thread.ofPlatform().daemon().name("tie-sftp-watchdog").start(() -> {
            while (!done) {
                try {
                    Thread.sleep(pollMillis);
                } catch (InterruptedException e) {
                    return;
                }
                if (!done && System.nanoTime() - lastProgress > timeoutNanos) {
                    expired = true;
                    session.disconnect();
                    return;
                }
            }
        });
    }

    boolean expired() {
        return expired;
    }

    @Override
    public void init(int op, String src, String dest, long max) {
        lastProgress = System.nanoTime();
    }

    @Override
    public boolean count(long count) {
        lastProgress = System.nanoTime();
        return true;
    }

    @Override
    public void end() {
        lastProgress = System.nanoTime();
    }

    @Override
    public void close() {
        done = true;
        thread.interrupt();
    }
}
