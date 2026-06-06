package io.vidocq.runtime.logging;

import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

public class StdoutHandler extends StreamHandler {

    public StdoutHandler() {
        super(System.out, new CompactFormatter());
    }

    @Override
    public synchronized void publish(LogRecord record) {
        super.publish(record);
        flush(); // StreamHandler does not automatically flush
    }

    @Override
    public synchronized void close() {
        flush();
    }
}