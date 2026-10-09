package dev.mdz.wolpi.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.mdz.wolpi.config.WolpiConfig.ExtensionTimeouts;
import dev.mdz.wolpi.config.WolpiConfig.ExtensionTimeouts.HttpTimeouts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ExtensionHttpClientTest {
    private final HttpClient delegate = mock(HttpClient.class);
    private final ExtensionHttpClient client = new ExtensionHttpClient(delegate, Duration.ofMillis(500));
    private final HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();

    @Test
    void shouldBindLanguageIndependentHttpSettings() {
        var source = new MapConfigurationPropertySource(Map.of(
                "wolpi.extension-timeouts.http.connect", "250ms",
                "wolpi.extension-timeouts.http.request", "750ms"));
        var settings = new Binder(source)
                .bind("wolpi.extension-timeouts", ExtensionTimeouts.class)
                .get();
        assertThat(settings.http().connect()).isEqualTo(Duration.ofMillis(250));
        assertThat(settings.http().request()).isEqualTo(Duration.ofMillis(750));
    }

    @Test
    void shouldUseDefaultsForOmittedSettings() {
        var source = new MapConfigurationPropertySource(Map.of("wolpi.extension-timeouts.http.connect", "250ms"));
        var settings = new Binder(source)
                .bind("wolpi.extension-timeouts", ExtensionTimeouts.class)
                .get();
        assertThat(settings.http().request()).isEqualTo(Duration.ofSeconds(30));
        try (var defaults = new ExtensionHttpClient(null);
                var configured =
                        new ExtensionHttpClient(new HttpTimeouts(Duration.ofMillis(250), Duration.ofSeconds(1)))) {
            assertThat(defaults.connectTimeout()).contains(Duration.ofSeconds(10));
            assertThat(configured.connectTimeout()).contains(Duration.ofMillis(250));
        }
    }

    @Test
    void shouldApplyTimeoutWithoutChangingRequestData() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost/test"))
                .header("X-Test", "first")
                .header("X-Test", "second")
                .POST(HttpRequest.BodyPublishers.ofString("payload"))
                .expectContinue(true)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        client.send(request, handler);
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).send(captured.capture(), same(handler));
        var bounded = captured.getValue();
        assertThat(bounded.timeout()).contains(Duration.ofMillis(500));
        assertThat(bounded.uri()).isEqualTo(request.uri());
        assertThat(bounded.method()).isEqualTo(request.method());
        assertThat(bounded.headers()).isEqualTo(request.headers());
        assertThat(bounded.bodyPublisher()).isEqualTo(request.bodyPublisher());
        assertThat(bounded.expectContinue()).isTrue();
        assertThat(bounded.version()).isEqualTo(request.version());
        assertThat(request.timeout()).isEmpty();
    }

    @Test
    void shouldPreserveShorterExplicitTimeout() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost/test"))
                .timeout(Duration.ofMillis(100))
                .build();
        client.send(request, handler);
        verify(delegate).send(same(request), same(handler));
    }

    @Test
    void shouldCapLongerExplicitTimeout() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost/test"))
                .timeout(Duration.ofSeconds(5))
                .build();
        client.send(request, handler);
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).send(captured.capture(), same(handler));
        assertThat(captured.getValue().timeout()).contains(Duration.ofMillis(500));
        assertThat(request.timeout()).contains(Duration.ofSeconds(5));
    }

    @Test
    void shouldApplyTimeoutToBothAsyncOverloads() {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost/test")).build();
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        HttpResponse.PushPromiseHandler<String> pushHandler = (initial, pushed, acceptor) -> {};
        client.sendAsync(request, handler);
        client.sendAsync(request, handler, pushHandler);
        verify(delegate).sendAsync(captured.capture(), same(handler));
        assertThat(captured.getValue().timeout()).contains(Duration.ofMillis(500));
        verify(delegate).sendAsync(captured.capture(), same(handler), same(pushHandler));
        assertThat(captured.getValue().timeout()).contains(Duration.ofMillis(500));
    }

    @Test
    void shouldDelegateLifecycleOperations() throws Exception {
        client.shutdown();
        client.shutdownNow();
        client.isTerminated();
        client.awaitTermination(Duration.ofSeconds(1));
        client.close();
        verify(delegate).shutdown();
        verify(delegate).shutdownNow();
        verify(delegate).isTerminated();
        verify(delegate).awaitTermination(Duration.ofSeconds(1));
        verify(delegate).close();
    }

    @Test
    void shouldRejectNonPositiveRequestTimeouts() {
        assertThatThrownBy(() -> new ExtensionHttpClient(delegate, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtensionHttpClient(delegate, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
