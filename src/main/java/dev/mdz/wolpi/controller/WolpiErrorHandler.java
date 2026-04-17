package dev.mdz.wolpi.controller;

import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.exceptions.HttpStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/// Global exception handler that catches all unhandled exceptions in controllers and provides
/// a nicer error log message than the default stack trace dump by Spring/Tomcat.
///
/// We subclass [ResponseEntityExceptionHandler] to not interfere with Spring's default exception
/// handling for known exception types.
@ControllerAdvice
public class WolpiErrorHandler extends ResponseEntityExceptionHandler {
    private static final Set<String> FORWARDED_HEADERS = Set.of(HttpHeaders.ETAG, HttpHeaders.LAST_MODIFIED);

    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final boolean logDetails;
    private final boolean corsEnabled;

    public WolpiErrorHandler(WolpiConfig config) {
        this.logDetails = config.logging() == null || config.logging().logRequestDetailsOnCrash();
        this.corsEnabled = config.iiif().features().cors();
    }

    @ExceptionHandler(HttpStatusException.class)
    public ResponseEntity<?> handleHttpStatusError(HttpStatusException ex, HttpServletRequest req) {
        if (ex.responseHeaders() != null) {
            // Forward some headers from the backend response to the client (ETag/Last-Modified)
            HttpHeaders headers = new HttpHeaders();
            FORWARDED_HEADERS.stream()
                    .filter(h -> ex.responseHeaders().containsHeader(h))
                    .forEach(h -> headers.set(h, ex.responseHeaders().getFirst(h)));
            // Set CORS on the response if activated
            if (corsEnabled) {
                headers.setAccessControlAllowOrigin(java.util.Optional.ofNullable(req.getHeader(HttpHeaders.ORIGIN))
                        .orElse("*"));
            }
            return ResponseEntity.status(HttpStatus.valueOf(ex.httpStatusCode()))
                    .headers(headers)
                    .body(null);
        }
        Map<String, Object> body =
                ex.details() != null ? ex.details() : Collections.singletonMap("message", ex.message());
        return ResponseEntity.status(HttpStatus.valueOf(ex.httpStatusCode())).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleAllUncaughtExceptions(Exception ex, HttpServletRequest request) {
        StringBuilder reqLine = new StringBuilder();
        reqLine.append(request.getMethod()).append(" ");
        reqLine.append(request.getRequestURI());
        if (request.getQueryString() != null) {
            reqLine.append("?").append(request.getQueryString());
        }
        if (logDetails) {
            log.error(
                    "Unhandled exception while processing '{}' from {} with headers [{}]",
                    reqLine,
                    request.getRemoteAddr(),
                    formatHeadersForLogging(request),
                    ex);
        } else {
            log.error("Unhandled exception while processing '{}'.", reqLine, ex);
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An internal error occurred.");
    }

    private String formatHeadersForLogging(HttpServletRequest request) {
        List<String> entries = new ArrayList<>();
        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            List<String> headerValues = Collections.list(request.getHeaders(headerName));
            entries.add("%s: %s"
                    .formatted(
                            headerName,
                            headerValues.stream().map("\"%s\""::formatted).collect(Collectors.joining(", "))));
        }
        return String.join("; ", entries);
    }
}
