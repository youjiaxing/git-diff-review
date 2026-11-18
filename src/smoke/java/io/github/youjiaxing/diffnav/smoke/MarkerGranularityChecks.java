package io.github.youjiaxing.diffnav.smoke;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.ActiveGutterRenderer;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ex.LineStatusMarkerRenderer;
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import git4idea.index.GitStageLineStatusTracker;
import git4idea.index.GitStageTracker;
import io.github.youjiaxing.diffnav.SelectedLines;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.function.BiConsumer;

/** Reproduces a fully staged new file with one later edit and one later deletion. */
final class MarkerGranularityChecks {
    private final Project project;
    private final BiConsumer<Boolean, String> check;
    private Editor source;
    private int step, ticks;
    MarkerGranularityChecks(Project project, BiConsumer<Boolean, String> check) { this.project = project; this.check = check; }
    boolean tick() throws Exception {
        if (++ticks > 200) throw new AssertionError("Marker granularity timed out at " + step);
        if (step == 0) {
            nativeLineBoundaries();
            var lines = new java.util.ArrayList<String>();
            for (int i = 0; i < 60; i++) lines.add("unique original source line " + i);
            var path = Path.of(project.getBasePath(), "marker-granularity.txt");
            Files.writeString(path, String.join("\n", lines) + "\n");
            var process = new ProcessBuilder("git", "-C", project.getBasePath(), "add", "--", path.getFileName().toString()).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes()); if (process.waitFor() != 0) throw new AssertionError(output);
            lines.set(14, "unique local replacement line 14"); lines.remove(34);
            Files.writeString(path, String.join("\n", lines) + "\n");
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            source = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file), true);
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(file);
            GitStageTracker.getInstance(project).updateTrackerState();
            step = 1; ticks = 0; return false;
        }
        var tracker = LineStatusTrackerManager.getInstanceImpl(project).getLineStatusTracker(source.getDocument());
        if (!(tracker instanceof GitStageLineStatusTracker git) || !git.isValid() || git.getRanges() == null) return false;
        RangeHighlighter marker = null;
        for (var h : DocumentMarkupModel.forDocument(source.getDocument(), project, false).getAllHighlighters())
            if (Boolean.TRUE.equals(h.getUserData(LineStatusMarkerRenderer.Companion.getMAIN_KEY()))
                    && h.getLineMarkerRenderer() != null && h.getLineMarkerRenderer().getClass().getName().contains("SourceMarkerService$Proxy")) marker = h;
        if (marker == null || source.getComponent().getWidth() <= 0) return false;
        // New projections are asynchronous; check the real editor only after status annotations were published.
        if (source.getInlayModel().getAfterLineEndElementsInRange(0, source.getDocument().getTextLength()).isEmpty()) return false;
        if (step == 2) {
            check.accept(true, "new source version re-enables granular markers after a fallback snapshot");
            return true;
        }
        var image = new BufferedImage(source.getComponent().getWidth(), source.getComponent().getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setClip(0, 0, image.getWidth(), image.getHeight());
            g.setColor(source.getColorsScheme().getDefaultBackground()); g.fillRect(0, 0, image.getWidth(), image.getHeight());
            marker.getLineMarkerRenderer().paint(source, g, new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        } finally { g.dispose(); }
        var area = com.intellij.openapi.diff.LineStatusMarkerDrawUtil.getGutterArea(source);
        int x = (area.first + area.second) / 2;
        int color = source.getColorsScheme().getDefaultBackground().getRGB();
        int solid = 0, missing = 0;
        for (int line : new int[]{3, 8, 22, 40}) {
            int y = source.logicalPositionToXY(new LogicalPosition(line, 0)).y;
            for (int dy = 3; dy < source.getLineHeight() - 3; dy++) {
                if (image.getRGB(x, y + dy) == color) solid++; else missing++;
            }
        }
        var root = Path.of(System.getProperty("diffnav.smoke.report")).getParent();
        javax.imageio.ImageIO.write(image, "png", root.resolve("marker-only.png").toFile());
        Files.writeString(root.resolve("smoke-report.txt"), "MARKER-RANGES " + git.getRanges() + "; untouchedColor=" + solid + "; untouchedGaps=" + missing + "\n", StandardOpenOption.APPEND);
        check.accept(missing == 0 && solid > 0, "untouched lines of a staged new file remain hollow despite two local differences [gaps=" + missing + "]");
        int changedY = source.logicalPositionToXY(new LogicalPosition(14, 0)).y;
        int centerY = changedY + source.getLineHeight() / 2;
        check.accept(image.getRGB(area.first + 1, centerY) != color && image.getRGB(area.second - 2, centerY) == color,
                "only edited source line uses native mixed half filling");
        var renderer = (ActiveGutterRenderer) marker.getLineMarkerRenderer();
        var gutter = ((com.intellij.openapi.editor.ex.EditorEx) source).getGutterComponentEx();
        int stableY = source.logicalPositionToXY(new LogicalPosition(8, 0)).y + source.getLineHeight() / 2;
        renderer.canDoAction(source, new java.awt.event.MouseEvent(gutter, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, stableY, 0, false));
        check.accept(renderer.getTooltipText().contains("已暂存") && !renderer.getTooltipText().contains("另有修改"), "untouched source line tooltip reports staged without combined-block mixed state");
        renderer.canDoAction(source, new java.awt.event.MouseEvent(gutter, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, changedY + source.getLineHeight() / 2, 0, false));
        check.accept(renderer.getTooltipText().contains("已暂存，另有修改"), "edited source line tooltip reports its own mixed state");
        int deletionY = source.logicalPositionToXY(new LogicalPosition(34, 0)).y;
        renderer.canDoAction(source, new java.awt.event.MouseEvent(gutter, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, deletionY, 0, false));
        check.accept(renderer.getTooltipText().contains("行间删除"), "deleted staged line is described as a line-boundary change");
        for (int line : new int[]{33, 34}) {
            int y = source.logicalPositionToXY(new LogicalPosition(line, 0)).y;
            int center = image.getRGB(x, y + source.getLineHeight() / 2);
            check.accept(center == color, "deletion does not fill neighboring source line " + (line + 1));
        }
        var text = new java.util.ArrayList<String>();
        for (var inlay : source.getInlayModel().getAfterLineEndElementsInRange(0, source.getDocument().getTextLength())) {
            var r = inlay.getRenderer(); if (!r.getClass().getName().contains("SourceMarkerService$Label")) continue;
            var accessor = r.getClass().getDeclaredMethod("text"); accessor.setAccessible(true); text.add((String) accessor.invoke(r));
        }
        check.accept(text.contains("已暂存") && text.contains("已暂存，另有修改") && text.stream().anyMatch(t -> t.contains("行间删除")),
                "source annotations distinguish unchanged staged spans, the changed line and deletion boundary");
        var component = source.getComponent();
        var frame = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        var fg = frame.createGraphics(); try { component.paint(fg); } finally { fg.dispose(); }
        javax.imageio.ImageIO.write(frame, "png", root.resolve("source-granularity.png").toFile());
        var watch = field(renderer, "watch");
        var original = field(renderer, "original");
        var fallbackField = watch.getClass().getDeclaredField("fallback"); fallbackField.setAccessible(true);
        fallbackField.set(watch, field(watch, "projection"));
        var restore = watch.getClass().getDeclaredMethod("restore"); restore.setAccessible(true); restore.invoke(watch);
        var service = project.getService(io.github.youjiaxing.diffnav.SourceMarkerService.class);
        service.refresh(); service.refresh();
        check.accept(marker.getLineMarkerRenderer() == original, "repeated refresh retains native renderer for a failed snapshot instead of blank proxy");
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> {
            int at = source.getDocument().getText().indexOf("unique local replacement line 14");
            source.getDocument().replaceString(at, at + "unique local replacement line 14".length(), "another local replacement line 14");
        });
        step = 2; ticks = 0; return false;
    }
    private void nativeLineBoundaries() throws Exception {
        var model = Class.forName("io.github.youjiaxing.diffnav.SourceMarkerRanges");
        var build = model.getDeclaredMethod("build", int.class, int.class, List.class, List.class); build.setAccessible(true);
        String[] texts = {"", "a", "a\n", "a\nb", "a\nb\n", "a\n\n", "\n", "b\n"};
        int cases = 0;
        for (String head : texts) for (String index : texts) for (String local : texts) {
            int indexCount = nativeLines(index), localCount = nativeLines(local);
            List<?> value;
            try { value = (List<?>) build.invoke(null, indexCount, localCount, changes(head, index), changes(index, local)); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError("Native line offsets failed for " + List.of(head.replace('\n', '|'), index.replace('\n', '|'), local.replace('\n', '|')), failure.getCause()); }
            for (var item : value) {
                var range = (com.intellij.openapi.vcs.ex.Range) item;
                if (range.getLine1() < 0 || range.getLine2() < range.getLine1() || range.getLine2() > localCount)
                    throw new AssertionError("Projected native newline range exceeds source: " + range);
            }
            cases++;
        }
        check.accept(cases == 512, "512 real ComparisonManager newline/empty-file combinations stay within source coordinates");
    }
    private static int nativeLines(String text) {
        int count = 1;
        for (int k = 0; k < text.length(); k++) if (text.charAt(k) == '\n') count++;
        return count;
    }
    private static List<SelectedLines.Change> changes(String before, String after) {
        return com.intellij.diff.comparison.ComparisonManager.getInstance().compareLines(before, after,
                com.intellij.diff.comparison.ComparisonPolicy.DEFAULT, new com.intellij.openapi.progress.EmptyProgressIndicator()).stream()
                .map(f -> new SelectedLines.Change(f.getStartLine1(), f.getEndLine1(), f.getStartLine2(), f.getEndLine2())).toList();
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
}
