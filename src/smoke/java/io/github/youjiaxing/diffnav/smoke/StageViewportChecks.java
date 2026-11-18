package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import git4idea.index.ui.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.BiConsumer;

/** Real long-file Stage, with viewport sampling after the replacement viewer has settled. */
final class StageViewportChecks {
    private final Project project;
    private final BiConsumer<Boolean, String> check;
    private int step, waits, relativeY, lineHeight;
    private GitStageDiffRequestProcessor stage;
    private String unrelated, localText;
    private volatile Boolean menuEnabled;
    private volatile Throwable menuFailure;
    private boolean menuUpdating;
    private ViewportFrameChecks frames;
    StageViewportChecks(Project project, BiConsumer<Boolean, String> check) { this.project = project; this.check = check; }
    boolean tick() throws Exception {
        if (step == 12) return true;
        if (menuFailure != null) throw new AssertionError(menuFailure);
        if (step != 11 && ++waits > 150) throw new AssertionError("Viewport check timeout step=" + step + " reason="
                + project.getService(io.github.youjiaxing.diffnav.StageNavigationService.class).getLastReason());
        var manager = FileEditorManager.getInstance(project);
        if (step == 0) {
            var file = file();
            unrelated = git("show", ":b.go"); localText = Files.readString(Path.of(file.getPath()));
            manager.openTextEditor(new OpenFileDescriptor(project, file), true); next();
        } else if (step == 1) {
            if (git4idea.index.GitStageTrackerKt.status(git4idea.index.GitStageTracker.getInstance(project), file()) == null) return false;
            if (!Boolean.TRUE.equals(menuEnabled)) {
                if (menuEnabled != null) { menuEnabled = null; menuUpdating = false; }
                if (!menuUpdating) {
                    menuUpdating = true;
                    var action = ActionManager.getInstance().getAction("Git.Stage.Compare.Local.Staged");
                    var editor = manager.getSelectedTextEditor();
                    var context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                            .setParent(DataManager.getInstance().getDataContext(editor.getContentComponent()))
                            .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor).add(CommonDataKeys.VIRTUAL_FILE, file()).build();
                    var event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.EDITOR_POPUP, context);
                    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try { com.intellij.openapi.application.ReadAction.run(() -> action.update(event)); menuEnabled = event.getPresentation().isEnabled(); }
                        catch (Throwable error) { menuFailure = error; }
                    });
                }
                return false;
            }
            invoke("Git.Stage.Compare.Local.Staged", manager.getSelectedTextEditor()); next();
        } else if (step == 2) {
            for (var editor : manager.getAllEditors()) if (editor instanceof DiffEditorViewerFileEditor diff
                    && diff.getEditorViewer() instanceof GitStageDiffRequestProcessor p) stage = p;
            if (stage == null || !(stage.getActiveViewer() instanceof OnesideTextDiffViewer one) || one.hasPendingRediff() || waits < 10) return false;
            position(one.getEditor(), 179, true); next();
        } else if (step == 3) {
            var editor = ((OnesideTextDiffViewer) stage.getActiveViewer()).getEditor();
            remember(editor, 179);
            if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", editor)) return false;
            frames = new ViewportFrameChecks(stage, 179);
            next();
        } else if (step == 4) {
            if (!ready(selected(179))) return false;
            if (waits < 15) return false;
            assertPosition(right(), 179, "first new-file Stage retains viewport across one-to-two-side replacement");
            frames.finish(check);
            position(right(), 229, false); next();
        } else if (step == 5) {
            remember(right(), 229);
            if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", right())) return false;
            frames = new ViewportFrameChecks(stage, 229);
            next();
        } else if (step == 6) {
            if (!ready(selected(179) + selected(229)) || waits < 15) return false;
            assertPosition(right(), 229, "consecutive Stage retains viewport in the latest viewer");
            frames.finish(check);
            position(right(), 259, true); next();
        } else if (step == 7) {
            remember(right(), 259);
            if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", right())) return false;
            frames = new ViewportFrameChecks(stage, 259);
            next();
        } else if (step == 8) {
            if (!ready(selected(179) + selected(229) + selected(259)) || waits < 15) return false;
            assertPosition(right(), 259, "Stage keeps selected content visible even when caret is elsewhere");
            frames.finish(check);
            check.accept(Files.readString(Path.of(file().getPath())).equals(localText) && git("show", ":b.go").equals(unrelated),
                    "viewport restoration preserves LOCAL and unrelated INDEX");
            position(right(), 119, true);
            right().getSelectionModel().setSelection(right().getDocument().getLineStartOffset(99), right().getDocument().getLineStartOffset(159));
            right().getScrollingModel().disableAnimation();
            right().getScrollingModel().scrollVertically(right().getScrollingModel().getVerticalScrollOffset() + 3);
            right().getScrollingModel().enableAnimation();
            check.accept(right().getScrollingModel().getVerticalScrollOffset() % right().getLineHeight() != 0,
                    "cross-screen selection starts with a partially clipped top line");
            next();
        } else if (step == 9) {
            remember(right(), 119);
            if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", right())) return false;
            frames = new ViewportFrameChecks(stage, 119);
            next();
        } else if (step == 10) {
            String expected = java.util.stream.IntStream.range(99, 159).mapToObj(StageViewportChecks::selected).collect(java.util.stream.Collectors.joining())
                    + selected(179) + selected(229) + selected(259);
            if (!ready(expected) || waits < 15) return false;
            assertPosition(right(), 119, "Stage of a selection taller than the screen keeps its visible content in place");
            frames.finish(check);
            for (var file : manager.getOpenFiles()) manager.closeFile(file);
            next();
        } else if (step == 11) {
            if (cancelChecks == null) cancelChecks = new StageRefreshCancelChecks(project, check);
            if (!cancelChecks.tick()) return false;
            next(); return true;
        }
        return false;
    }
    private StageRefreshCancelChecks cancelChecks;
    private void next() { step++; waits = 0; }
    private com.intellij.openapi.vfs.VirtualFile file() { return LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/review-viewport.txt"); }
    private Editor right() { return ((TwosideTextDiffViewer) stage.getActiveViewer()).getEditor2(); }
    private boolean ready(String expected) {
        return stage.getActiveViewer() instanceof TwosideTextDiffViewer view && !view.hasPendingRediff()
                && stage.getCurrentChange().getUserObject() instanceof GitFileStatusNode node && node.getKind() == NodeKind.UNSTAGED
                && node.getFilePath().getName().equals("review-viewport.txt") && view.getEditor1().getDocument().getText().equals(expected)
                && git("show", ":review-viewport.txt").equals(expected);
    }
    private static String selected(int line) { return String.format("unique viewport line %03d with enough text for selection\n", line + 1); }
    private void position(Editor editor, int line, boolean separateCaret) {
        editor.getSettings().setUseSoftWraps(false);
        editor.getCaretModel().moveToOffset(separateCaret ? 0 : editor.getDocument().getLineStartOffset(line));
        editor.getSelectionModel().setSelection(editor.getDocument().getLineStartOffset(line), editor.getDocument().getLineEndOffset(line));
        var scrolling = editor.getScrollingModel();
        scrolling.disableAnimation();
        scrolling.scrollVertically(editor.logicalPositionToXY(new LogicalPosition(line, 0)).y - editor.getLineHeight() * 6);
        scrolling.enableAnimation();
    }
    private void remember(Editor editor, int line) {
        relativeY = editor.logicalPositionToXY(new LogicalPosition(line, 0)).y - editor.getScrollingModel().getVisibleArea().y;
        lineHeight = editor.getLineHeight();
        check.accept(editor.getScrollingModel().getVisibleArea().height > 0 && editor.getScrollingModel().getVerticalScrollOffset() > 1000,
                "long-file Stage starts with a real viewport far from the top");
    }
    private void assertPosition(Editor editor, int line, String label) {
        var area = editor.getScrollingModel().getVisibleArea();
        int after = editor.logicalPositionToXY(new LogicalPosition(line, 0)).y - area.y;
        check.accept(area.height > 0 && after >= 0 && after + lineHeight <= area.height && Math.abs(after - relativeY) <= lineHeight,
                label + " [beforeY=" + relativeY + ", afterY=" + after + ", scroll=" + area.y + ", height=" + area.height
                        + ", caret=" + editor.getCaretModel().getLogicalPosition().line + ", viewer=" + System.identityHashCode(stage.getActiveViewer()) + "]");
    }
    private void invoke(String id, Editor editor) {
        var context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .setParent(DataManager.getInstance().getDataContext(editor.getContentComponent()))
                .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor)
                .add(CommonDataKeys.VIRTUAL_FILE, FileDocumentManager.getInstance().getFile(editor.getDocument())).build();
        ActionUtil.invokeAction(ActionManager.getInstance().getAction(id), context, "DiffNavSmoke", null, null);
    }
    private String git(String... args) {
        try {
            var command = new java.util.ArrayList<String>(); command.add("git"); command.add("-C"); command.add(project.getBasePath()); command.addAll(java.util.List.of(args));
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? output.replace("\r\n", "\n") : "";
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
}
