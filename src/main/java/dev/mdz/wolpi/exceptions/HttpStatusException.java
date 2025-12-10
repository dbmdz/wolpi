package dev.mdz.wolpi.exceptions;

import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import java.util.Map;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.TypeLiteral;
import org.jspecify.annotations.Nullable;

/// Thrown by extensions to indicate that the user should recive a HTTP response with
/// a specific status code and message and an optional details object.
public class HttpStatusException extends ExtensionExecutionException {
    private final int httpStatusCode;
    private final @Nullable Map<String, Object> details;

    public static @Nullable HttpStatusException fromGuestException(PolyglotException pe) {
        var obj = pe.getGuestObject();
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
        super(message);
        this.httpStatusCode = httpStatusCode;
        this.details = details;
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
}
