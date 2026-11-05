package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vcs.changes.EditorTabDiffPreview;
import com.intellij.openapi.wm.ToolWindowManager;
import git4idea.index.ui.GitFileStatusNode;
import git4idea.index.ui.GitStageDiffPreviewHandler;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import git4idea.index.ui.GitStagePanel;
import git4idea.index.ui.NodeKind;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.Timer;

/** Only packaged into the separate, disposable sandbox test plugin. */
public final class SmokeActivity implements StartupActivity.DumbAware {
    private Project project;
    private Path report;
    private GitStageDiffRequestProcessor stage;
    private EditorTabDiffPreview preview;
    private int step;
    private int ticks;
    private int passed;
    private boolean busy;

    @Override public void runActivity(Project project) {
        String output = System.getProperty("diffnav.smoke.report");
        if (output == null) return;
        this.project = project;
        report = Path.of(output);
        log("START " + project.getBasePath());
        DumbService.getInstance(project).runWhenSmart(() -> {
            var timer = new Timer(200, event -> {
                if (busy) return;
                busy = true;
                ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    if (++ticks > 600) throw new AssertionError("Timed out at step " + step);
                    tick();
                    if (step == 99) ((Timer) event.getSource()).stop();
                } catch (Throwable error) {
                    log("FAIL step " + step + ": " + error);
                    for (var frame : error.getStackTrace()) log("  " + frame);
                    ((Timer) event.getSource()).stop();
                    step = 99;
                } finally { busy = false; }
                }, com.intellij.openapi.application.ModalityState.nonModal());
            });
            timer.start();
        });
    }

    private void tick() {
        if (ticks % 15 == 0 && stage != null) {
            var current = stage.getCurrentChange();
            log("WAIT step=" + step + " change=" + (current == null ? null : current.getUserObject())
                    + " viewer=" + stage.getActiveViewer()
                    + " files=" + java.util.Arrays.toString(FileEditorManager.getInstance(project).getOpenFiles()));
        }
        if (step == 0) {
            var window = ToolWindowManager.getInstance(project).getToolWindow("Commit");
            if (window == null) return;
            window.show();
            var panel = find(window.getContentManager().getComponent(), GitStagePanel.class);
            if (panel == null) return;
            var all = GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(panel.getTree());
            var main = all.find(w -> w.getFilePath().getName().equals("main.go"));
            if (main == null) return;
            preview = (EditorTabDiffPreview) DiffDataKeys.EDITOR_TAB_DIFF_PREVIEW.getData(
                    DataManager.getInstance().getDataContext(panel));
            if (preview == null) return;
            GitStageDiffPreviewHandler.INSTANCE.selectChange(panel.getTree(), main);
            preview.openPreview(true);
            step = 1;
        } else if (step == 1) {
            for (var editor : FileEditorManager.getInstance(project).getAllEditors()) {
                if (editor instanceof DiffEditorViewerFileEditor diff
                        && diff.getEditorViewer() instanceof GitStageDiffRequestProcessor processor) stage = processor;
            }
            if (!at("main.go", NodeKind.UNSTAGED)) return;
            check(FileEditorManager.getInstance(project).isFileOpen(preview.getPreviewFile()), "Commit preview is open");
            call("B()");
            step = 2;
        } else if (step == 2) {
            if (!at("b.go", NodeKind.STAGED)) return;
            check(right().getCaretModel().getLogicalPosition().line == 2, "native declaration to staged-only function B at line 3");
            check(localTabs() == 0, "native declaration creates no source tab");
            call("C()");
            step = 3;
        } else if (step == 3) {
            if (!at("c.go", NodeKind.UNSTAGED)) return;
            check(right().getCaretModel().getLogicalPosition().line == 2, "native declaration to unstaged C at line 3");
            action("DiffNavigation.Back");
            step = 4;
        } else if (step == 4) {
            if (!at("b.go", NodeKind.STAGED)) return;
            check(right().getCaretModel().getLogicalPosition().line == 2, "review Back restores B");
            action("DiffNavigation.Back");
            step = 5;
        } else if (step == 5) {
            if (!at("main.go", NodeKind.UNSTAGED)) return;
            check(right().getCaretModel().getLogicalPosition().line == 2, "review Back restores main");
            action("DiffNavigation.Forward");
            step = 6;
        } else if (step == 6) {
            if (!at("b.go", NodeKind.STAGED)) return;
            action("DiffNavigation.Forward");
            step = 7;
        } else if (step == 7) {
            if (!at("c.go", NodeKind.UNSTAGED)) return;
            check(localTabs() == 0, "review Forward preserves shared preview");
            call("Clean()");
            step = 8;
        } else if (step == 8) {
            var current = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (current == null) return;
            check(current.getDocument().getText().contains("func Clean()"), "unchanged target uses normal source editor");
            check(localTabs() == 1 && FileEditorManager.getInstance(project).isFileOpen(preview.getPreviewFile()),
                    "unchanged target preserves Commit preview");
            var normal = FileEditorManager.getInstance(project).getSelectedTextEditor();
            normal.getCaretModel().moveToOffset(normal.getDocument().getText().indexOf("B()"));
            var ctx = DataManager.getInstance().getDataContext(normal.getContentComponent());
            ActionUtil.invokeAction(ActionManager.getInstance().getAction("GotoDeclaration"), ctx, "DiffNavSmoke", null, null);
            step = 9;
        } else if (step == 9) {
            var normal = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (normal == null || !normal.getDocument().getText().contains("func B()")) return;
            check(localTabs() == 2, "ordinary editor navigation stays in ordinary editor");
            var partial = GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(stage.getStageTree())
                    .find(w -> w.getFilePath().getName().equals("partial.go")
                            && ((GitFileStatusNode) w.getUserObject()).getKind() == NodeKind.STAGED);
            if (partial == null) throw new AssertionError("Missing partial staging fixture");
            GitStageDiffPreviewHandler.INSTANCE.selectChange(stage.getStageTree(), partial);
            stage.setCurrentChange(partial);
            preview.openPreview(true);
            step = 10;
        } else if (step == 10) {
            if (!at("partial.go", NodeKind.STAGED)) return;
            check(!right().getDocument().getText().contains("worktree-only"), "staged view excludes worktree-only edit");
            var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/b.go");
            var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
            var ctx = DataManager.getInstance().getDataContext(right().getContentComponent());
            right().getCaretModel().moveToOffset(right().getDocument().getText().indexOf("B()"));
            // Test history across different INDEX/worktree contents using a final
            // native source request; resolving mismatched INDEX PSI is out of scope.
            com.intellij.codeInsight.navigation.actions.IdeKt.navigateRequest(project,
                    new com.intellij.platform.backend.navigation.impl.SourceNavigationRequest(file,
                            document.createRangeMarker(document.getText().indexOf("B()"), document.getText().indexOf("B()")), null), ctx);
            step = 11;
        } else if (step == 11) {
            if (!at("b.go", NodeKind.STAGED)) return;
            action("DiffNavigation.Back");
            step = 12;
        } else if (step == 12) {
            if (!at("partial.go", NodeKind.STAGED)) return;
            check(right().getCaretModel().getLogicalPosition().line == 2, "Back restores INDEX coordinates in partially staged file");
            check(!right().getDocument().getText().contains("worktree-only"), "Back preserves staged version");
            check(localTabs() == 2, "existing ordinary source tabs are preserved");
            log("PASS " + passed + " integration checks");
            step = 99;
        }
    }

    private boolean at(String name, NodeKind kind) {
        if (stage == null || stage.getCurrentChange() == null
                || !(stage.getActiveViewer() instanceof TwosideTextDiffViewer two) || two.hasPendingRediff()) return false;
        var node = (GitFileStatusNode) stage.getCurrentChange().getUserObject();
        return node.getKind() == kind && node.getFilePath().getName().equals(name);
    }
    private long localTabs() {
        return java.util.Arrays.stream(FileEditorManager.getInstance(project).getOpenFiles())
                .filter(file -> file.isInLocalFileSystem() && file.getPath().startsWith(project.getBasePath())).count();
    }
    private com.intellij.openapi.editor.Editor right() { return ((TwosideTextDiffViewer) stage.getActiveViewer()).getEditor2(); }
    private void call(String expression) {
        var editor = right();
        int offset = editor.getDocument().getText().indexOf(expression);
        check(offset >= 0, "find call " + expression);
        editor.getCaretModel().moveToOffset(offset);
        action("GotoDeclaration");
        log("NATIVE returned, files=" + java.util.Arrays.toString(FileEditorManager.getInstance(project).getOpenFiles()));
    }
    private void action(String id) {
        var context = DataManager.getInstance().getDataContext(right().getContentComponent());
        if (id.equals("GotoDeclaration")) {
            var psi = com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE.getData(context);
            var e = com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.getData(context);
            var pdm = com.intellij.psi.PsiDocumentManager.getInstance(project);
            log("RESOLVE psi=" + psi + " vf=" + (psi == null ? null : psi.getVirtualFile())
                    + " offset=" + e.getCaretModel().getOffset() + " committed=" + pdm.isCommitted(e.getDocument()));
        }
        ActionUtil.invokeAction(ActionManager.getInstance().getAction(id), context, "DiffNavSmoke", null, null);
    }
    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++;
        log("OK " + label);
    }
    private void log(String text) {
        try { Files.writeString(report, text + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static <T> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container parent) for (var child : parent.getComponents()) {
            T result = find(child, type);
            if (result != null) return result;
        }
        return null;
    }
}
