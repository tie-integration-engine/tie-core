package org.tie.fixtures;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures every log event of the root logger at a given level, until closed. */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final Level previous = root.getLevel();
    private final Logger sshd = (Logger) LoggerFactory.getLogger("org.apache.sshd");
    private final Level previousSshd = sshd.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture(Level level) {
        appender.start();
        root.addAppender(appender);
        root.setLevel(level);
        sshd.setLevel(level);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** Every captured line: level, logger, formatted message and throwable messages. */
    public List<String> lines() {
        return events().stream().map(event -> {
            StringBuilder line = new StringBuilder(event.getLevel() + " " + event.getLoggerName() + " "
                    + event.getFormattedMessage());
            for (var thrown = event.getThrowableProxy(); thrown != null; thrown = thrown.getCause()) {
                line.append(" | ").append(thrown.getMessage());
            }
            return line.toString();
        }).toList();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(previous);
        sshd.setLevel(previousSshd);
    }
}
