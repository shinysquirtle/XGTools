package cn.company.xgtools.common;

public class MyServiceException extends RuntimeException {
    public MyServiceException(String message) { super(message); }
    public MyServiceException(String message, Throwable cause) { super(message, cause); }
}
