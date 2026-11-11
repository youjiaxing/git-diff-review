package io.github.youjiaxing.diffnav;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Serializes this plugin's writes across previews and projects, without blocking the EDT. */
final class SelectionWriteGuard implements AutoCloseable {
    private static final Map<Path, SelectionWriteGuard> ACTIVE = new HashMap<>();
    private final Path index;
    private SelectionWriteGuard(Path index) { this.index = index; }
    static synchronized SelectionWriteGuard acquire(Path index) {
        index = index.toAbsolutePath().normalize();
        if (ACTIVE.containsKey(index)) return null;
        var guard = new SelectionWriteGuard(index);
        ACTIVE.put(index, guard);
        return guard;
    }
    @Override public void close() {
        synchronized (SelectionWriteGuard.class) { ACTIVE.remove(index, this); }
    }
}
