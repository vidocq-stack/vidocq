package io.vidocq.mpserver.logging;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class CompactFormatter extends Formatter {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    @Override
    public String format(LogRecord record) {
        String time = TIME_FMT.format(record.getInstant());
        String level = record.getLevel().getName();
        String thread = Thread.currentThread().getName();
        String logger = shortenName(record.getLoggerName(), 36);
        String message = formatMessage(record);

        var sb = new StringBuilder(128)
                .append(time)
                .append(" [").append(thread).append("] ")
                .append(padRight(level, 7))
                .append(' ')
                .append(logger)
                .append(" - ")
                .append(message)
                .append('\n');

        if (record.getThrown() != null) {
            var sw = new java.io.StringWriter();
            record.getThrown().printStackTrace(new java.io.PrintWriter(sw));
            sb.append(sw);
        }
        return sb.toString();
    }

    // io.vidocq.mpserver.http.EmbeddedServer → i.v.s.h.EmbeddedServer
    private static String shortenName(String name, int maxLen) {
        if (name == null) return "";
        if (name.length() <= maxLen) return name;

        String[] parts = name.split("\\.");
        var sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            sb.append(parts[i].charAt(0)).append('.');
        }
        sb.append(parts[parts.length - 1]);
        return sb.toString();
    }

    private static String padRight(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}