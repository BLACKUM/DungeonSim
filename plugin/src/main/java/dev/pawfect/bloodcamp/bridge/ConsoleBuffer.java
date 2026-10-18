package dev.pawfect.bloodcamp.bridge;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

public final class ConsoleBuffer extends AbstractAppender {
    private static final Pattern FORMATTING = Pattern.compile("\u001B\\[[;\\d]*m|§[0-9a-fk-orx]", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public record Line(long seq, String text) {}

    private final Line[] ring;
    private long nextSeq = 1;

    private ConsoleBuffer(int capacity) {
        super("BloodcampBridge", null, null, true, Property.EMPTY_ARRAY);
        this.ring = new Line[capacity];
    }

    public static ConsoleBuffer attach(int capacity) {
        ConsoleBuffer buffer = new ConsoleBuffer(capacity);
        buffer.start();
        ((Logger) LogManager.getRootLogger()).addAppender(buffer);
        return buffer;
    }

    public void detach() {
        ((Logger) LogManager.getRootLogger()).removeAppender(this);
        stop();
    }

    @Override
    public void append(LogEvent event) {
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        String logger = event.getLoggerName();
        String source = logger == null || logger.isEmpty() ? "" : " [" + logger.substring(logger.lastIndexOf('.') + 1) + "]";
        StringBuilder text = new StringBuilder()
                .append('[').append(TIME.format(Instant.ofEpochMilli(event.getTimeMillis())))
                .append(' ').append(event.getLevel()).append(']')
                .append(source).append(' ')
                .append(FORMATTING.matcher(message).replaceAll(""));
        if (event.getThrown() != null) {
            text.append("\n").append(event.getThrown());
            for (StackTraceElement element : event.getThrown().getStackTrace()) {
                text.append("\n\tat ").append(element);
            }
        }
        synchronized (this) {
            ring[(int) (nextSeq % ring.length)] = new Line(nextSeq, text.toString());
            nextSeq++;
        }
    }

    public synchronized List<Line> since(long after, int max) {
        long oldest = Math.max(1, nextSeq - ring.length);
        long from = Math.max(Math.max(after + 1, oldest), nextSeq - max);
        List<Line> lines = new ArrayList<>();
        for (long seq = from; seq < nextSeq; seq++) {
            lines.add(ring[(int) (seq % ring.length)]);
        }
        return lines;
    }

    public synchronized long lastSeq() {
        return nextSeq - 1;
    }
}
