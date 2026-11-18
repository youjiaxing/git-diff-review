package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import java.nio.file.*;
import java.util.function.BiConsumer;
import javax.swing.*;

/** Samples the displayed preview throughout the actual asynchronous refresh, not just its endpoint. */
final class ViewportFrameChecks {
    private final GitStageDiffRequestProcessor stage;
    private final int line, expectedY;
    private final Timer timer;
    private final Path directory;
    private int samples, missing, maxShift;
    private String worst = "";
    ViewportFrameChecks(GitStageDiffRequestProcessor stage, int line) {
        this.stage = stage; this.line = line;
        directory = Path.of(System.getProperty("diffnav.smoke.report")).getParent().resolve("viewport-frames").resolve("line-" + (line + 1));
        expectedY = coordinate(editor());
        capture("before");
        timer = new Timer(10, event -> com.intellij.openapi.application.ReadAction.run(this::sample)); timer.start();
        com.intellij.openapi.util.Disposer.register(stage, () -> timer.stop());
    }
    private Editor editor() {
        if (stage.getActiveViewer() instanceof TwosideTextDiffViewer two) return two.getEditor2();
        if (stage.getActiveViewer() instanceof OnesideTextDiffViewer one) return one.getEditor();
        return null;
    }
    private int coordinate(Editor editor) {
        return SwingUtilities.convertPoint(editor.getContentComponent(), 0, editor.logicalPositionToXY(new LogicalPosition(line, 0)).y, stage.getComponent()).y;
    }
    private void sample() {
        samples++;
        var editor = editor();
        if (editor == null || !editor.getContentComponent().isShowing()) { if (missing++ == 0) capture("missing"); return; }
        int actualY = coordinate(editor);
        int shift = Math.abs(actualY - expectedY);
        if (shift > maxShift) {
            maxShift = shift;
            worst = "sample=" + samples + ", y=" + actualY + ", expected=" + expectedY + ", scroll="
                    + editor.getScrollingModel().getVerticalScrollOffset() + ", viewer=" + System.identityHashCode(stage.getActiveViewer());
            capture("shift");
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("trace.csv"), samples + "," + actualY + "," + expectedY + "," + shift + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private void capture(String name) {
        try {
            var component = stage.getComponent();
            if (component.getWidth() <= 0 || component.getHeight() <= 0) return;
            var image = new java.awt.image.BufferedImage(component.getWidth(), component.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { component.printAll(graphics); } finally { graphics.dispose(); }
            Files.createDirectories(directory);
            javax.imageio.ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    void finish(BiConsumer<Boolean, String> check) {
        timer.stop(); sample(); capture("after");
        check.accept(samples > 10 && missing == 0 && maxShift <= 1,
                "Stage refresh has no intermediate viewport jump at line " + (line + 1) + " [samples=" + samples + ", missing=" + missing + ", maxShift=" + maxShift + ", " + worst + "]");
    }
}
