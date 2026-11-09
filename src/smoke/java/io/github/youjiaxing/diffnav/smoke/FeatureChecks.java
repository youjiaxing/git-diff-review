package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.EditorTabDiffPreview;
import com.intellij.openapi.vfs.LocalFileSystem;
import git4idea.index.ui.*;
import io.github.youjiaxing.diffnav.CommitTreeFollowService;
import java.nio.file.Path;
import java.util.function.BiConsumer;

/** Real staging UI and passive Commit tree behavior; runs after navigation regression. */
final class FeatureChecks {
    private final Project project;
    private final GitStageDiffRequestProcessor stage;
    private final EditorTabDiffPreview preview;
    private final BiConsumer<Boolean, String> check;
    private int step;
    private int ticks;
    private String previous;
    private boolean dialogDone;
    private Throwable dialogFailure;
    FeatureChecks(Project project, GitStageDiffRequestProcessor stage, EditorTabDiffPreview preview, BiConsumer<Boolean, String> check) {
        this.project = project; this.stage = stage; this.preview = preview; this.check = check;
    }
    boolean tick() throws Exception {
        if (dialogFailure != null) throw new AssertionError("Preview interaction failed", dialogFailure);
        if (++ticks > 300) throw new AssertionError("Feature checks timed out at " + step);
        var tree = stage.getStageTree();
        if (step == 0) {
            selectDiff("lines.txt", NodeKind.UNSTAGED); step++;
        } else if (step == 1) {
            if (!at("lines.txt", NodeKind.UNSTAGED)) return false;
            Editor right = two().getEditor2();
            selectLine(right, "two");
            floatingStage(right);
            step++;
        } else if (step == 2) {
            if (!index().equals("head\ntwo\nanchor\ntail\n")) return false;
            if (wrapper("lines.txt", NodeKind.STAGED) == null) return false;
            check.accept(true, "UI stages selected line only inside one hunk");
            check.accept(java.nio.file.Files.readString(Path.of(project.getBasePath(), "lines.txt")).replace("\r\n", "\n").contains("one\ntwo\nthree"), "UI staging leaves worktree unchanged");
            selectDiff("lines.txt", NodeKind.STAGED); step++;
        } else if (step == 3) {
            if (!at("lines.txt", NodeKind.STAGED) || !two().getEditor2().getDocument().getText().contains("two\n")) return false;
            selectLine(two().getEditor2(), "two");
            invoke("DiffNavigation.SelectedLines", two().getEditor2()); step++;
        } else if (step == 4) {
            if (!index().equals("head\nanchor\ntail\n")) return false;
            check.accept(true, "UI unstages selected line without changing worktree");
            open("main.go"); step++;
        } else if (step == 5) {
            if (!selected("main.go", NodeKind.UNSTAGED)) return false;
            check.accept(source("main.go"), "tab follow selects Commit file without replacing source editor");
            open("partial.go"); step++;
        } else if (step == 6) {
            if (!selected("partial.go", NodeKind.UNSTAGED)) return false;
            check.accept(true, "ordinary source prefers unstaged group");
            var staged = wrapper("partial.go", NodeKind.STAGED);
            GitStageDiffPreviewHandler.INSTANCE.selectChange(tree, staged);
            open("b.go"); open("partial.go"); step++;
        } else if (step == 7) {
            if (!selected("partial.go", NodeKind.UNSTAGED)) return false;
            check.accept(source("partial.go"), "same file source tab follows unstaged group instead of old staged selection");
            var root = tree.getRoot();
            var paths = new java.util.ArrayList<javax.swing.tree.TreePath>();
            var nodes = root.preorderEnumeration();
            while (nodes.hasMoreElements()) {
                var item = nodes.nextElement();
                if (item instanceof javax.swing.tree.DefaultMutableTreeNode n && n.getUserObject() instanceof GitFileStatusNode status
                        && status.getKind() == NodeKind.UNSTAGED && (status.getFilePath().getPath().equals(project.getBasePath() + "/main.go")
                        || status.getFilePath().getPath().equals(project.getBasePath() + "/c.go"))) {
                    paths.add(new javax.swing.tree.TreePath(n.getPath()));
                }
            }
            tree.setSelectionPaths(paths.toArray(javax.swing.tree.TreePath[]::new));
            open("lines.txt"); step++;
        } else if (step == 8) {
            if (ticks % 3 != 0) return false;
            check.accept(tree.getSelectionCount() == 2, "switching source tab preserves Commit multi-selection");
            tree.clearSelection();
            open("main.go"); step++;
        } else if (step == 9) {
            if (!selected("main.go", NodeKind.UNSTAGED)) return false;
            check.accept(true, "tab follow resumes on next tab change after multi-selection");
            previous = tree.selectedStatusNodes().single().getFilePath().getPath();
            open("clean.go"); step++;
        } else if (step == 10) {
            if (ticks % 3 != 0) return false;
            check.accept(tree.selectedStatusNodes().single().getFilePath().getPath().equals(previous), "unchanged file absent from Commit preserves previous tree selection");
            selectDiff("replace.txt", NodeKind.UNSTAGED); step++;
        } else if (step == 11) {
            if (!at("replace.txt", NodeKind.UNSTAGED)) return false;
            selectLine(two().getEditor2(), "newOneLong");
            watchPreview(false);
            invoke("DiffNavigation.SelectedLines", two().getEditor2()); step++;
        } else if (step == 12) {
            if (!dialogDone) return false;
            check.accept(readIndex("replace.txt").equals("oldOne\noldTwo\ntail\n"), "canceling ambiguous replacement preview leaves index untouched");
            watchPreview(true);
            invoke("DiffNavigation.SelectedLines", two().getEditor2()); step++;
        } else if (step == 13) {
            if (!dialogDone || !readIndex("replace.txt").equals("newOneLong\noldTwo\ntail\n")) return false;
            check.accept(true, "confirming replacement preview stages only the reviewed result");
            check.accept(java.nio.file.Files.readString(Path.of(project.getBasePath(), "replace.txt")).replace("\r\n", "\n").contains("newOneLong\nnewTwoLong"), "preview confirmation leaves worktree unmodified");
            selectDiff("new-no-eol.txt", NodeKind.UNTRACKED); step++;
        } else if (step == 14) {
            if (!atAny("new-no-eol.txt", NodeKind.UNTRACKED)) return false;
            Editor editor = activeEditor();
            editor.getSelectionModel().setSelection(0, editor.getDocument().getTextLength());
            invoke("DiffNavigation.SelectedLines", editor); step++;
        } else if (step == 15) {
            if (wrapper("new-no-eol.txt", NodeKind.STAGED) == null || !readIndex("new-no-eol.txt").equals("newOnly")) return false;
            check.accept(true, "new file without final newline stages from native one-sided viewer");
            selectDiff("new-no-eol.txt", NodeKind.STAGED); step++;
        } else if (step == 16) {
            if (!atAny("new-no-eol.txt", NodeKind.STAGED)) return false;
            Editor editor = activeEditor();
            editor.getSelectionModel().setSelection(0, editor.getDocument().getTextLength());
            invoke("DiffNavigation.SelectedLines", editor); step++;
        } else if (step == 17) {
            if (!missingIndex("new-no-eol.txt")) return false;
            check.accept(java.nio.file.Files.readString(Path.of(project.getBasePath(), "new-no-eol.txt")).equals("newOnly"), "Unstage last new-file line removes index entry and preserves local file");
            selectDiff("deleted.txt", NodeKind.UNSTAGED); step++;
        } else if (step == 18) {
            if (!atAny("deleted.txt", NodeKind.UNSTAGED)) return false;
            selectLine(activeEditor(), "delOne"); invoke("DiffNavigation.SelectedLines", activeEditor()); step++;
        } else if (step == 19) {
            if (!readIndex("deleted.txt").equals("delTwo\n")) return false;
            if (wrapper("deleted.txt", NodeKind.STAGED) == null) return false;
            check.accept(!java.nio.file.Files.exists(Path.of(project.getBasePath(), "deleted.txt")), "selected old-side deletion preserves unselected index line and missing worktree");
            selectDiff("deleted.txt", NodeKind.STAGED); step++;
        } else if (step == 20) {
            if (!atAny("deleted.txt", NodeKind.STAGED)) return false;
            var left = two().getEditor1(); selectLine(left, "delOne"); invoke("DiffNavigation.SelectedLines", left); step++;
        } else if (step == 21) {
            if (!readIndex("deleted.txt").equals("delOne\ndelTwo\n")) return false;
            check.accept(!java.nio.file.Files.exists(Path.of(project.getBasePath(), "deleted.txt")), "Unstage selected deletion restores index only");
            selectDiff("b.go", NodeKind.STAGED); step++;
        } else if (step == 22) {
            if (!at("b.go", NodeKind.STAGED)) return false;
            navigateTo("c.go", "C()"); step++;
        } else if (step == 23) {
            if (!at("c.go", NodeKind.UNSTAGED)) return false;
            open("main.go"); step++;
        } else if (step == 24) {
            if (!selected("main.go", NodeKind.UNSTAGED)) return false;
            check.accept(source("main.go"), "following unrelated source tab keeps source editor active");
            preview.openPreview(true); step++;
        } else if (step == 25) {
            if (!at("c.go", NodeKind.UNSTAGED) || FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return false;
            check.accept(true, "returning to review preview restores file parked before passive tree follow");
            invoke("DiffNavigation.Back", two().getEditor2()); step++;
        } else if (step == 26) {
            if (!at("b.go", NodeKind.UNSTAGED)) return false;
            check.accept(true, "passive tree follow preserves Back history while default Back uses unstaged version");
            open("main.go"); step++;
        } else if (step == 27) {
            if (!selected("main.go", NodeKind.UNSTAGED)) return false;
            project.getService(CommitTreeFollowService.class).request(true);
            tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), 0, 2, 2, 1, false, java.awt.event.MouseEvent.BUTTON1));
            GitStageDiffPreviewHandler.INSTANCE.selectChange(tree, wrapper("b.go", NodeKind.STAGED)); step++;
        } else if (step == 28) {
            if (ticks % 3 != 0) return false;
            check.accept(selected("b.go", NodeKind.STAGED), "manual tree selection invalidates queued automatic follow");
            return true;
        }
        return false;
    }
    private void floatingStage(Editor editor) throws Exception {
        Class<?> type = Class.forName("io.github.youjiaxing.diffnav.LineStaging");
        var keyField = type.getDeclaredField("CONTROLLER"); keyField.setAccessible(true);
        Object controller = editor.getUserData((com.intellij.openapi.util.Key<?>) keyField.get(null));
        var offer = type.getDeclaredMethod("offer", Editor.class); offer.setAccessible(true); offer.invoke(controller, editor);
        var popupField = type.getDeclaredField("popup"); popupField.setAccessible(true);
        var popup = (com.intellij.openapi.ui.popup.JBPopup) popupField.get(controller);
        if (popup == null) throw new AssertionError("Selection popup wasn't created");
        var button = findButton(popup.getContent(), "Stage 选中行");
        check.accept(button != null, "selection floating toolbar exposes Stage selected lines");
        button.doClick();
    }
    private void navigateTo(String name, String expression) {
        var file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + name);
        var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
        int offset = document.getText().indexOf(expression);
        com.intellij.codeInsight.navigation.actions.IdeKt.navigateRequest(project,
                new com.intellij.platform.backend.navigation.impl.SourceNavigationRequest(file, document.createRangeMarker(offset, offset), null),
                DataManager.getInstance().getDataContext(two().getEditor2().getContentComponent()));
    }
    private void watchPreview(boolean confirm) {
        dialogDone = false;
        final int[] attempts = {0};
        var timer = new javax.swing.Timer(100, null);
        timer.addActionListener(event -> {
            try {
                if (++attempts[0] > 200) throw new AssertionError("Expected replacement preview dialog");
                for (var window : java.awt.Window.getWindows()) {
                    if (!(window instanceof javax.swing.JDialog dialog) || !dialog.isShowing() || !dialog.getTitle().equals("确认暂存内容")) continue;
                    boolean resultFound = false;
                    for (var editor : com.intellij.openapi.editor.EditorFactory.getInstance().getAllEditors()) {
                        if (javax.swing.SwingUtilities.isDescendingFrom(editor.getComponent(), dialog)
                                && editor.getDocument().getText().equals("newOneLong\noldTwo\ntail\n")) resultFound = true;
                    }
                    check.accept(resultFound, "ambiguous replacement preview shows exact proposed index result");
                    javax.swing.JButton button = findButton(dialog, confirm ? "确认 Stage" : "取消");
                    if (button == null && !confirm) button = findButton(dialog, "Cancel");
                    if (button == null) throw new AssertionError("Preview button missing");
                    timer.stop(); button.doClick(); dialogDone = true; return;
                }
            } catch (Throwable error) { timer.stop(); dialogFailure = error; }
        });
        timer.start();
    }
    private static javax.swing.JButton findButton(java.awt.Component component, String text) {
        if (component instanceof javax.swing.JButton button && text.equals(button.getText())) return button;
        if (component instanceof java.awt.Container parent) for (var child : parent.getComponents()) {
            var found = findButton(child, text); if (found != null) return found;
        }
        return null;
    }
    private void selectLine(Editor editor, String text) {
        int start = editor.getDocument().getText().indexOf(text + "\n");
        if (start < 0) throw new AssertionError("Missing selected line " + text);
        editor.getSelectionModel().setSelection(start, start + text.length());
    }
    private void invoke(String id, Editor editor) {
        ActionUtil.invokeAction(ActionManager.getInstance().getAction(id), DataManager.getInstance().getDataContext(editor.getContentComponent()), "DiffNavSmoke", null, null);
    }
    private boolean at(String name, NodeKind kind) {
        if (!(stage.getActiveViewer() instanceof TwosideTextDiffViewer two) || two.hasPendingRediff() || stage.getCurrentChange() == null) return false;
        var node = (GitFileStatusNode) stage.getCurrentChange().getUserObject();
        var file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(two.getEditor2().getDocument());
        String path = file instanceof git4idea.index.vfs.GitIndexVirtualFile index ? index.getFilePath().getPath() : file == null ? "" : file.getPath();
        return node.getKind() == kind && node.getFilePath().getPath().equals(project.getBasePath() + "/" + name) && path.equals(node.getFilePath().getPath());
    }
    private boolean atAny(String name, NodeKind kind) {
        if (!(stage.getActiveViewer() instanceof com.intellij.diff.tools.util.base.DiffViewerBase viewer) || viewer.hasPendingRediff()
                || stage.getCurrentChange() == null) return false;
        var node = (GitFileStatusNode) stage.getCurrentChange().getUserObject();
        return node.getKind() == kind && node.getFilePath().getPath().equals(project.getBasePath() + "/" + name);
    }
    private Editor activeEditor() {
        return stage.getActiveViewer() instanceof TwosideTextDiffViewer two ? two.getCurrentEditor()
                : ((com.intellij.diff.tools.util.side.OnesideTextDiffViewer) stage.getActiveViewer()).getEditor();
    }
    private TwosideTextDiffViewer two() { return (TwosideTextDiffViewer) stage.getActiveViewer(); }
    private com.intellij.openapi.vcs.changes.ChangeViewDiffRequestProcessor.Wrapper wrapper(String name, NodeKind kind) {
        return GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(stage.getStageTree()).find(w -> w.getFilePath().getPath().equals(project.getBasePath() + "/" + name) && ((GitFileStatusNode) w.getUserObject()).getKind() == kind);
    }
    private void selectDiff(String name, NodeKind kind) {
        var target = wrapper(name, kind);
        if (target == null) throw new AssertionError("Missing fixture " + name + " " + kind);
        var tree = stage.getStageTree();
        tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 2, 2, 1, false, java.awt.event.MouseEvent.BUTTON1));
        GitStageDiffPreviewHandler.INSTANCE.selectChange(stage.getStageTree(), target); stage.setCurrentChange(target); preview.openPreview(true);
    }
    private void open(String name) {
        var file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + name);
        FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0), true);
    }
    private boolean source(String name) {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        var file = editor == null ? null : com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file != null && file.getPath().equals(project.getBasePath() + "/" + name);
    }
    private boolean selected(String name, NodeKind kind) {
        var nodes = stage.getStageTree().selectedStatusNodes().toList();
        return nodes.size() == 1 && nodes.getFirst().getFilePath().getPath().equals(project.getBasePath() + "/" + name) && nodes.getFirst().getKind() == kind;
    }
    private String index() throws Exception {
        return readIndex("lines.txt");
    }
    private String readIndex(String name) throws Exception {
        var p = new ProcessBuilder("git", "-C", project.getBasePath(), "show", ":" + name).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new AssertionError("Unable to read fixture index");
        return out.replace("\r\n", "\n").replace("\uFEFF", "");
    }
    private boolean missingIndex(String name) throws Exception {
        var p = new ProcessBuilder("git", "-C", project.getBasePath(), "ls-files", "--", name).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new AssertionError("Unable to list index");
        return out.isEmpty();
    }
}
