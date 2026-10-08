package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.config.WolpiConfig.ExtensionTimeouts.HttpTimeouts;
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

/// HTTP client exposed to extensions in both languages. Applies the configured request deadline
/// even when the extension builds an HttpRequest without a timeout.
public final class ExtensionHttpClient extends HttpClient {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient delegate;
    private final Duration requestTimeout;

    public ExtensionHttpClient(@Nullable HttpTimeouts timeouts) {
        this.requestTimeout = requirePositive(
                timeouts == null || timeouts.request() == null ? DEFAULT_REQUEST_TIMEOUT : timeouts.request());
        this.delegate = HttpClient.newBuilder()
                .version(Version.HTTP_2)
                .connectTimeout(
                        timeouts == null || timeouts.connect() == null ? DEFAULT_CONNECT_TIMEOUT : timeouts.connect())
                .build();
    }

    ExtensionHttpClient(HttpClient delegate, Duration requestTimeout) {
        this.requestTimeout = requirePositive(requestTimeout);
        this.delegate = delegate;
    }

    private static Duration requirePositive(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Extension HTTP request timeout must be positive");
        }
        return timeout;
    }

    private HttpRequest withTimeout(HttpRequest request) {
        if (request.timeout()
                .filter(timeout -> timeout.compareTo(requestTimeout) <= 0)
                .isPresent()) {
            return request;
        }
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
            HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler) {
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
