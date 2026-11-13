package com.g1do.anvil.upload;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Maps a body that crossed the limit to {@code 413}; other errors keep their normal handling. */
@RestControllerAdvice
public class RequestTooLargeHandler {

    @ExceptionHandler(RequestTooLargeException.class)
    ResponseEntity<Map<String, String>> tooLarge() {
        return response();
    }

    /** A streamed multipart body is enforced by the servlet multipart limit, not the input stream. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> multipartTooLarge() {
        return response();
    }

    private static ResponseEntity<Map<String, String>> response() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Map.of("error", "request body exceeds " + UploadLimits.MAX_BYTES + " bytes"));
    }
}
