package com.lifestylepicks.api.exception;

public class RemoteCallException extends RuntimeException {
    public RemoteCallException(String message) { super(message); }
    public RemoteCallException(String message, Throwable cause) { super(message, cause); }
}
