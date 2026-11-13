package com.g1do.anvil.upload;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps a body that crossed the limit to {@code 413}, however the parser wrapped it. */
@RestControllerAdvice
public class RequestTooLargeHandler {

    @ExceptionHandler(RequestTooLargeException.class)
    ResponseEntity<Map<String, String>> tooLarge() {
        return response();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestTooLargeException) {
                return response();
            }
        }
        return ResponseEntity.badRequest().body(Map.of("error", "malformed request body"));
    }

    private static ResponseEntity<Map<String, String>> response() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Map.of("error", "request body exceeds " + UploadLimits.MAX_BYTES + " bytes"));
    }
}
