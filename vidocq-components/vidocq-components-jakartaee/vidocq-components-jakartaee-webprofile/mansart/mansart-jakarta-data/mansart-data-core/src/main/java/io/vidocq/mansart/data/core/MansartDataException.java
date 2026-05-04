package io.vidocq.mansart.data.core;

public class MansartDataException extends RuntimeException {
    public MansartDataException(String message)                    { super(message); }
    public MansartDataException(String message, Throwable cause)   { super(message, cause); }
}
