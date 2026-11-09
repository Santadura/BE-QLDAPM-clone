package com.example.demo.exception;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Regression: a domain/saga guard's 409 must never become a generic 500. */
class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler=new GlobalExceptionHandler();

    @Test void writerClaimConflictPreserves409InsteadOfGeneric500() {
        var result=handler.handleResponseStatus(new ResponseStatusException(
            HttpStatus.CONFLICT,"Core assignment writes must be QUIESCED"));
        assertEquals(HttpStatus.CONFLICT,result.getStatusCode());
        assertEquals(409,result.getBody().get("status"));
        assertEquals("Core assignment writes must be QUIESCED",
            result.getBody().get("message"));
    }

    @Test void internalServiceKeyFailurePreserves401() {
        var result=handler.handleResponseStatus(new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,"Invalid service key"));
        assertEquals(HttpStatus.UNAUTHORIZED,result.getStatusCode());
        assertEquals("Unauthorized",result.getBody().get("error"));
    }

    @Test void unavailableReferenceServiceRemains503() {
        var result=handler.handleResponseStatus(new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Projection unavailable"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,result.getStatusCode());
        assertEquals(503,result.getBody().get("status"));
    }

    @Test void unexpectedErrorsRemain500() {
        var result=handler.handleGeneric(new IllegalStateException("Unexpected failure"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,result.getStatusCode());
    }
}
