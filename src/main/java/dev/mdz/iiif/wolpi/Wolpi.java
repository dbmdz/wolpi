package dev.mdz.iiif.wolpi;

import app.photofox.vipsffm.Vips;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mdz.iiif.wolpi.config.WolpiConfig;
import dev.mdz.iiif.wolpi.extension.ExtensionRegistry;
import dev.mdz.iiif.wolpi.extension.ExtensionRuntime;
import dev.mdz.iiif.wolpi.extension.RuntimeContextPooledObjectFactory;
import dev.mdz.iiif.wolpi.model.extensions.LoadedExtension;
import dev.mdz.iiif.wolpi.model.extensions.LoadedExtension.RuntimeContext;
import dev.mdz.iiif.wolpi.util.ByteBufferHttpMessageConverter;
import java.lang.foreign.Arena;
import java.net.http.HttpClient;
import java.util.List;
import org.apache.commons.pool2.KeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.HttpMessageConverter;
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
      ObjectMapper objectMapper,
      ExtensionRegistry registry,
      @Qualifier("contextPool") KeyedObjectPool<LoadedExtension, RuntimeContext> ctxPool) {
    return new ExtensionRuntime(registry, ctxPool, objectMapper);
  }

  /// Pool of [RuntimeContext]s for each [LoadedExtension] to be reused across requests.
  @Bean("contextPool")
  public KeyedObjectPool<LoadedExtension, RuntimeContext> extensionContextPool(
      WolpiConfig wolpiConfig) {
    var cfg = new GenericKeyedObjectPoolConfig<RuntimeContext>();
    cfg.setMaxIdlePerKey(wolpiConfig.extensionPool().maxIdle());
    cfg.setMaxTotalPerKey(wolpiConfig.extensionPool().maxTotal());
    // Disable bean self-registration via JMX
    cfg.setJmxEnabled(false);
    return new GenericKeyedObjectPool<>(new RuntimeContextPooledObjectFactory(), cfg);
  }

  /// Create a new HttpClient using HTTP/2 for the application.
  @Bean
  public HttpClient httpClient() {
    return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
  }

  /// Register the conversion service needed for case-insensitive enum conversion
  @Override
  public void addFormatters(@NonNull FormatterRegistry registry) {
    ApplicationConversionService.configure(registry);
  }

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    converters.add(new ByteBufferHttpMessageConverter());
  }

  public static void main(String[] args) {
    // Need to enable untrusted loaders, since OpenJPEG is categorized as untrusted
    // due to a lack of fuzz-testing
    Vips.init(true, false);
    Vips.disableOperationCache();
    SpringApplication.run(Wolpi.class, args);
  }
}
