package dev.mdz.iiif.wolpi.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StreamUtils;

///  Utility for shelling out to external commands with timeout and error handling.
public class CommandRunner {

  /// Run a command to completion, throwing an exception if it fails or times out.
  ///
  /// @param executable       path to the executable
  /// @param workingDirectory optional working directory, or null to use the current directory
  /// @param timeout          maximum duration to wait for the command to complete
  /// @param args             arguments to pass to the command
  /// @return the command's standard output as a string
  /// @throws IOException          if an I/O error occurs or the command fails
  /// @throws InterruptedException if the current thread is interrupted while waiting for
  ///                              the command to complete
  public static String runCommand(
      Path executable, @Nullable Path workingDirectory, Duration timeout, String... args)
      throws IOException, InterruptedException {
    List<String> cmd = new ArrayList<>();
    cmd.add(executable.toAbsolutePath().toString());
    cmd.addAll(Arrays.asList(args));

    ProcessBuilder pb = new ProcessBuilder(cmd);
    if (workingDirectory != null) {
      pb.directory(workingDirectory.toFile());
    }
    Process p = pb.start();

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<String> stdoutFut =
          executor.submit(
              () -> StreamUtils.copyToString(p.getInputStream(), StandardCharsets.UTF_8));
      Future<String> stderrFut =
          executor.submit(
              () -> StreamUtils.copyToString(p.getErrorStream(), StandardCharsets.UTF_8));
      if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        p.destroyForcibly();
        throw new IOException(
            "'%s' command timed out after %ds. cmd=%s"
                .formatted(executable, timeout.toSeconds(), String.join(" ", args)));
      }
      int code = p.exitValue();
      String stderr;
      String stdout;
      try {
        stdout = stdoutFut.get();
        stderr = stderrFut.get();
      } catch (ExecutionException e) {
        throw new RuntimeException("Failed to read stdout/stderr of process", e);
      }
      if (code != 0) {
        throw new IOException(
            "'%s' command exited with code=%d. command='%s %s' stdout=%s stderr=%s"
                .formatted(executable, code, executable, String.join(" ", args), stdout, stderr));
      }
      return stdout;
    }
  }

  ///  Find executable on the system PATH.
  ///
  /// @param executableName name of the executable to find, e.g. "npm" or "python3"
  /// @return path to the executable, or null if not found
  public static @Nullable Path findOnSystemPath(String executableName) {
    String systemPath = System.getenv("PATH");
    if (systemPath == null || systemPath.isBlank()) {
      return null;
    }
    String[] paths = systemPath.split(":");
    return Arrays.stream(paths)
        .map(p -> Path.of(p, executableName))
        .filter(np -> Files.isRegularFile(np) && Files.isExecutable(np))
        .findFirst()
        .orElse(null);
  }
}
