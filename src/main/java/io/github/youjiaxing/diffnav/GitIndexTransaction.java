package io.github.youjiaxing.diffnav;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** A Git index transaction. The real index lock protects verification and atomic publication. */
public final class GitIndexTransaction {
    public record Entry(String mode, String hash, String text, String checkoutText) {}
    public record Snapshot(Path root, Path index, String path, String head, Entry staged, Entry committed,
                           byte[] indexBytes, byte[] worktreeBytes, boolean worktreeExists, Charset charset) {}
    private final String git;
    public GitIndexTransaction(String git) { this.git = git; }

    public Snapshot capture(Path root, String path, Charset charset) throws Exception {
        Path actualRoot = Path.of(text(run(root, null, null, "rev-parse", "--show-toplevel")).strip()).toRealPath();
        if (!actualRoot.equals(root.toRealPath())) throw new IOException("Git 仓库根目录已变化，已取消选行操作");
        if (path.isBlank() || path.indexOf('\0') >= 0 || Path.of(path).isAbsolute() || path.startsWith("../")) throw new IOException("无效文件路径");
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root) || Files.isSymbolicLink(file)) throw new IOException("暂不支持符号链接");
        Path index = Path.of(text(run(root, null, null, "rev-parse", "--path-format=absolute", "--git-path", "index")).strip());
        byte[] before = bytesOrEmpty(index);
        String head = head(root);
        Entry staged = entry(root, path, charset, false, head);
        Entry committed = entry(root, path, charset, true, head);
        boolean exists = Files.exists(file);
        byte[] local = exists ? Files.readAllBytes(file) : new byte[0];
        if (!Arrays.equals(before, bytesOrEmpty(index)) || !head.equals(head(root))) throw new IOException("Git 状态正在变化，请刷新后重试");
        return new Snapshot(root, index, path, head, staged, committed, before, local, exists, charset);
    }

    public void commit(Snapshot expected, String candidate, boolean exists, BooleanSupplier documentUnchanged) throws Exception {
        commit(expected, candidate, exists, null, documentUnchanged);
    }

    public void commit(Snapshot expected, String candidate, boolean exists, List<Integer> origins, BooleanSupplier documentUnchanged) throws Exception {
        Path lock = expected.index.resolveSibling(expected.index.getFileName() + ".lock");
        // CREATE_NEW is Git's lock protocol. Never remove another process's lock.
        boolean acquired = false;
        boolean published = false;
        try {
          try (FileChannel held = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            acquired = true;
            Path temporary = null;
            try {
                validate(expected, documentUnchanged);
                temporary = Files.createTempFile(expected.index.getParent(), "diffnav-index-", ".tmp");
                if (expected.indexBytes.length > 0) Files.write(temporary, expected.indexBytes);
                else {
                    Files.delete(temporary);
                    run(expected.root, temporary, null, "read-tree", "--empty");
                }
                if (exists) {
                    String mode = expected.staged != null ? expected.staged.mode : expected.committed != null ? expected.committed.mode : "100644";
                    String template = expected.staged != null ? expected.staged.checkoutText
                            : expected.committed != null ? expected.committed.checkoutText : decode(expected.worktreeBytes, expected.charset);
                    byte[] content = encode(preserveFormat(template, candidate, expected.staged == null ? null : origins), expected.charset);
                    String hash = text(run(expected.root, temporary, content, "hash-object", "-w", "--path=" + expected.path, "--stdin")).strip();
                    run(expected.root, temporary, null, "update-index", "--add", "--cacheinfo", mode + "," + hash + "," + expected.path);
                } else run(expected.root, temporary, null, "update-index", "--force-remove", "--", expected.path);
                validate(expected, documentUnchanged);
                byte[] updated = Files.readAllBytes(temporary);
                ByteBuffer buffer = ByteBuffer.wrap(updated);
                while (buffer.hasRemaining()) held.write(buffer);
                held.force(true);
            } finally {
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                    Files.deleteIfExists(temporary.resolveSibling(temporary.getFileName() + ".lock"));
                }
            }
          }
          Files.move(lock, expected.index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          published = true;
        } catch (FileAlreadyExistsException busy) {
            throw new IOException("Git 暂存区正被其他操作占用，请稍后重试", busy);
        } finally {
            if (acquired && !published) Files.deleteIfExists(lock);
        }
    }

    private void validate(Snapshot expected, BooleanSupplier documentUnchanged) throws Exception {
        Path file = expected.root.resolve(expected.path);
        if (!Arrays.equals(expected.indexBytes, bytesOrEmpty(expected.index)) || !expected.head.equals(head(expected.root))
                || expected.worktreeExists != Files.exists(file) || !Arrays.equals(expected.worktreeBytes, bytesOrEmpty(file))
                || !documentUnchanged.getAsBoolean()) throw new IOException("文件、HEAD 或暂存内容已变化，本次操作已取消，请重新选择");
    }

    /** Read-only final check for a document operation. No index lock or Git write is performed. */
    public void verify(Snapshot expected) throws Exception { validate(expected, () -> true); }

    private Entry entry(Path root, String path, Charset charset, boolean fromHead, String head) throws Exception {
        byte[] listing = fromHead && head.isEmpty() ? new byte[0] : fromHead
                ? run(root, null, null, "ls-tree", "-z", head, "--", path)
                : run(root, null, null, "ls-files", "--stage", "-z", "--", path);
        if (listing.length == 0) return null;
        String record = text(listing);
        String[] metadata = record.substring(0, record.indexOf('\t')).split(" ");
        if (!fromHead && (!metadata[2].equals("0") || record.indexOf('\0') != record.length() - 1)) throw new IOException("冲突文件不支持按行暂存");
        String mode = metadata[0], hash = metadata[fromHead ? 2 : 1];
        if (!mode.equals("100644") && !mode.equals("100755")) throw new IOException("仅支持普通文本文件");
        byte[] content = run(root, null, null, "cat-file", "--filters", "--path=" + path, hash);
        if (content.length > 8 * 1024 * 1024) throw new IOException("文件过大，请使用原生暂存操作");
        String decoded = decode(content, charset);
        if (decoded.indexOf('\0') >= 0) throw new IOException("二进制文件不支持按行暂存");
        return new Entry(mode, hash, normalized(decoded), decoded);
    }

    private String head(Path root) throws Exception {
        var result = command(root, null, null, List.of("rev-parse", "--verify", "--quiet", "HEAD"));
        if (result.code == 0) return text(result.out).strip();
        if (result.code == 1) {
            var reference = command(root, null, null, List.of("symbolic-ref", "--quiet", "HEAD"));
            if (reference.code == 0) {
                var exists = command(root, null, null, List.of("show-ref", "--verify", "--quiet", text(reference.out).strip()));
                if (exists.code == 1) return ""; // A valid unborn branch, not an arbitrary Git failure.
            }
        }
        throw new IOException("无法读取 HEAD，已取消选行操作：" + text(result.error).strip());
    }
    private static byte[] bytesOrEmpty(Path path) throws IOException { return Files.exists(path) ? Files.readAllBytes(path) : new byte[0]; }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    public static String normalized(String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
    private static String decode(byte[] bytes, Charset charset) throws Exception {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** Retain the index BOM and exact line separators for all surviving unchanged lines. */
    private static String preserveFormat(String original, String candidate, List<Integer> origins) throws IOException {
        boolean bom = original.startsWith("\uFEFF");
        String body = bom ? original.substring(1) : original;
        var raw = new ArrayList<String>();
        for (int start = 0, i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '\r' || body.charAt(i) == '\n') {
                if (body.charAt(i) == '\r' && i + 1 < body.length() && body.charAt(i + 1) == '\n') i++;
                raw.add(body.substring(start, i + 1)); start = i + 1;
            }
            if (i == body.length() - 1 && start < body.length()) raw.add(body.substring(start));
        }
        List<String> old = SelectedLines.lines(normalized(original)), next = SelectedLines.lines(candidate);
        String separator = body.contains("\r\n") ? "\r\n" : body.indexOf('\r') >= 0 ? "\r" : "\n";
        if (origins != null && origins.size() == next.size()) {
            var exact = new StringBuilder(bom ? "\uFEFF" : "");
            for (int k = 0; k < next.size(); k++) {
                int source = origins.get(k);
                if (source >= 0 && source < old.size() && old.get(source).equals(next.get(k))) exact.append(raw.get(source));
                else exact.append(next.get(k).replace("\n", separator));
            }
            return exact.toString();
        }
        // Edited preview results no longer carry exact row identity. Reject format ambiguities.
        var representations = new HashMap<String, String>();
        for (int k = 0; k < old.size(); k++) {
            String previous = representations.putIfAbsent(old.get(k), raw.get(k));
            if (previous != null && !previous.equals(raw.get(k)) && !candidate.equals(normalized(original)))
                throw new IOException("重复行包含不同换行格式，无法安全应用编辑后的预览，请缩小选区");
        }
        int prefix = 0;
        while (prefix < old.size() && prefix < next.size() && old.get(prefix).equals(next.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < old.size() - prefix && suffix < next.size() - prefix
                && old.get(old.size() - 1 - suffix).equals(next.get(next.size() - 1 - suffix))) suffix++;
        int n = old.size() - prefix - suffix, m = next.size() - prefix - suffix;
        if ((long)n * m > 4_000_000L) throw new IOException("修改范围过大，无法安全保留行尾格式，请缩小选区");
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
            dp[i][j] = old.get(prefix + i).equals(next.get(prefix + j)) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
        var result = new StringBuilder(bom ? "\uFEFF" : "");
        for (int i = 0; i < prefix; i++) result.append(raw.get(i));
        int i = 0, j = 0;
        while (j < m) {
            if (i < n && old.get(prefix + i).equals(next.get(prefix + j))) { result.append(raw.get(prefix + i)); i++; j++; }
            else if (i < n && dp[i + 1][j] >= dp[i][j + 1]) i++;
            else { result.append(next.get(prefix + j++).replace("\n", separator)); }
        }
        for (int k = old.size() - suffix; k < old.size(); k++) result.append(raw.get(k));
        return result.toString();
    }
    private static byte[] encode(String text, Charset charset) throws Exception {
        ByteBuffer encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
        byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
    }
    private byte[] run(Path root, Path index, byte[] input, String... args) throws Exception {
        Output result = command(root, index, input, Arrays.asList(args));
        if (result.code != 0) throw new IOException("Git 操作失败：" + text(result.error).strip());
        return result.out;
    }
    private record Output(int code, byte[] out, byte[] error) {}
    private Output command(Path root, Path index, byte[] input, List<String> args) throws Exception {
        var command = new ArrayList<>(List.of(git, "-C", root.toString(), "--literal-pathspecs")); command.addAll(args);
        var builder = new ProcessBuilder(command);
        builder.environment().remove("GIT_INDEX_FILE");
        builder.environment().remove("GIT_DIR"); builder.environment().remove("GIT_WORK_TREE");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        if (index != null) builder.environment().put("GIT_INDEX_FILE", index.toString());
        Process process = builder.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = executor.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> stderr = executor.submit(() -> process.getErrorStream().readAllBytes());
            Future<?> stdin = executor.submit(() -> { try (var stream = process.getOutputStream()) { if (input != null) stream.write(input); } return null; });
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Git 操作超时"); }
            stdin.get();
            return new Output(process.exitValue(), stdout.get(), stderr.get());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
