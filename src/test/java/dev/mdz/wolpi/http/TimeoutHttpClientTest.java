package dev.mdz.wolpi.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class TimeoutHttpClientTest {
    @ParameterizedTest
    @CsvSource({"0,5", "2,2", "5,5", "10,5"})
    void appliesDefaultAndCapsExplicitTimeoutsForAllSendMethods(int explicitSeconds, int expectedSeconds)
            throws Exception {
        var delegate = mock(HttpClient.class);
        var builder = HttpRequest.newBuilder(URI.create("http://localhost/test"));
        if (explicitSeconds > 0) builder.timeout(Duration.ofSeconds(explicitSeconds));
        var request = builder.build();
        var handler = HttpResponse.BodyHandlers.discarding();
        HttpResponse.PushPromiseHandler<Void> pushHandler = (initial, pushed, acceptor) -> {};
        try (var client = new TimeoutHttpClient(delegate, Duration.ofSeconds(5))) {
            client.send(request, handler);
            client.sendAsync(request, handler);
            client.sendAsync(request, handler, pushHandler);
        }
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).send(captured.capture(), same(handler));
        verify(delegate).sendAsync(captured.capture(), same(handler));
        verify(delegate).sendAsync(captured.capture(), same(handler), same(pushHandler));
        assertThat(captured.getAllValues()).hasSize(3).allSatisfy(sent -> assertThat(sent.timeout())
                .contains(Duration.ofSeconds(expectedSeconds)));
    }
}
