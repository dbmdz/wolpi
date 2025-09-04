package dev.mdz.iiif.wolpi.testutil;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.mockito.MockedConstruction;

/// Utility helpers to reduce boilerplate when mocking ProcessBuilder constructions in tests.
public final class ProcessBuilderMocks {
  private ProcessBuilderMocks() {}

  // Fluent builder entrypoint
  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Predicate<List<String>> commandMatcher = cmd -> true;
    private int exitCode = 0;
    private final Map<String, String> stdoutByContains = new LinkedHashMap<>();
    private String defaultStdout = "";
    private String stderr = "";
    private BiConsumer<ProcessBuilder, MockedConstruction.Context> verifier = null;

    public Builder matchCommand(Predicate<List<String>> matcher) {
      this.commandMatcher = matcher != null ? matcher : (cmd -> true);
      return this;
    }

    public Builder matchCommandTokenContains(String token) {
      return matchCommand(cmd -> cmd.stream().anyMatch(t -> t.contains(token)));
    }

    public Builder success() {
      this.exitCode = 0;
      return this;
    }

    public Builder failure() {
      this.exitCode = 1;
      return this;
    }

    public Builder exitCode(int code) {
      this.exitCode = code;
      return this;
    }

    public Builder stdoutWhenContains(String substring, String stdout) {
      this.stdoutByContains.put(substring, stdout);
      return this;
    }

    public Builder stdoutMap(Map<String, String> map) {
      if (map != null) this.stdoutByContains.putAll(map);
      return this;
    }

    public Builder defaultStdout(String stdout) {
      this.defaultStdout = stdout != null ? stdout : "";
      return this;
    }

    public Builder stderr(String err) {
      this.stderr = err != null ? err : "";
      return this;
    }

    public Builder verify(BiConsumer<ProcessBuilder, MockedConstruction.Context> processVerifier) {
      this.verifier = processVerifier;
      return this;
    }

    public MockedConstruction<ProcessBuilder> build() {
      return construct(commandMatcher, exitCode, stdoutByContains, defaultStdout, stderr, verifier);
    }
  }

  private static MockedConstruction<ProcessBuilder> construct(
      Predicate<List<String>> commandMatcher,
      int exitCode,
      Map<String, String> stdoutByContains,
      String defaultStdout,
      String stderr,
      BiConsumer<ProcessBuilder, MockedConstruction.Context> processVerifier) {
    return mockConstruction(
        ProcessBuilder.class,
        (pbMock, context) -> {
          Process process = mock(Process.class);
          when(pbMock.start()).thenReturn(process);
          when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);

          @SuppressWarnings("unchecked")
          List<String> cmd = (List<String>) context.arguments().getFirst();

          boolean matches = commandMatcher == null || commandMatcher.test(cmd);

          int ec = matches ? exitCode : 0;
          String stdout = "";
          String err = "";
          if (matches) {
            stdout = defaultStdout == null ? "" : defaultStdout;
            if (stdoutByContains != null && !stdoutByContains.isEmpty()) {
              outer:
              for (Map.Entry<String, String> e : stdoutByContains.entrySet()) {
                String key = e.getKey();
                for (String token : cmd) {
                  if (token.contains(key)) {
                    stdout = e.getValue();
                    break outer;
                  }
                }
              }
            }
            err = stderr == null ? "" : stderr;
          }

          when(process.exitValue()).thenReturn(ec);
          when(process.getInputStream()).thenReturn(new ByteArrayInputStream(stdout.getBytes()));
          when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(err.getBytes()));

          if (processVerifier != null) {
            processVerifier.accept(pbMock, context);
          }
        });
  }
}
