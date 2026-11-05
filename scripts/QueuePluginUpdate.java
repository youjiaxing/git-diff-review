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
        Path plugin = plugins.resolve("goland-diff-navigation");
        if (!plugin.getParent().equals(plugins) || !archive.getFileName().toString().equals("goland-diff-navigation-0.3.0.zip"))
            throw new IllegalArgumentException("Unexpected update target or archive");
        var commands = new ArrayList<ActionCommand>();
        for (var command : existing) {
            boolean ownDelete = command instanceof DeleteCommand && command.toString().equals("delete[" + plugin + "]");
            boolean ownArchive = command instanceof UnzipCommand unzip && Path.of(unzip.getSource()).getFileName().toString().matches("goland-diff-navigation-[0-9.]+\\.zip");
            if (!ownDelete && !ownArchive) commands.add(command);
        }
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
            try (var files = Files.list(plugin.resolve("lib"))) {
                var installed = files.map(path -> path.getFileName().toString()).toList();
                if (!installed.equals(List.of("goland-diff-navigation-0.3.0.jar"))) throw new IllegalStateException(installed.toString());
            }
            System.out.println("Verified deferred update: only version 0.3.0 remains");
        }
    }
}
