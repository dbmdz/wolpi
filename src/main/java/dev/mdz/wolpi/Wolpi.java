package dev.mdz.wolpi;

import app.photofox.vipsffm.Vips;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.ExtensionRegistry;
import dev.mdz.wolpi.extension.ExtensionRuntime;
import dev.mdz.wolpi.extension.ExtensionRuntime.ExtensionRuntimeImpl;
import dev.mdz.wolpi.extension.GraalContextSupplier;
import dev.mdz.wolpi.extension.RuntimeContext;
import dev.mdz.wolpi.extension.RuntimeContextPooledObjectFactory;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.metrics.WolpiMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.commons.pool2.KeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
@EnableConfigurationProperties(WolpiConfig.class)
@EnableWebMvc
@Configuration(proxyBeanMethods = false)
public class Wolpi implements WebMvcConfigurer {

    /// Creates a new FFM Memory [Arena] for use by libvips
    /// This bean is request-scoped because a confined [Arena] is not thread-safe (and a shared Arena
    /// has too much runtime overhead) and should be confined to the lifetime of a single request
    /// handler thread. Since we can't easily attach cleanup hooks to the request handler threads, we
    /// opt for a request-scoped bean.
    @Bean(destroyMethod = "close")
    @RequestScope
    public Arena vipsArena() {
        return Arena.ofConfined();
    }

    @Bean(destroyMethod = "close")
    @RequestScope
    public ExtensionRuntime extensionRuntime(
            ExtensionRegistry registry,
            @Qualifier("contextPool") KeyedObjectPool<LoadedExtension, RuntimeContext> ctxPool,
            @Qualifier("extensionThreadPool") ExecutorService threadPool,
            WolpiMetrics metrics) {
        return new ExtensionRuntimeImpl(registry, ctxPool, threadPool, metrics);
    }

    @PreDestroy
    public void shutdownHook() {
        Vips.shutdown();
    }

    /// Pool of [RuntimeContext]s for each [LoadedExtension] to be reused across requests.
    @Bean("contextPool")
    public GenericKeyedObjectPool<LoadedExtension, RuntimeContext> extensionContextPool(
            GenericKeyedObjectPoolConfig<RuntimeContext> cfg, GraalContextSupplier contextSupplier) {
        return new GenericKeyedObjectPool<>(new RuntimeContextPooledObjectFactory(contextSupplier), cfg);
    }

    @Bean
    GenericKeyedObjectPoolConfig<RuntimeContext> poolConfig(WolpiConfig wolpiConfig) {
        var cfg = new GenericKeyedObjectPoolConfig<RuntimeContext>();

        // Default minIdle to CPU core count if not explicitly configured
        var minIdle = wolpiConfig.extensionPool().minIdle() != null
                ? wolpiConfig.extensionPool().minIdle()
                : Runtime.getRuntime().availableProcessors();
        var maxIdle = wolpiConfig.extensionPool().maxIdle() != null
                ? wolpiConfig.extensionPool().maxIdle()
                : 2 * minIdle;
        var maxTotal = wolpiConfig.extensionPool().maxTotal() != null
                ? wolpiConfig.extensionPool().maxTotal()
                : 2 * maxIdle;

        cfg.setMinIdlePerKey(minIdle);
        cfg.setMaxIdlePerKey(maxIdle);
        cfg.setMaxTotalPerKey(maxTotal);

        // Configure eviction to free up memory when load decreases, while keeping
        // at least minIdle contexts warm to avoid expensive recompilation.
        // - Hard limit (minEvictableIdleTime): Practically never evict below minIdle
        // - Soft limit (softMinEvictableIdleTime): Evict contexts above minIdle after configured timeout
        // - Eviction runs every 2 minutes to check for stale contexts
        cfg.setMinEvictableIdleDuration(Duration.ofDays(365));
        cfg.setSoftMinEvictableIdleDuration(wolpiConfig.extensionPool().evictionTimeout());
        cfg.setTimeBetweenEvictionRuns(Duration.ofMinutes(2));

        // Disable bean self-registration via JMX
        cfg.setJmxEnabled(false);
        return cfg;
    }

    /// Pool to run extension code in parallel, used for auth and resolving to reduce latency.
    @Bean("extensionThreadPool")
    public ThreadPoolExecutor extensionThreadPool(
            WolpiConfig wolpiConfig, GenericKeyedObjectPoolConfig<RuntimeContext> poolConfig) {
        return new ThreadPoolExecutor(
                // coreSize = 1 thread per extension
                wolpiConfig.extensions().size(),
                // maxSize = 1 thread per total number of contexts.
                Math.max(1, wolpiConfig.extensions().size() * poolConfig.getMaxTotalPerKey()),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("extension-thread-" + t.threadId());
                    return t;
                });
    }

    /// Create a new HttpClient using HTTP/2 for the application.
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
    }

    @Bean
    public JsonMapperBuilderCustomizer jacksonCustomizer() {
        return jsonMapperBuilder ->
                jsonMapperBuilder.changeDefaultPropertyInclusion(inc -> inc.withContentInclusion(Include.NON_NULL));
    }

    /// Register the conversion service needed for case-insensitive enum conversion
    @Override
    public void addFormatters(FormatterRegistry registry) {
        ApplicationConversionService.configure(registry);
    }

    /// Add a new write-only [HttpMessageConverter] that can write ByteBuffers directly to the
    /// response body.
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.addCustomConverter(new AbstractHttpMessageConverter<ByteBuffer>(MediaType.ALL) {
            @Override
            public boolean canRead(Class<?> clazz, @Nullable MediaType mediaType) {
                return false;
            }

            @Override
            protected boolean supports(Class<?> clazz) {
                return ByteBuffer.class.isAssignableFrom(clazz);
            }

            @Override
            protected ByteBuffer readInternal(Class<? extends ByteBuffer> clazz, HttpInputMessage inputMessage)
                    throws IOException, HttpMessageNotReadableException {
                // Not implemented for writing-only converter.
                throw new UnsupportedOperationException();
            }

            @Override
            protected void writeInternal(ByteBuffer byteBuffer, HttpOutputMessage outputMessage)
                    throws IOException, HttpMessageNotWritableException {
                final OutputStream outputStream = outputMessage.getBody();
                try (WritableByteChannel channel = Channels.newChannel(outputStream)) {
                    channel.write(byteBuffer);
                }
            }
        });
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setProperty("relaxedPathChars", "^");
            connector.setProperty("relaxedQueryChars", "^");
        });
    }

    @Bean
    public MeterFilter ignoreMonitoringEndpoints() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (id.getName().equals("http.server.requests")) {
                    String uri = id.getTag("uri");
                    if (uri != null && uri.startsWith("/monitoring")) {
                        return MeterFilterReply.DENY;
                    }
                }
                return MeterFilterReply.NEUTRAL;
            }
        };
    }

    public static void main(String[] args) {
        // Need to enable untrusted loaders, since OpenJPEG is categorized as untrusted
        // due to a lack of fuzz-testing
        Vips.allowUntrustedOperations();
        // Operations cache does not make much sense for our access patterns
        Vips.disableOperationCache();
        SpringApplication app = new SpringApplication(Wolpi.class);
        app.run(args);
    }
}
