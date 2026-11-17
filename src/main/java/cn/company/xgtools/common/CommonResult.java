package cn.company.xgtools.common;

public record CommonResult<T>(boolean success, String message, T data) {
    public static <T> CommonResult<T> success(String message, T data) {
        return new CommonResult<>(true, message, data);
    }
    public static <T> CommonResult<T> failure(String message) {
        return new CommonResult<>(false, message, null);
    }
}
