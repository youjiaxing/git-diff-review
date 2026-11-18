import com.intellij.ide.startup.StartupActionScriptManager;
import com.intellij.ide.startup.StartupActionScriptManager.ActionCommand;
import com.intellij.ide.startup.StartupActionScriptManager.DeleteCommand;
import com.intellij.ide.startup.StartupActionScriptManager.UnzipCommand;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** Uses the same deferred update format as the IDE's Install from Disk action. */
public final class QueuePluginUpdate {
    public static void main(String[] args) throws Exception {
        Path script = Path.of(args[1]).toAbsolutePath().normalize();
        var existing = loadStrict(script);
        if (args[0].equals("inspect")) { existing.forEach(System.out::println); return; }
        if (args[0].equals("clear-own")) {
            Path plugins = Path.of(args[2]).toAbsolutePath().normalize();
            var remaining = existing.stream().filter(command -> !isOwn(command, plugins)).toList();
            if (remaining.size() != existing.size()) writeScript(script, remaining);
            if (loadStrict(script).stream().anyMatch(command -> isOwn(command, plugins)))
                throw new IOException("Plugin update commands remain after cleanup");
            System.out.println("Preserved unrelated startup commands: " + remaining.size());
            return;
        }
        String version = args[4];
        if (!version.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"))
            throw new IllegalArgumentException("Invalid stable version");
        Path archive = Path.of(args[2]).toRealPath();
        Path plugins = Path.of(args[3]).toAbsolutePath().normalize();
        Path plugin = plugins.resolve("git-diff-review");
        Path legacy = plugins.resolve("goland-diff-navigation");
        if (!plugin.getParent().equals(plugins) || !legacy.getParent().equals(plugins)
                || !archive.getFileName().toString().equals("git-diff-review-" + version + ".zip"))
            throw new IllegalArgumentException("Unexpected update target or archive");
        var commands = new ArrayList<ActionCommand>();
        for (var command : existing) {
            if (!isOwn(command, plugins)) commands.add(command);
        }
        // The ID changed intentionally. Remove both identities before installing the renamed plugin.
        commands.add(new DeleteCommand(legacy));
        commands.add(new DeleteCommand(plugin));
        commands.add(new UnzipCommand(archive, plugins));
        writeScript(script, commands);
        var reread = loadStrict(script);
        if (reread.size() != commands.size()) throw new IllegalStateException("Queue verification failed");
        reread.forEach(System.out::println);
        if (args[0].equals("simulate")) {
            for (var command : reread) command.execute(FileSystems.getDefault());
            if (Files.exists(legacy)) throw new IllegalStateException("Legacy plugin was not removed");
            try (var files = Files.list(plugin.resolve("lib"))) {
                var installed = files.map(path -> path.getFileName().toString()).toList();
                if (!installed.equals(List.of("git-diff-review-" + version + ".jar"))) throw new IllegalStateException(installed.toString());
            }
            System.out.println("Verified deferred update: only version " + version + " remains");
        }
    }
    private static List<ActionCommand> loadStrict(Path script) throws Exception {
        try (var input = new ObjectInputStream(Files.newInputStream(script))) {
            Object value = input.readObject();
            if (!(value instanceof ActionCommand[] commands)) throw new IOException("Invalid startup command array");
            return Arrays.asList(commands);
        } catch (NoSuchFileException absent) { return List.of(); }
    }
    private static boolean isOwn(ActionCommand command, Path plugins) {
        boolean delete = command instanceof DeleteCommand && (command.toString().equals("delete[" + plugins.resolve("git-diff-review") + "]")
                || command.toString().equals("delete[" + plugins.resolve("goland-diff-navigation") + "]"));
        boolean archive = command instanceof UnzipCommand unzip
                && Path.of(unzip.getSource()).getFileName().toString().matches("(?:git-diff-review|goland-diff-navigation)-[0-9.]+\\.zip")
                && command.toString().equals("unzip[" + unzip.getSource() + "," + plugins + ",null]");
        return delete || archive;
    }
    private static void writeScript(Path script, List<? extends ActionCommand> commands) throws Exception {
        Path temporary = script.resolveSibling("diffnav-action.script.tmp");
        try {
            StartupActionScriptManager.saveActionScript(new ArrayList<>(commands), temporary);
            if (Files.exists(temporary)) Files.move(temporary, script, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.deleteIfExists(script);
        } finally { Files.deleteIfExists(temporary); }
    }
}
