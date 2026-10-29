package io.github.mesubash.quokra.common;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorBody(Error error) {
        public record Error(String code, String message, Map<String, String> details) {}
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(body(e.code(), e.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> handle(MethodArgumentNotValidException e) {
        Map<String, String> fields = e.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(f -> f.getField(), f -> String.valueOf(f.getDefaultMessage()), (a, b) -> a));
        return ResponseEntity.badRequest().body(body("VALIDATION_ERROR", "Invalid request", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> handle(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(body("VALIDATION_ERROR", "Request body is missing or not valid JSON", null));
    }

    private static ErrorBody body(String code, String message, Map<String, String> details) {
        return new ErrorBody(new ErrorBody.Error(code, message, details));
    }
}
