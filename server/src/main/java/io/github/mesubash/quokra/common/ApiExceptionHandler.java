package io.github.mesubash.quokra.common;

import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

// Every error leaves the API in the same shape: { "error": { "code", "message", "details" } }.
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorBody(Error error) {
        public record Error(String code, String message, Map<String, String> details) {}
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> handle(ApiException e) {
        var response = ResponseEntity.status(e.status());
        if (e instanceof RateLimiter.RateLimitedException limited) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(limited.retryAfterSeconds()));
        }
        return response.body(body(e.code(), e.getMessage(), null));
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

    // e.g. ?status=NOPE or a malformed id in the path.
    @ExceptionHandler(TypeMismatchException.class)
    ResponseEntity<ErrorBody> handle(TypeMismatchException e) {
        String name = e.getPropertyName() == null ? "A parameter" : e.getPropertyName();
        return ResponseEntity.badRequest().body(body("VALIDATION_ERROR", name + " has an invalid value", null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> handle(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
            .body(body("FILE_TOO_LARGE", "Images can be up to 5 MB", null));
    }

    // Someone else changed the same row at the same moment (e.g. two agents acting on one chat).
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ErrorBody> handle(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(body("CONFLICT", "This was changed by someone else just now. Reload and try again.", null));
    }

    // Spring's own errors (unknown path, wrong method, missing parameter, ...) keep their status.
    // Anything else is a bug: log it and say little.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> handle(Exception e) {
        if (e instanceof ErrorResponse known) {
            HttpStatusCode status = known.getStatusCode();
            return ResponseEntity.status(status).body(body(codeFor(status), messageFor(status), null));
        }
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError().body(body("INTERNAL_ERROR", "Something went wrong", null));
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "VALIDATION_ERROR";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "BAD_REQUEST";
        };
    }

    private static String messageFor(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? "Request failed" : known.getReasonPhrase();
    }

    private static ErrorBody body(String code, String message, Map<String, String> details) {
        return new ErrorBody(new ErrorBody.Error(code, message, details));
    }
}
