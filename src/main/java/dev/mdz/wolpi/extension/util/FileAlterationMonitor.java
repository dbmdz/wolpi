package dev.mdz.wolpi.extension.util;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/// Monitor paths recursively, invoking callbacks when changes occur.
///
/// This class uses Java's [WatchService] API to monitor file system events. It supports filtering
/// events based on user-defined predicates. It can monitor both directories and individual files.
///
/// Contrary to Apache Commons IO's `FileAlterationMonitor`, this implementation does not use
/// polling, but relies on the underlying OS's file system event notifications (like inotify on
/// Linux) to retrieve asynchronous updates from the OS.
public class FileAlterationMonitor {
    /// Minimum interval between change events for the same path to avoid duplicate notifications
    private static final int MINIMUM_CHANGE_INTERVAL_MS = 100;

    /// Represents a registered observer with its callback and filter
    private record Observer(Consumer<AlterationEvent> callback, Predicate<Path> filter) {}

    /// Represents a monitored path along with its observers and watch key
    private record MonitoredPath(Path path, Set<Observer> observers, WatchKey key) {}

    private final WatchService watcher;
    private final Map<WatchKey, MonitoredPath> monitoredPaths;
    private final Map<WatchKey, Instant> lastChangeTimes = new HashMap<>();

    /// The thread that runs the monitoring loop
    private @Nullable Thread watcherThread = null;

    /// Type of file system alteration event
    public enum EventKind {
        CREATE,
        DELETE,
        MODIFY
    }

    /// Represents a file system alteration event along with its affected path
    public record AlterationEvent(EventKind kind, Path path, Instant timestamp) {}

    public FileAlterationMonitor() throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.monitoredPaths = new ConcurrentHashMap<>();
    }

    /// Start monitoring a directory or file for changes.
    ///
    /// @param dir      The directory or file to monitor. If a directory is provided, all its
    ///                 subdirectories and files contained therein will be monitored recursively.
    /// @param callback A callback function that will be invoked when a file system alteration event
    ///                 occurs. The callback receives an [AlterationEvent] object containing the
    ///                 event type and the affected path.
    /// @param filter   A predicate function to filter which paths should trigger the callback. The
    ///                 callback will only be invoked for paths where this predicate returns true.
    /// @throws IOException If an I/O error occurs while setting up the monitoring.
    public void monitor(Path dir, Consumer<AlterationEvent> callback, Predicate<Path> filter) throws IOException {
        if (Files.isDirectory(dir)) {
            walkAndRegisterDirectories(dir, new Observer(callback, filter));
        } else if (Files.exists(dir) && filter.test(dir)) {
            register(dir.getParent(), new Observer(callback, p -> p.equals(dir) && filter.test(p)));
        } else {
            throw new IllegalArgumentException("Path is not a directory or a valid file: " + dir);
        }
    }

    ///  Start monitoring a directory or file for changes, without filtering.
    ///
    /// @param dir      The directory or file to monitor. If a directory is provided, all its
    ///                 subdirectories and files contained therein will be monitored recursively.
    /// @param callback A callback function that will be invoked when a file system alteration event
    ///                 occurs. The callback receives an [AlterationEvent] object containing the
    ///                 event type and the affected path.
    /// @throws IOException If an I/O error occurs while setting up the monitoring.
    public void monitor(Path dir, Consumer<AlterationEvent> callback) throws IOException {
        monitor(dir, callback, p -> true);
    }

    /// Start monitoring the watched paths in a separate thread.
    public void start() {
        if (watcherThread != null && watcherThread.isAlive()) {
            throw new IllegalStateException("Monitor is already running");
        }
        watcherThread = new Thread(this::processEvents);
        watcherThread.start();
    }

    /// Stop monitoring and wait for the watcher thread to finish.
    public void stop() throws InterruptedException, IOException {
        if (watcherThread == null || !watcherThread.isAlive()) {
            throw new IllegalStateException("Monitor is not running");
        }
        watcher.close();
        watcherThread.join();
        watcherThread = null;
    }

    /// Run the monitor in the current thread (blocking).
    public void run() {
        processEvents();
    }

    private void register(Path dir, Observer observer) throws IOException {
        WatchKey key = dir.register(
                watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        MonitoredPath monitored =
                monitoredPaths.computeIfAbsent(key, k -> new MonitoredPath(dir, new HashSet<>(), key));
        monitored.observers.add(observer);
    }

    /// Register the given directory, and all its sub-directories, with the WatchService.
    ///
    /// @param root     The root directory to start the registration from.
    /// @param observer The observer to associate with the registered directories.
    private void walkAndRegisterDirectories(final Path root, Observer observer) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                register(dir, observer);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /// Process all events for keys queued to the watcher.
    private void processEvents() {
        for (; ; ) {
            // Wait for the next IO event on the registered paths
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException _) {
                // Exit loop if watcher is closed
                return;
            }

            // Get the root directory associated with the watch key
            MonitoredPath monitoredPath = monitoredPaths.get(key);
            if (monitoredPath == null) {
                // Key may have been for a deleted directory
                continue;
            }

            // Multiple events may be queued, process them all
            for (WatchEvent<?> rawEvent : key.pollEvents()) {
                WatchEvent.Kind<?> kind = rawEvent.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> event = (WatchEvent<Path>) rawEvent;
                Path child = monitoredPath.path().resolve(event.context());

                AlterationEvent alterationEvent;
                Instant ts = Instant.now();
                if (kind != StandardWatchEventKinds.ENTRY_DELETE) {
                    try {
                        ts = Files.getLastModifiedTime(child).toInstant();
                    } catch (IOException e) {
                        // Ignore, use current time
                    }
                }

                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    alterationEvent = new AlterationEvent(EventKind.CREATE, child, ts);
                } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    alterationEvent = new AlterationEvent(EventKind.DELETE, child, ts);
                } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                    alterationEvent = new AlterationEvent(EventKind.MODIFY, child, ts);
                } else {
                    continue;
                }

                Instant lastChange = lastChangeTimes.get(key);
                if (lastChange != null && (Instant.now().isBefore(lastChange.plusMillis(MINIMUM_CHANGE_INTERVAL_MS)))) {
                    // Skip events that occur too close to the last one to avoid duplicate notifications
                    continue;
                }

                // Invoke callbacks for files that match the filter associated with each callback
                for (Observer observer : monitoredPath.observers()) {
                    if (observer.filter().test(child)) {
                        observer.callback().accept(alterationEvent);
                        lastChangeTimes.put(key, Instant.now());
                    }
                }
            }

            // Important: WatchKeys must be reset after processing to receive further events
            boolean valid = key.reset();
            if (!valid) {
                monitoredPaths.remove(key);
                lastChangeTimes.remove(key);
            }
        }
    }
}
