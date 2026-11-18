package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import git4idea.index.ui.*;
import io.github.youjiaxing.diffnav.StageRefreshService;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;

/** Holds a replacement editor undisplayed to exercise user intent after the real INDEX write. */
final class StageRefreshCancelChecks {
    private final Project project;
    private final BiConsumer<Boolean, String> check;
    private int step, waits, scenario, userScroll;
    private GitStageDiffRequestProcessor stage;
    private Disposable listener;
    private Object originalViewer;
    private boolean inputApplied;
    private TwosideTextDiffViewer heldViewer;
    private Throwable failure;
    private String expected = "";
    StageRefreshCancelChecks(Project project, BiConsumer<Boolean, String> check) { this.project = project; this.check = check; }
    boolean tick() throws Exception {
        if (scenario == 4) return true;
        if (failure != null) throw new AssertionError("Post-write cancellation callback: " + failure, failure);
        if (++waits > 150) throw new AssertionError("Post-write cancellation timeout scenario=" + scenario + " step=" + step);
        var manager = FileEditorManager.getInstance(project);
        if (step == 0) {
            manager.openTextEditor(new OpenFileDescriptor(project, file("review-viewport-cancel.txt")), true); next();
        } else if (step == 1) {
            var editor = manager.getSelectedTextEditor();
            if (editor == null || git4idea.index.GitStageTrackerKt.status(git4idea.index.GitStageTracker.getInstance(project), file("review-viewport-cancel.txt")) == null) return false;
            com.intellij.openapi.actionSystem.ex.ActionUtil.invokeAction(com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("Git.Stage.Compare.Local.Staged"),
                    com.intellij.ide.DataManager.getInstance().getDataContext(editor.getContentComponent()), "DiffNavSmoke", null, null); next();
        } else if (step == 2) {
            for (var editor : manager.getAllEditors()) if (editor instanceof DiffEditorViewerFileEditor diff
                    && diff.getEditorViewer() instanceof GitStageDiffRequestProcessor p) stage = p;
            if (stage == null || waits < 10) return false;
            var editor = localEditor();
            if (editor == null) return false;
            SelectionUxChecks.select(editor, String.format("unique viewport line %03d", 180 + scenario));
            editor.getScrollingModel().disableAnimation();
            editor.getScrollingModel().scrollVertically(editor.offsetToXY(editor.getSelectionModel().getSelectionStart()).y - editor.getLineHeight() * 6);
            editor.getScrollingModel().enableAnimation(); next();
        } else if (step == 3) {
            if (!SelectionUxChecks.enabled(localEditor(), "DiffNavigation.SelectedLines")) return false;
            expected += String.format("unique viewport line %03d with enough text for selection\n", 180 + scenario);
            originalViewer = stage.getActiveViewer(); inputApplied = false; heldViewer = null;
            listener = Disposer.newDisposable("Post-write input check");
            Disposer.register(project, listener);
            com.intellij.diff.DiffExtension.EP_NAME.getPoint().registerExtension(new com.intellij.diff.DiffExtension() {
                @Override public void onViewerCreated(com.intellij.diff.FrameDiffTool.DiffViewer viewer, com.intellij.diff.DiffContext context,
                                                      com.intellij.diff.requests.DiffRequest request) {
                    if (context.getProject() == project && "Stage".equals(context.getUserData(com.intellij.diff.util.DiffUserDataKeys.PLACE))) replacement(viewer);
                }
            }, listener);
            if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", localEditor())) throw new AssertionError("Stage became unavailable");
            next();
        } else if (step == 4) {
            if (!inputApplied || waits < 15) return false;
            check.accept(index().equals(expected), label() + " preserves the successful partial Stage");
            if (scenario < 2) {
                if (localEditor() == null || stage.getActiveViewer() instanceof com.intellij.diff.tools.util.base.DiffViewerBase base && base.hasPendingRediff()) return false;
                check.accept(localEditor().getScrollingModel().getVerticalScrollOffset() == userScroll,
                        label() + " wins over delayed viewport restoration");
            } else {
                var selected = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).getCurrentWindow().getSelectedFile();
                check.accept(scenario == 2 ? file("clean.go").equals(selected) : !manager.isFileOpen(preview), label() + " is not reversed by delayed refresh");
            }
            Disposer.dispose(listener);
            scenario++; step = 0; waits = 0;
            for (var file : manager.getOpenFiles()) manager.closeFile(file);
        }
        return scenario == 4;
    }
    private com.intellij.openapi.vfs.VirtualFile preview;
    private void replacement(com.intellij.diff.FrameDiffTool.DiffViewer viewer) {
        if (inputApplied || viewer == originalViewer || !(viewer instanceof TwosideTextDiffViewer two)) return;
        heldViewer = two;
        var editor = two.getEditor2();
        two.getComponent().setVisible(false);
        scheduleInput(two, 0);
    }
    private void scheduleInput(TwosideTextDiffViewer two, int attempts) {
        var editor = two.getEditor2();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                if (two != heldViewer || two.isDisposed()) return;
                var service = project.getService(StageRefreshService.class);
                var ownField = service.getClass().getDeclaredField("own"); ownField.setAccessible(true);
                if (stage.getActiveViewer() != two || ownField.getInt(service) != 0) {
                    if (attempts > 100) throw new AssertionError("Replacement viewer did not become active");
                    var timer = new javax.swing.Timer(20, null);
                    timer.addActionListener(event -> { timer.stop(); scheduleInput(two, attempts + 1); });
                    timer.start();
                    return;
                }
                check.accept(index().equals(expected), label() + " starts after Git has actually written INDEX");
                var pendingField = service.getClass().getDeclaredField("pending"); pendingField.setAccessible(true);
                var refresh = ((Map<?, ?>) pendingField.get(service)).get(stage);
                check.accept(refresh != null, label() + " runs while position restoration is still pending");
                preview = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).getCurrentWindow().getSelectedFile();
                if (scenario == 0) {
                    var component = editor.getContentComponent();
                    component.dispatchEvent(new MouseWheelEvent(component, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 10, 10, 0, false,
                            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1));
                } else if (scenario == 1) {
                    var component = editor.getContentComponent();
                    component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 10, 10, 1, false, MouseEvent.BUTTON1));
                    editor.getCaretModel().moveToOffset(editor.getDocument().getLineStartOffset(39));
                } else if (scenario == 2) {
                    FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file("clean.go")), true);
                } else FileEditorManager.getInstance(project).closeFile(preview);
                if (refresh != null) {
                    // File editor selection notifications may arrive later, so observe the real selected window too.
                    var canceled = refresh.getClass().getDeclaredField("canceled"); canceled.setAccessible(true);
                    if (scenario < 2) check.accept((boolean) canceled.get(refresh), label() + " cancels the pending recovery");
                }
                if (!editor.isDisposed()) two.getComponent().setVisible(true);
                if (scenario < 2) {
                    editor.getScrollingModel().disableAnimation();
                    editor.getScrollingModel().scrollVertically(editor.getLineHeight() * 35);
                    editor.getScrollingModel().enableAnimation();
                    userScroll = editor.getScrollingModel().getVerticalScrollOffset();
                }
                inputApplied = true;
            } catch (Throwable error) { failure = error; }
            finally { if ((inputApplied || failure != null) && !editor.isDisposed()) two.getComponent().setVisible(true); }
        }, ModalityState.nonModal());
    }
    private String label() { return new String[]{"post-write wheel", "post-write click", "post-write file switch", "post-write preview close"}[scenario]; }
    private void next() { step++; waits = 0; }
    private Editor localEditor() {
        if (stage.getActiveViewer() instanceof TwosideTextDiffViewer two && !two.hasPendingRediff()) return two.getEditor2();
        if (stage.getActiveViewer() instanceof OnesideTextDiffViewer one && !one.hasPendingRediff()) return one.getEditor();
        return null;
    }
    private com.intellij.openapi.vfs.VirtualFile file(String name) { return LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + name); }
    private String index() {
        try {
            var process = new ProcessBuilder("git", "-C", project.getBasePath(), "show", ":review-viewport-cancel.txt").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? output.replace("\r\n", "\n") : "";
        } catch (Exception error) { throw new RuntimeException(error); }
    }
}
