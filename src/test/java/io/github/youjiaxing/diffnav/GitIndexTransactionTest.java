package io.github.youjiaxing.diffnav;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real Git fixtures, always outside business repositories. */
public final class GitIndexTransactionTest {
    private static int passed;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("diffnav-index-test-").toRealPath();
        try {
            git(root, "init", "-q"); git(root, "config", "core.autocrlf", "false");
            Files.writeString(root.resolve("a.txt"), "a\nb\n"); Files.writeString(root.resolve("other.txt"), "keep\n");
            git(root, "add", "."); git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "baseline");
            Files.writeString(root.resolve("a.txt"), "A\nB\n");
            Files.writeString(root.resolve("other.txt"), "already staged\n"); git(root, "add", "other.txt");
            var tx = new GitIndexTransaction("git");
            var snapshot = tx.capture(root, "a.txt", StandardCharsets.UTF_8);
            tx.commit(snapshot, "A\nb\n", true, () -> true);
            check(git(root, "show", ":a.txt").equals("A\nb\n"), "selected contents staged");
            check(Files.readString(root.resolve("a.txt")).equals("A\nB\n"), "worktree untouched");
            check(git(root, "show", ":other.txt").equals("already staged\n"), "unrelated staged content preserved");
            snapshot = tx.capture(root, "a.txt", StandardCharsets.UTF_8);
            git(root, "add", "a.txt");
            var stale = snapshot;
            reject(() -> tx.commit(stale, "wrong\n", true, () -> true), "concurrent index change rejected");
            check(git(root, "show", ":a.txt").equals("A\nB\n"), "external staged contents preserved");
            check(!Files.exists(snapshot.index().resolveSibling("index.lock")), "own lock removed on failure");
            snapshot = tx.capture(root, "a.txt", StandardCharsets.UTF_8);
            Path lock = snapshot.index().resolveSibling("index.lock"); Files.writeString(lock, "other owner's lock");
            var locked = snapshot;
            reject(() -> tx.commit(locked, "wrong\n", true, () -> true), "existing index lock rejected");
            check(Files.readString(lock).equals("other owner's lock"), "other process lock preserved"); Files.delete(lock);
            var valid = tx.capture(root, "a.txt", StandardCharsets.UTF_8);
            reject(() -> tx.commit(valid, "wrong\n", true, () -> false), "changed document prevents write");
            Files.writeString(root.resolve("new.txt"), "first\nsecond\n");
            tx.commit(tx.capture(root, "new.txt", StandardCharsets.UTF_8), "second\n", true, () -> true);
            check(git(root, "show", ":new.txt").equals("second\n"), "new file partial stage");
            tx.commit(tx.capture(root, "new.txt", StandardCharsets.UTF_8), "", false, () -> true);
            check(git(root, "ls-files", "--", "new.txt").isEmpty() && Files.exists(root.resolve("new.txt")), "unstage new file removes entry only");
            tx.commit(tx.capture(root, "a.txt", StandardCharsets.UTF_8), "", true, () -> true);
            check(git(root, "show", ":a.txt").isEmpty() && !git(root, "ls-files", "--", "a.txt").isEmpty(), "empty file stays an entry");
            tx.commit(tx.capture(root, "a.txt", StandardCharsets.UTF_8), "", false, () -> true);
            check(git(root, "ls-files", "--", "a.txt").isEmpty(), "full deletion removes entry");
            Files.writeString(root.resolve("raw.txt"), "a\r\nb\r\nc\n", StandardCharsets.UTF_8); git(root, "add", "raw.txt");
            Files.writeString(root.resolve("raw.txt"), "A\r\nB\r\nc\n", StandardCharsets.UTF_8);
            tx.commit(tx.capture(root, "raw.txt", StandardCharsets.UTF_8), "A\nb\nc\n", true, () -> true);
            check(git(root, "show", ":raw.txt").equals("A\r\nb\r\nc\n"), "raw CRLF and mixed unchanged line endings preserved without attributes");
            Files.writeString(root.resolve("bom.txt"), "\uFEFFa\nb\n", StandardCharsets.UTF_8); git(root, "add", "bom.txt");
            Files.writeString(root.resolve("bom.txt"), "A\nB\n", StandardCharsets.UTF_8);
            tx.commit(tx.capture(root, "bom.txt", StandardCharsets.UTF_8), "A\nb\n", true, () -> true);
            check(git(root, "show", ":bom.txt").equals("\uFEFFA\nb\n"), "index BOM retained when worktree removed it");
            Files.writeString(root.resolve("nobom.txt"), "a\nb\n", StandardCharsets.UTF_8); git(root, "add", "nobom.txt");
            Files.writeString(root.resolve("nobom.txt"), "\uFEFFA\nB\n", StandardCharsets.UTF_8);
            tx.commit(tx.capture(root, "nobom.txt", StandardCharsets.UTF_8), "A\nb\n", true, () -> true);
            check(git(root, "show", ":nobom.txt").equals("A\nb\n"), "worktree BOM not copied into existing index");
            Files.writeString(root.resolve("repeated.txt"), "anchor\nx\r\nx\nend\n"); git(root, "add", "repeated.txt");
            Files.writeString(root.resolve("repeated.txt"), "anchor\nend\n");
            var repeated = tx.capture(root, "repeated.txt", StandardCharsets.UTF_8);
            var plan = SelectedLines.apply(repeated.staged().text(), true, "anchor\nend\n", true,
                    List.of(new SelectedLines.Change(1, 3, 1, 1)), false, 1, 2);
            tx.commit(repeated, plan.text(), plan.exists(), plan.baseOrigins(), () -> true);
            check(git(root, "show", ":repeated.txt").equals("anchor\nx\nend\n"), "surviving duplicate row retains its exact original line ending");
            Files.writeString(root.resolve("restore.txt"), "\uFEFFa\r\nb\r\n"); git(root, "add", "restore.txt");
            git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "restore fixture");
            Files.delete(root.resolve("restore.txt")); git(root, "add", "restore.txt");
            tx.commit(tx.capture(root, "restore.txt", StandardCharsets.UTF_8), "a\nb\n", true, () -> true);
            check(git(root, "show", ":restore.txt").equals("\uFEFFa\r\nb\r\n"), "unstaging deleted file restores HEAD format without touching missing worktree");
            Files.writeString(root.resolve("line ending.txt"), "甲\r\n乙\r\n", StandardCharsets.UTF_8);
            Files.writeString(root.resolve(".gitattributes"), "*.txt text eol=crlf\n"); git(root, "add", ".gitattributes", "line ending.txt");
            var crlf = tx.capture(root, "line ending.txt", StandardCharsets.UTF_8);
            check(crlf.staged().text().equals("甲\n乙\n"), "CRLF normalized for diff plan");
            tx.commit(crlf, "甲\n新\n", true, () -> true);
            check(git(root, "show", ":line ending.txt").equals("甲\n新\n"), "Git clean filters and UTF-8 preserved");
            System.out.println("GitIndexTransactionTest: " + passed + " real Git checks passed");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.getFileStore(path).supportsFileAttributeView("dos")) Files.setAttribute(path, "dos:readonly", false);
                Files.deleteIfExists(path);
            } }
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void reject(Checked action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); } catch (java.io.IOException expected) { passed++; }
    }
    private static String git(Path root, String... arguments) throws Exception {
        var args = new ArrayList<>(List.of("git", "-C", root.toString())); args.addAll(Arrays.asList(arguments));
        var process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new AssertionError(args.toString());
        return new String(output, StandardCharsets.UTF_8);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; }
}
