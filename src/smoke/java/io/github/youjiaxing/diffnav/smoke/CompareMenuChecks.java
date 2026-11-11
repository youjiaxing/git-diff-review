package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import git4idea.index.ui.GitFileStatusNode;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import git4idea.index.ui.NodeKind;
import io.github.youjiaxing.diffnav.NavigationSettings;
import io.github.youjiaxing.diffnav.StageNavigationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;

/** Calls the real source-editor comparison action, including cold Commit initialization. */
final class CompareMenuChecks {
    private static final String COMPARE = "Git.Stage.Compare.Local.Staged";
    private final Project project;
    private final BiConsumer<Boolean, String> check;
    private int step, ticks;
    private GitStageDiffRequestProcessor stage;
    private VirtualFile preview;
    private String worktree, unrelatedIndex;
    private int settle;
    private com.intellij.openapi.fileEditor.impl.EditorWindow firstWindow, secondWindow;
    private GitStageDiffRequestProcessor firstProcessor;
    CompareMenuChecks(Project project, BiConsumer<Boolean, String> check) { this.project = project; this.check = check; }

    boolean tick() {
        if (++ticks > 300) throw new AssertionError("Compare menu timed out at " + step + " editors="
                + java.util.Arrays.toString(FileEditorManager.getInstance(project).getAllEditors())
                + " files=" + java.util.Arrays.toString(FileEditorManager.getInstance(project).getOpenFiles()));
        var manager = FileEditorManager.getInstance(project);
        if (step == 0) {
            ToolWindowManager.getInstance(project).getToolWindow("Commit").hide();
            open("menu.txt");
            worktree = readLocal("menu.txt"); unrelatedIndex = git("show", ":b.go");
            step++;
        } else if (step == 1) {
            if (git4idea.index.GitStageTrackerKt.status(git4idea.index.GitStageTracker.Companion.getInstance(project), file("menu.txt")) == null) return false;
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 2) {
            for (var editor : manager.getAllEditors()) {
                if (editor instanceof DiffEditorViewerFileEditor diff && diff.getEditorViewer() instanceof GitStageDiffRequestProcessor processor) {
                    stage = processor; preview = editor.getFile();
                }
            }
            if (stage == null) {
                // The old action deterministically opens a standalone Diff instead.
                if (java.util.Arrays.stream(manager.getAllEditors()).anyMatch(e -> e instanceof DiffEditorViewerFileEditor))
                    throw new AssertionError("source Compare with Staged opened standalone Diff instead of Commit review preview");
                return false;
            }
            if (!at("menu.txt")) return false;
            check.accept(ToolWindowManager.getInstance(project).getToolWindow("Commit").isVisible(), "explicit comparison shows hidden Commit panel");
            check.accept(manager.isFileOpen(file("menu.txt")), "explicit comparison preserves source tab");
            check.accept(two().getEditor1().getDocument().getText().equals("head\nanchor\ntail\n"), "comparison uses INDEX as left version");
            check.accept(two().getEditor2().getDocument().getText().contains("menuOne\nmenuTwo"), "comparison uses LOCAL as right version");
            Editor right = two().getEditor2();
            int start = right.getDocument().getText().indexOf("menuOne");
            right.getSelectionModel().setSelection(start, start + "menuOne\n".length());
            if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", right)) return false; step++;
        } else if (step == 3) {
            if (!git("show", ":menu.txt").equals("head\nmenuOne\nanchor\ntail\n")) return false;
            check.accept(true, "source-menu Commit preview stages only selected line");
            check.accept(readLocal("menu.txt").equals(worktree), "source-menu staging preserves worktree");
            check.accept(git("show", ":b.go").equals(unrelatedIndex), "source-menu staging preserves unrelated index entries");
            var settings = NavigationSettings.get(project).getState();
            settings.targetScope = NavigationSettings.TargetScope.STAGED_ONLY;
            settings.autoStart = false;
            project.getService(StageNavigationService.class).settingsChanged();
            open("menu.txt"); step++;
        } else if (step == 4) {
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 5) {
            if (!at("menu.txt")) return false;
            check.accept(manager.getSelectedEditor(preview) != null && manager.getSelectedTextEditor() == null, "existing Commit preview reused with explicit unstaged target despite staged-only scope");
            check.accept(!project.getService(StageNavigationService.class).statusText().endsWith("On"), "explicit comparison preserves auto-start off");
            check.accept(java.util.Arrays.stream(manager.getAllEditors()).filter(e -> e instanceof DiffEditorViewerFileEditor).count() == 1, "repeated source comparisons do not create standalone diff tabs");
            NavigationSettings.get(project).getState().enabled = false;
            project.getService(StageNavigationService.class).settingsChanged();
            open("menu.txt"); step++;
        } else if (step == 6) {
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 7) {
            if (!standalone()) return false;
            check.accept(true, "disabled plugin keeps native standalone comparison");
            closeStandalone();
            NavigationSettings.get(project).getState().enabled = true;
            NavigationSettings.get(project).getState().autoStart = true;
            NavigationSettings.get(project).getState().targetScope = NavigationSettings.TargetScope.FOLLOW_TARGET;
            project.getService(StageNavigationService.class).settingsChanged();
            open("added/staged.go"); step++;
        } else if (step == 8) {
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 9) {
            if (!standalone()) return false;
            check.accept(true, "fully staged source keeps native comparison instead of selecting staged group");
            closeStandalone();
            open("menu.txt"); step++;
        } else if (step == 10) {
            invoke(COMPARE, manager.getSelectedTextEditor());
            open("clean.go"); settle = 0; step++;
        } else if (step == 11) {
            if (++settle < 4) return false;
            check.accept(source("clean.go") && !standalone(), "switching source before deferred comparison cancels without late native fallback");
            open("menu.txt"); step++;
        } else if (step == 12) {
            invoke(COMPARE, manager.getSelectedTextEditor());
            var tree = stage.getStageTree();
            var key = new java.awt.event.KeyEvent(tree, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                    java.awt.event.KeyEvent.VK_DOWN, java.awt.event.KeyEvent.CHAR_UNDEFINED);
            for (var listener : tree.getKeyListeners()) listener.keyPressed(key);
            var other = git4idea.index.ui.GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(tree).find(w ->
                    w.getFilePath().getName().equals("main.go") && ((GitFileStatusNode) w.getUserObject()).getKind() == NodeKind.UNSTAGED);
            git4idea.index.ui.GitStageDiffPreviewHandler.INSTANCE.selectChange(tree, other);
            settle = 0; step++;
        } else if (step == 13) {
            if (++settle < 4) return false;
            check.accept(source("menu.txt") && !standalone(), "manual Commit tree input cancels pending comparison without fallback");
            invoke(COMPARE, manager.getSelectedTextEditor());
            open("main.go"); invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 14) {
            if (!at("main.go")) return false;
            check.accept(!standalone(), "rapid source comparisons honor only the last requested file");
            open("menu.txt"); step++;
        } else if (step == 15) {
            // A source switch parks the old main.go preview; an explicit comparison must replace it.
            var tree = stage.getStageTree();
            var nodes = tree.getRoot().preorderEnumeration();
            var paths = new java.util.ArrayList<javax.swing.tree.TreePath>();
            while (nodes.hasMoreElements()) {
                var item = nodes.nextElement();
                if (item instanceof javax.swing.tree.DefaultMutableTreeNode node && node.getUserObject() instanceof GitFileStatusNode status
                        && status.getKind() == NodeKind.UNSTAGED && (status.getFilePath().getName().equals("main.go")
                        || status.getFilePath().getName().equals("c.go"))) paths.add(new javax.swing.tree.TreePath(node.getPath()));
            }
            tree.setSelectionPaths(paths.toArray(javax.swing.tree.TreePath[]::new));
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 16) {
            if (!at("menu.txt")) return false;
            check.accept(stage.getStageTree().getSelectionCount() == 1 && !standalone(), "explicit comparison replaces multi-selection and parked preview with requested unstaged file");
            invoke("DiffNavigation.ToggleReview", two().getEditor2());
            open("menu.txt"); step++;
        } else if (step == 17) {
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 18) {
            if (!at("menu.txt")) return false;
            check.accept(!project.getService(StageNavigationService.class).statusText().endsWith("On"), "explicit comparison preserves an already paused review session");
            open("menu.txt"); step++;
        } else if (step == 19) {
            var editor = manager.getSelectedTextEditor();
            editor.getCaretModel().moveToOffset(0);
            invoke(COMPARE, editor);
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> editor.getDocument().replaceString(0, 4, "HEAD"));
            step++;
        } else if (step == 20) {
            if (!at("menu.txt")) return false;
            check.accept(!standalone(), "unmappable source caret keeps confirmed Commit diff");
            check.accept(readLocal("menu.txt").equals(worktree) && FileDocumentManager.getInstance().isDocumentUnsaved(two().getEditor2().getDocument()), "explicit comparison leaves unsaved source changes unsaved");
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> two().getEditor2().getDocument().replaceString(0, 4, "head"));
            NavigationSettings.get(project).getState().openNewFilesInDiff = false;
            open("added/partial_added.go"); step++;
        } else if (step == 21) {
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 22) {
            if (!at("added/partial_added.go")) return false;
            check.accept(true, "explicit comparison ignores new-file automatic navigation preference");
            NavigationSettings.get(project).getState().openNewFilesInDiff = true;
            invoke("Git.Stage.Compare.Three.Versions", two().getEditor2()); step++;
        } else if (step == 23) {
            if (!standalone()) return false;
            check.accept(true, "other native comparison menus retain their original diff");
            closeStandalone(); open("menuA/item.txt"); step++;
        } else if (step == 24) {
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 25) {
            if (!at("menuA/item.txt")) return false;
            open("menuB/item.txt"); step++;
        } else if (step == 26) {
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 27) {
            if (!at("menuB/item.txt")) return false;
            check.accept(two().getEditor2().getDocument().getText().contains("newB"), "same-named source files select exact full path");
            open("menu-renamed.txt"); step++;
        } else if (step == 28) {
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 29) {
            if (!standalone()) return false;
            check.accept(true, "renamed source falls back to native comparison");
            closeStandalone();
            open("menuA/item.txt"); step++;
        } else if (step == 30) {
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 31) {
            if (!at("menuA/item.txt")) return false;
            firstWindow = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
            firstProcessor = stage;
            secondWindow = firstWindow.split(javax.swing.SwingConstants.VERTICAL, true, file("menuB/item.txt"), true);
            step++;
        } else if (step == 32) {
            if (!source("menuB/item.txt") || com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).getCurrentWindow() != secondWindow) return false;
            invoke(COMPARE, manager.getSelectedTextEditor()); step++;
        } else if (step == 33) {
            var selected = secondWindow.getSelectedComposite();
            if (selected == null || !(selected.getSelectedEditor() instanceof DiffEditorViewerFileEditor diff)
                    || !(diff.getEditorViewer() instanceof GitStageDiffRequestProcessor processor)) return false;
            stage = processor;
            if (!at("menuB/item.txt")) return false;
            check.accept(secondWindow != firstWindow && stage != firstProcessor, "source comparison opens Commit processor in the initiating split");
            check.accept(preview.equals(secondWindow.getSelectedFile()) && firstWindow.findFileComposite(preview) != null,
                    "comparison preserves existing Commit preview in the other split");
            secondWindow.unsplit(true);
            NavigationSettings.get(project).getState().autoStart = true;
            project.getService(StageNavigationService.class).settingsChanged();
            for (var file : manager.getOpenFiles()) manager.closeFile(file);
            return true;
        }
        return false;
    }
    private boolean at(String name) {
        if (stage == null || stage.getCurrentChange() == null || !(stage.getActiveViewer() instanceof TwosideTextDiffViewer view)
                || view.hasPendingRediff() || FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return false;
        var node = (GitFileStatusNode) stage.getCurrentChange().getUserObject();
        return node.getFilePath().getVirtualFile().equals(file(name)) && node.getKind() == NodeKind.UNSTAGED
                && FileDocumentManager.getInstance().getFile(view.getEditor2().getDocument()).equals(file(name));
    }
    private TwosideTextDiffViewer two() { return (TwosideTextDiffViewer) stage.getActiveViewer(); }
    private boolean source(String name) {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        return editor != null && file(name).equals(FileDocumentManager.getInstance().getFile(editor.getDocument()));
    }
    private boolean standalone() {
        return java.util.Arrays.stream(FileEditorManager.getInstance(project).getAllEditors()).anyMatch(e ->
                e instanceof DiffEditorViewerFileEditor diff && !(diff.getEditorViewer() instanceof GitStageDiffRequestProcessor));
    }
    private void closeStandalone() {
        var manager = FileEditorManager.getInstance(project);
        for (var editor : manager.getAllEditors()) if (editor instanceof DiffEditorViewerFileEditor diff
                && !(diff.getEditorViewer() instanceof GitStageDiffRequestProcessor)) manager.closeFile(editor.getFile());
    }
    private VirtualFile file(String name) { return LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + name); }
    private void open(String name) { FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file(name)), true); }
    private void invoke(String id, Editor editor) {
        var context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .setParent(DataManager.getInstance().getDataContext(editor.getContentComponent()))
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, editor)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, FileDocumentManager.getInstance().getFile(editor.getDocument())).build();
        ActionUtil.invokeAction(ActionManager.getInstance().getAction(id), context, "DiffNavSmoke", null, null);
    }
    private String readLocal(String name) {
        try { return Files.readString(Path.of(project.getBasePath(), name)); } catch (Exception error) { throw new RuntimeException(error); }
    }
    private String git(String... args) {
        try {
            var command = new java.util.ArrayList<String>(java.util.List.of("git", "-C", project.getBasePath()));
            command.addAll(java.util.List.of(args));
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() != 0) throw new AssertionError(result);
            return result.replace("\r\n", "\n").replace("\uFEFF", "");
        } catch (Exception error) { throw new RuntimeException(error); }
    }
}
