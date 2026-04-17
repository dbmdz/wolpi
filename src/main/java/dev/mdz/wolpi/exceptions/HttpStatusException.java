package dev.mdz.wolpi.exceptions;

import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import java.util.Map;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/// Thrown by extensions to indicate that the user should receive a HTTP response with
/// a specific status code and message, and optional details object or response headers.
public class HttpStatusException extends ExtensionExecutionException {
    /// Status code to return to the client
    private final int httpStatusCode;
    /// Extra detail that should be put into the JSON response payload
    private final @Nullable Map<String, Object> details;
    /// Extra response headers that should be sent to the client
    private final @Nullable HttpHeaders responseHeaders;

    public static @Nullable HttpStatusException fromGuestException(PolyglotException pe) {
        var obj = pe.getGuestObject();
        if (obj == null || obj.isNull()) {
            return null;
        }
        var message = PolyglotHelpers.getDictOrObjectMember("message", obj);
        var status = PolyglotHelpers.getDictOrObjectMember("status", obj);
        var details = PolyglotHelpers.getDictOrObjectMember("details", obj);
        if (message != null && status != null) {
            var typeRef = new TypeLiteral<Map<String, Object>>() {};
            return new HttpStatusException(
                    message.asString(), status.asInt(), details != null ? details.as(typeRef) : null);
        }
        return null;
    }

    public HttpStatusException(String message, int httpStatusCode, @Nullable Map<String, Object> details) {
        this(message, httpStatusCode, details, null);
    }

    public HttpStatusException(
            String message,
            int httpStatusCode,
            @Nullable Map<String, Object> details,
            @Nullable HttpHeaders responseHeaders) {
        super(message);
        this.httpStatusCode = httpStatusCode;
        this.details = details;
        this.responseHeaders = responseHeaders;
    }

    public String message() {
        return super.getMessage();
    }

    public int httpStatusCode() {
        return httpStatusCode;
    }

    public @Nullable Map<String, Object> details() {
        return details;
    }

    public @Nullable HttpHeaders responseHeaders() {
        return responseHeaders;
    }
}
