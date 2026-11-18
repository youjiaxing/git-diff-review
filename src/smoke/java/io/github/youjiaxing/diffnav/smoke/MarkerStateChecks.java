package io.github.youjiaxing.diffnav.smoke;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ex.*;
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager;
import com.intellij.openapi.vfs.*;
import git4idea.index.GitStageLineStatusTracker;
import git4idea.index.GitStageTracker;
import io.github.youjiaxing.diffnav.SourceMarkerService;
import io.github.youjiaxing.diffnav.SelectedLines;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;

/** Real Git states and native renderer comparisons, isolated from user repositories. */
final class MarkerStateChecks {
    private record Case(String name, String head, String index, String local, int line, boolean staged, boolean unstaged, boolean point) {}
    private static final String BASE = "before\noriginal-one\nanchor\noriginal-two\nafter\n";
    private static final String INDEX = BASE.replace("original-one", "staged-one");
    private final List<Case> cases = List.of(
            new Case("clean", BASE, BASE, BASE, 1, false, false, false),
            new Case("modified-unstaged", BASE, BASE, BASE.replace("original-one", "local-one"), 1, false, true, false),
            new Case("modified-staged", BASE, INDEX, INDEX, 1, true, false, false),
            new Case("modified-mixed", BASE, INDEX, INDEX.replace("staged-one", "local-one"), 1, true, true, false),
            new Case("restored-head", BASE, INDEX, BASE, 1, true, true, false),
            new Case("partial-independent", BASE, INDEX, INDEX.replace("original-two", "local-two"), 1, true, false, false),
            new Case("partial-local", BASE, INDEX, INDEX.replace("original-two", "local-two"), 3, false, true, false),
            new Case("new-staged", null, BASE, BASE, 1, true, false, false),
            new Case("new-no-eol", null, "alpha\nbeta", "alpha\nbeta", 1, true, false, false),
            new Case("existing-empty-filled", "", "alpha\nbeta", "alpha\nbeta", 1, true, false, false),
            new Case("new-empty", null, "", "", 0, false, false, false),
            new Case("new-mixed", null, BASE, BASE.replace("original-one", "local-one"), 1, true, true, false),
            new Case("new-untouched", null, BASE, BASE.replace("original-one", "local-one"), 3, true, false, false),
            new Case("local-insert", BASE, BASE, BASE.replace("anchor\n", "anchor\ninserted\n"), 3, false, true, false),
            new Case("insert-after-new-stage", null, BASE, BASE.replace("anchor\n", "anchor\ninserted\n"), 3, false, true, false),
            new Case("staged-delete", BASE, BASE.replace("original-one\n", ""), BASE.replace("original-one\n", ""), 1, true, false, true),
            new Case("local-delete", BASE, BASE, BASE.replace("original-one\n", ""), 1, false, true, true),
            new Case("mixed-delete", BASE, INDEX, INDEX.replace("staged-one\n", ""), 1, true, true, true),
            new Case("empty-staged", BASE, "", "", 0, true, false, true),
            new Case("empty-local", BASE, BASE, "", 0, false, true, true),
            new Case("inserted-grown", "a\nz", "a\nb\nz", "a\nB\nC\nz", 1, true, true, false),
            new Case("delete-beside-local", "a\nb\nc", "a\nc", "a\nC", 1, true, false, true),
            new Case("untracked", null, null, BASE, 1, false, true, false)
    );
    private final Project project;
    private final BiConsumer<Boolean, String> check;
    private int step, nextCase, waits;
    private Editor editor;
    MarkerStateChecks(Project project, BiConsumer<Boolean, String> check) { this.project = project; this.check = check; }

    boolean tick() throws Exception {
        if (++waits > 180) throw new AssertionError("Marker state timeout " + (nextCase < cases.size() ? cases.get(nextCase).name : "end"));
        if (step == 0) {
            var tracked = new ArrayList<String>();
            for (var c : cases) if (c.head != null) { Files.writeString(path(c), c.head); tracked.add(path(c).getFileName().toString()); }
            gitWithPaths("add", tracked); var commit = new ArrayList<>(List.of("commit", "-m", "marker state fixtures", "--only", "--")); commit.addAll(tracked); git(commit);
            var staged = new ArrayList<String>();
            for (var c : cases) if (c.index != null) { Files.writeString(path(c), c.index); staged.add(path(c).getFileName().toString()); }
            gitWithPaths("add", staged);
            for (var c : cases) Files.writeString(path(c), c.local);
            LocalFileSystem.getInstance().refreshNioFiles(cases.stream().map(this::path).toList(), false, false, null);
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
            GitStageTracker.getInstance(project).updateTrackerState(); step = 1; waits = 0; return false;
        }
        var c = cases.get(nextCase);
        if (step == 1) {
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path(c));
            editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file), true);
            step = 2; waits = 0; return false;
        }
        if (waits < 8) return false;
        var tracker = LineStatusTrackerManager.getInstanceImpl(project).getLineStatusTracker(editor.getDocument());
        // Repository/index loading is asynchronous; compare only the requested Git snapshot.
        if (c.index != null && tracker instanceof GitStageLineStatusTracker git
                && (!git.isValid() || !git.getVcsDocument().getText().equals(c.head == null ? "" : c.head)
                || !git.getStagedDocument().getText().equals(c.index))) return false;
        if (c.index == null) {
            check.accept(tracker == null || !(tracker instanceof GitStageLineStatusTracker git) || !git.isValid() || git.getRanges() == null || git.getRanges().isEmpty(),
                    "untracked file preserves native absence of a tracked gutter baseline");
        } else if (c.staged || c.unstaged) {
            if (!(tracker instanceof GitStageLineStatusTracker git) || !git.isValid()) return false;
            var marker = marker(); if (marker == null || !marker.getLineMarkerRenderer().getClass().getName().contains("SourceMarkerService$Proxy")) return false;
            var proxy = marker.getLineMarkerRenderer(); var watch = field(proxy, "watch");
            var current = watch.getClass().getDeclaredMethod("current"); current.setAccessible(true); if (!(boolean) current.invoke(watch)) return false;
            List<?> marks = (List<?>) field(watch, "marks");
            Files.writeString(Path.of(System.getProperty("diffnav.smoke.report")), "STATE " + c.name + " native=" + git.getRanges() + " projected=" + marks + "\n", StandardOpenOption.APPEND);
            Object found = null;
            for (var value : marks) {
                var mark = (Range) value;
                if (c.point ? mark.getLine1() == c.line && mark.getLine2() == c.line : mark.getLine1() <= c.line && c.line < mark.getLine2()) { found = value; break; }
            }
            check.accept(found != null && (boolean) field(found, "staged") == c.staged && (boolean) field(found, "unstaged") == c.unstaged,
                    "Git state " + c.name + " has correct per-line membership");
            if (c.name.equals("modified-unstaged") || c.name.equals("modified-staged") || c.name.equals("modified-mixed")
                    || c.name.equals("new-no-eol") || c.name.equals("existing-empty-filled") || c.name.equals("new-staged") || c.name.equals("empty-staged") || c.name.equals("empty-local") || c.name.equals("staged-delete") || c.name.equals("local-delete") || c.name.equals("mixed-delete")) {
                var scheme = editor.getColorsScheme();
                var key = com.intellij.openapi.editor.colors.EditorColors.BORDER_LINES_COLOR;
                Color savedBorder = scheme.getColor(key);
                try {
                    for (boolean explicitBorder : new boolean[]{false, true}) {
                        scheme.setColor(key, explicitBorder ? Color.MAGENTA : savedBorder);
                        var actual = paint(proxy);
                        var nativeImage = paint(field(proxy, "original"));
                        var area = com.intellij.openapi.diff.LineStatusMarkerDrawUtil.getGutterArea(editor);
                        int y = editor.logicalPositionToXY(new LogicalPosition(c.line, 0)).y;
                        int mismatch = 0;
                        int start = Math.max(0, c.point ? y - editor.getLineHeight() / 2 : y + 2);
                        int end = Math.min(actual.getHeight(), c.point ? y + editor.getLineHeight() / 2 : y + editor.getLineHeight() - 2);
                        for (int xx = area.first; xx < area.second; xx++) for (int yy = start; yy < end; yy++)
                            if (actual.getRGB(xx, yy) != nativeImage.getRGB(xx, yy)) mismatch++;
                        capture(c.name + (explicitBorder ? "-border" : ""), actual, nativeImage);
                        check.accept(mismatch == 0, "native marker appearance " + c.name + " explicitBorder=" + explicitBorder + " matches official pixels [different=" + mismatch + "]");
                    }
                } finally { scheme.setColor(key, savedBorder); }
            }
        } else {
            check.accept(!(tracker instanceof GitStageLineStatusTracker git) || git.getRanges() == null || git.getRanges().isEmpty(), "clean file has no change markers");
        }
        if (++nextCase == cases.size()) return true;
        step = 1; waits = 0; return false;
    }
    private Path path(Case c) { return Path.of(project.getBasePath(), "state-" + c.name + ".txt"); }
    private RangeHighlighter marker() {
        for (var h : DocumentMarkupModel.forDocument(editor.getDocument(), project, false).getAllHighlighters())
            if (Boolean.TRUE.equals(h.getUserData(LineStatusMarkerRenderer.Companion.getMAIN_KEY()))) return h;
        return null;
    }
    private BufferedImage paint(Object renderer) {
        var im = new BufferedImage(editor.getComponent().getWidth(), editor.getComponent().getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = im.createGraphics(); try {
            g.setClip(0, 0, im.getWidth(), im.getHeight()); g.setColor(editor.getColorsScheme().getDefaultBackground()); g.fillRect(0, 0, im.getWidth(), im.getHeight());
            ((com.intellij.openapi.editor.markup.LineMarkerRenderer) renderer).paint(editor, g, new Rectangle(0, 0, im.getWidth(), im.getHeight()));
        } finally { g.dispose(); }
        return im;
    }
    private void capture(String name, BufferedImage actual, BufferedImage original) throws Exception {
        var dir = Path.of(System.getProperty("diffnav.smoke.report")).getParent().resolve("state-markers"); Files.createDirectories(dir);
        javax.imageio.ImageIO.write(actual, "png", dir.resolve(name + ".png").toFile());
        javax.imageio.ImageIO.write(original, "png", dir.resolve(name + "-official.png").toFile());
    }
    private void gitWithPaths(String op, List<String> paths) throws Exception { var cmd = new ArrayList<>(List.of(op, "--")); cmd.addAll(paths); git(cmd); }
    private void git(List<String> args) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-C", project.getBasePath())); cmd.addAll(args);
        var p = new ProcessBuilder(cmd).redirectErrorStream(true).start(); var out = new String(p.getInputStream().readAllBytes()); if (p.waitFor() != 0) throw new AssertionError(out);
    }
    private static Object field(Object owner, String name) throws Exception { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
}
