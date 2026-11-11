package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import git4idea.index.ui.*;
import io.github.youjiaxing.diffnav.NavigationSettings;
import java.nio.file.*;
import java.util.function.BiConsumer;

/** Uses the actual native action and actual index writes, without manually reselecting after Stage. */
final class NewFileReviewChecks {
    private final Project project;
    private final BiConsumer<Boolean, String> check;
    private int step, waits;
    private GitStageDiffRequestProcessor stage;
    private volatile Boolean enabled;
    private volatile Throwable updateFailure;
    private String unrelated;
    NewFileReviewChecks(Project project, BiConsumer<Boolean, String> check) { this.project = project; this.check = check; }
    boolean tick() {
        if (step == 100) return true;
        if (++waits > 100) throw new AssertionError("New-file review timed out at " + step + " current="
                + (stage == null ? null : stage.getCurrentChange()) + " viewer=" + (stage == null ? null : stage.getActiveViewer()));
        if (updateFailure != null) throw new AssertionError(updateFailure);
        var manager = FileEditorManager.getInstance(project);
        if (step == 0) {
            open("review-new.txt"); unrelated = git("show", ":b.go"); next();
        } else if (step == 1) {
            var file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/review-new.txt");
            if (git4idea.index.GitStageTrackerKt.status(git4idea.index.GitStageTracker.getInstance(project), file) == null) return false;
            updateMenu(); next();
        } else if (step == 2) {
            if (enabled == null) return false;
            check.accept(enabled, "Compare with Staged is enabled for pure untracked source");
            invoke("Git.Stage.Compare.Local.Staged", manager.getSelectedTextEditor()); next();
        } else if (step == 3) {
            for (var editor : manager.getAllEditors()) if (editor instanceof DiffEditorViewerFileEditor diff
                    && diff.getEditorViewer() instanceof GitStageDiffRequestProcessor p) stage = p;
            if (stage == null || !(stage.getActiveViewer() instanceof OnesideTextDiffViewer one) || one.hasPendingRediff()) return false;
            check.accept(node().getKind() == NodeKind.UNTRACKED && node().getFilePath().getName().equals("review-new.txt"), "untracked source menu opens native empty-to-local Commit preview");
            check.accept(git("ls-files", "--", "review-new.txt").isEmpty(), "opening new-file comparison creates no index entry");
            select(one.getEditor(), "second"); if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", one.getEditor())) return false;
            check.accept(!stageActionEnabled(one.getEditor()), "old selection cannot Stage again while write and baseline refresh are pending");
            next();
        } else if (step == 4) {
            if (!git("show", ":review-new.txt").equals("second\n")) return false;
            if (!ready("second\n")) return false;
            check.accept(true, "partial Stage automatically replaces untracked preview with INDEX-to-local diff");
            check.accept(two().getEditor2().getDocument().getText().equals("first\nsecond\nthird\nfourth\n"), "partial Stage retains full local file in right side");
            select(two().getEditor2(), "third"); if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", two().getEditor2())) return false; next();
        } else if (step == 5) {
            if (!git("show", ":review-new.txt").equals("second\nthird\n") || !ready("second\nthird\n")) return false;
            check.accept(true, "consecutive Stage refreshes the same unstaged group to latest index");
            check.accept(read("review-new.txt").equals("first\nsecond\nthird\nfourth\n") && git("show", ":b.go").equals(unrelated), "new-file repeated Stage preserves worktree and unrelated index");
            open("review-cancel.txt"); next();
        } else if (step == 6) {
            NavigationSettings.get(project).getState().enabled = false;
            updateMenu(); next();
        } else if (step == 7) {
            if (enabled == null) return false;
            check.accept(!enabled, "disabled plugin preserves native disabled comparison for untracked source");
            NavigationSettings.get(project).getState().enabled = true;
            NavigationSettings.get(project).getState().openNewFilesInDiff = false;
            NavigationSettings.get(project).getState().autoStart = false;
            invoke("Git.Stage.Compare.Local.Staged", manager.getSelectedTextEditor()); next();
        } else if (step == 8) {
            if (!(stage.getActiveViewer() instanceof OnesideTextDiffViewer one) || one.hasPendingRediff()
                    || !node().getFilePath().getName().equals("review-cancel.txt")) return false;
            check.accept(true, "explicit untracked menu works with automatic new-file navigation and review start disabled");
            select(one.getEditor(), "beta"); if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", one.getEditor())) return false;
            open("clean.go"); next();
        } else if (step == 9) {
            if (waits < 15) return false;
            check.accept(git("show", ":review-cancel.txt").isEmpty(), "leaving preview before deferred Stage cancels the index write");
            check.accept(manager.getSelectedTextEditor() != null && FileDocumentManager.getInstance().getFile(manager.getSelectedTextEditor().getDocument()).getName().equals("clean.go"), "canceled Stage does not return focus to preview");
            open("review-cancel.txt"); next();
        } else if (step == 10) {
            invoke("Git.Stage.Compare.Local.Staged", manager.getSelectedTextEditor()); next();
        } else if (step == 11) {
            if (!(stage.getActiveViewer() instanceof OnesideTextDiffViewer view) || view.hasPendingRediff() || !node().getFilePath().getName().equals("review-cancel.txt")) return false;
            select(view.getEditor(), "beta"); if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", view.getEditor())) return false;
            next();
        } else if (step == 12) {
            if (!(stage.getActiveViewer() instanceof TwosideTextDiffViewer view) || view.hasPendingRediff() || !view.getEditor1().getDocument().getText().equals("beta\n")) return false;
            select(view.getEditor2(), "gamma"); if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", view.getEditor2())) return false;
            var previewFile = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).getCurrentWindow().getSelectedFile();
            open("clean.go");
            com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).getCurrentWindow().setSelectedComposite(previewFile, true);
            next();
        } else if (step == 13) {
            if (waits < 15) return false;
            check.accept(git("show", ":review-cancel.txt").equals("beta\n"), "switch away and back cannot resurrect an old Stage candidate");
            open("review-cancel.txt"); next();
        } else if (step == 14) {
            invoke("Git.Stage.Compare.Local.Staged", manager.getSelectedTextEditor()); next();
        } else if (step == 15) {
            if (!(stage.getActiveViewer() instanceof TwosideTextDiffViewer view) || view.hasPendingRediff() || !view.getEditor1().getDocument().getText().equals("beta\n")) return false;
            select(view.getEditor2(), "gamma"); if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", view.getEditor2())) return false;
            var tree = stage.getStageTree();
            var key = new java.awt.event.KeyEvent(tree, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                    java.awt.event.KeyEvent.VK_DOWN, java.awt.event.KeyEvent.CHAR_UNDEFINED);
            for (var listener : tree.getKeyListeners()) listener.keyPressed(key);
            next();
        } else if (step == 16) {
            if (waits < 15) return false;
            var tree = stage.getStageTree();
            var other = GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(tree).find(w -> w.getFilePath().getName().equals("main.go") && ((GitFileStatusNode) w.getUserObject()).getKind() == NodeKind.UNSTAGED);
            GitStageDiffPreviewHandler.INSTANCE.selectChange(tree, other); next();
        } else if (step == 17) {
            if (waits < 5) return false;
            check.accept(stage.getStageTree().selectedStatusNodes().single().getFilePath().getName().equals("main.go"), "manual Commit selection during Stage is not overwritten");
            open("review-race.txt"); next();
        } else if (step == 18) {
            invoke("Git.Stage.Compare.Local.Staged", manager.getSelectedTextEditor()); next();
        } else if (step == 19) {
            if (!(stage.getActiveViewer() instanceof OnesideTextDiffViewer one) || one.hasPendingRediff() || !node().getFilePath().getName().equals("review-race.txt")) return false;
            select(one.getEditor(), "two"); if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", one.getEditor())) return false;
            var tree = stage.getStageTree();
            var other = GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(tree).find(w -> w.getFilePath().getName().equals("main.go") && ((GitFileStatusNode) w.getUserObject()).getKind() == NodeKind.UNSTAGED);
            GitStageDiffPreviewHandler.INSTANCE.selectChange(tree, other); stage.setCurrentChange(other); next();
        } else if (step == 20) {
            if (waits < 15) return false;
            check.accept(node().getFilePath().getName().equals("main.go"), "switching preview cancels obsolete Stage without a blocking dialog");
            NavigationSettings.get(project).getState().autoStart = true;
            NavigationSettings.get(project).getState().openNewFilesInDiff = true;
            project.getService(io.github.youjiaxing.diffnav.StageNavigationService.class).settingsChanged();
            for (var file : manager.getOpenFiles()) manager.closeFile(file);
            step = 100; return true;
        }
        return false;
    }
    private void next() { step++; waits = 0; }
    private GitFileStatusNode node() { return (GitFileStatusNode) stage.getCurrentChange().getUserObject(); }
    private TwosideTextDiffViewer two() { return (TwosideTextDiffViewer) stage.getActiveViewer(); }
    private boolean ready(String expected) {
        return stage.getActiveViewer() instanceof TwosideTextDiffViewer view && !view.hasPendingRediff()
                && node().getKind() == NodeKind.UNSTAGED && node().getFilePath().getName().equals("review-new.txt")
                && view.getEditor1().getDocument().getText().equals(expected);
    }
    private void open(String name) { FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project,
            LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + name)), true); }
    private DataContext context(Editor editor) { return SimpleDataContext.builder().setParent(DataManager.getInstance().getDataContext(editor.getContentComponent()))
            .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.VIRTUAL_FILE, FileDocumentManager.getInstance().getFile(editor.getDocument())).build(); }
    private void updateMenu() {
        enabled = null;
        var action = ActionManager.getInstance().getAction("Git.Stage.Compare.Local.Staged");
        var event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.EDITOR_POPUP, context(FileEditorManager.getInstance(project).getSelectedTextEditor()));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { com.intellij.openapi.application.ReadAction.run(() -> action.update(event)); enabled = event.getPresentation().isEnabled(); }
            catch (Throwable failure) { updateFailure = failure; }
        });
    }
    private void invoke(String id, Editor editor) { ActionUtil.invokeAction(ActionManager.getInstance().getAction(id), context(editor), "DiffNavSmoke", null, null); }
    private boolean stageActionEnabled(Editor editor) {
        var action = ActionManager.getInstance().getAction("DiffNavigation.SelectedLines");
        var event = AnActionEvent.createFromAnAction(action, null, "DiffNavSmoke", context(editor));
        action.update(event); return event.getPresentation().isEnabled();
    }
    private void select(Editor editor, String line) {
        int start = editor.getDocument().getText().indexOf(line);
        editor.getSelectionModel().setSelection(start, start + line.length());
    }
    private String read(String name) { try { return Files.readString(Path.of(project.getBasePath(), name)); } catch (Exception error) { throw new RuntimeException(error); } }
    private String git(String... args) {
        try {
            var command = new java.util.ArrayList<String>(java.util.List.of("git", "-C", project.getBasePath()));
            command.addAll(java.util.List.of(args)); var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            process.waitFor(); return result.replace("\r\n", "\n");
        } catch (Exception error) { throw new RuntimeException(error); }
    }
}
