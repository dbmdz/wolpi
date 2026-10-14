package dev.mdz.wolpi.http;

import dev.mdz.wolpi.config.HttpTimeoutConfig.Limits;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.jspecify.annotations.Nullable;

/// Applies a default request timeout, preserving shorter explicit timeouts, and delegates to
/// the stock JDK HTTP client. The JDK has no client-wide default request-timeout setting.
///
/// Implemented as a subclass of [HttpClient] to force extensions that use
/// [dev.mdz.wolpi.extension.model.ExtensionGuestContext#httpClient()] to use the configured timeouts.
///
/// On Java 25, [HttpRequest.Builder#timeout(Duration)] only bounds the wait for response headers.
/// Slow, stalled, or trickling response bodies can still block indefinitely. This adapter adds
/// no body deadline or idle timeout. This is fixed from Java 26 to cover the response body, see
/// [JDK-8208693](https://bugs.openjdk.org/browse/JDK-8208693)
public final class TimeoutHttpClient extends HttpClient {
    private final HttpClient delegate;
    private final Duration requestTimeout;

    public TimeoutHttpClient(Limits limits) {
        this(
                HttpClient.newBuilder()
                        .version(Version.HTTP_2)
                        .connectTimeout(limits.connect())
                        .build(),
                limits.request());
    }

    TimeoutHttpClient(HttpClient delegate, Duration requestTimeout) {
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("HTTP request timeout must be positive");
        }
        this.delegate = delegate;
        this.requestTimeout = requestTimeout;
    }

    private HttpRequest withTimeout(HttpRequest request) {
        if (request.timeout()
                .filter(value -> value.compareTo(requestTimeout) <= 0)
                .isPresent()) return request;
        return HttpRequest.newBuilder(request, (name, value) -> true)
                .timeout(requestTimeout)
                .build();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return delegate.send(withTimeout(request), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return delegate.sendAsync(withTimeout(request), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.@Nullable PushPromiseHandler<T> pushHandler) {
        return delegate.sendAsync(withTimeout(request), handler, pushHandler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
        return delegate.newWebSocketBuilder();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public void shutdownNow() {
        delegate.shutdownNow();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(Duration duration) throws InterruptedException {
        return delegate.awaitTermination(duration);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
