package com.example.kvstore.api;

import com.example.kvstore.api.response.ErrorResponse;
import com.example.kvstore.cluster.PeerUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Three cases worth a clean JSON body instead of the servlet container's default HTML error
 * page: a missing required query parameter (almost always a missing "key"), a forwarded call
 * to a peer that failed (network error, timeout, or its inbound semaphore was full), and
 * anything else unexpected. Everything else - 404/201/200/204/421/507 - is expressed directly
 * as a ResponseEntity in the controller and never reaches here.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(PeerUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePeerUnavailable(PeerUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
    }
}
