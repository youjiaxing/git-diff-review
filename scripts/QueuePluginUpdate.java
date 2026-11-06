import com.intellij.ide.startup.StartupActionScriptManager;
import com.intellij.ide.startup.StartupActionScriptManager.ActionCommand;
import com.intellij.ide.startup.StartupActionScriptManager.DeleteCommand;
import com.intellij.ide.startup.StartupActionScriptManager.UnzipCommand;
import java.nio.file.*;
import java.util.*;

/** Uses the same deferred update format as the IDE's Install from Disk action. */
public final class QueuePluginUpdate {
    public static void main(String[] args) throws Exception {
        Path script = Path.of(args[1]).toAbsolutePath().normalize();
        var existing = StartupActionScriptManager.loadActionScript(script);
        if (args[0].equals("inspect")) { existing.forEach(System.out::println); return; }
        Path archive = Path.of(args[2]).toRealPath();
        Path plugins = Path.of(args[3]).toAbsolutePath().normalize();
        Path plugin = plugins.resolve("git-diff-review");
        Path legacy = plugins.resolve("goland-diff-navigation");
        if (!plugin.getParent().equals(plugins) || !legacy.getParent().equals(plugins)
                || !archive.getFileName().toString().equals("git-diff-review-0.3.1.zip"))
            throw new IllegalArgumentException("Unexpected update target or archive");
        var commands = new ArrayList<ActionCommand>();
        for (var command : existing) {
            boolean ownDelete = command instanceof DeleteCommand && (command.toString().equals("delete[" + plugin + "]")
                    || command.toString().equals("delete[" + legacy + "]"));
            boolean ownArchive = command instanceof UnzipCommand unzip
                    && Path.of(unzip.getSource()).getFileName().toString().matches("(?:git-diff-review|goland-diff-navigation)-[0-9.]+\\.zip")
                    && command.toString().equals("unzip[" + unzip.getSource() + "," + plugins + ",null]");
            if (!ownDelete && !ownArchive) commands.add(command);
        }
        // The ID changed intentionally. Remove both identities before installing the renamed plugin.
        commands.add(new DeleteCommand(legacy));
        commands.add(new DeleteCommand(plugin));
        commands.add(new UnzipCommand(archive, plugins));
        Path temporary = script.resolveSibling("diffnav-action.script.tmp");
        try {
            StartupActionScriptManager.saveActionScript(commands, temporary);
            Files.move(temporary, script, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        var reread = StartupActionScriptManager.loadActionScript(script);
        if (reread.size() != commands.size()) throw new IllegalStateException("Queue verification failed");
        reread.forEach(System.out::println);
        if (args[0].equals("simulate")) {
            for (var command : reread) command.execute(FileSystems.getDefault());
            if (Files.exists(legacy)) throw new IllegalStateException("Legacy plugin was not removed");
            try (var files = Files.list(plugin.resolve("lib"))) {
                var installed = files.map(path -> path.getFileName().toString()).toList();
                if (!installed.equals(List.of("git-diff-review-0.3.1.jar"))) throw new IllegalStateException(installed.toString());
            }
            System.out.println("Verified deferred update: only version 0.3.1 remains");
        }
    }
}
