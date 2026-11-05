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
    private com.intellij.usages.UsageView usages;
    private com.intellij.usages.UsageInfo2UsageAdapter usage;
    private FeatureChecks features;

    @Override public void runActivity(Project project) {
        String output = System.getProperty("diffnav.smoke.report");
        if (output == null) return;
        this.project = project;
        report = Path.of(output);
        log("START " + project.getBasePath());
        var diagnostic = new Timer(10000, event -> {
            if (step == 99) { ((Timer) event.getSource()).stop(); return; }
            log("DIAGNOSTIC step=" + step + " busy=" + busy + " modality=" + com.intellij.openapi.application.ModalityState.current());
        });
        diagnostic.start();
        DumbService.getInstance(project).runWhenSmart(() -> {
            var timer = new Timer(200, event -> {
                if (busy) return;
                if (com.intellij.openapi.application.ModalityState.current() != com.intellij.openapi.application.ModalityState.nonModal()) return;
                busy = true;
                javax.swing.SwingUtilities.invokeLater(() -> com.intellij.openapi.application.WriteIntentReadAction.run(() -> {
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
                }));
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
            if (window == null) {
                if (ticks % 15 == 0) log("WAIT no Commit toolwindow; windows=" + java.util.Arrays.toString(ToolWindowManager.getInstance(project).getToolWindowIds()));
                return;
            }
            window.show();
            var panel = find(window.getContentManager().getComponent(), GitStagePanel.class);
            if (panel == null) { if (ticks % 15 == 0) log("WAIT staging panel not initialized"); return; }
            var all = GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(panel.getTree());
            var main = all.find(w -> w.getFilePath().getName().equals("main.go"));
            if (main == null) { if (ticks % 15 == 0) log("WAIT main file status unavailable; changes=" + all); return; }
            preview = (EditorTabDiffPreview) DiffDataKeys.EDITOR_TAB_DIFF_PREVIEW.getData(
                    DataManager.getInstance().getDataContext(panel));
            if (preview == null) { if (ticks % 15 == 0) log("WAIT preview unavailable"); return; }
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
            if (normal != null && normal.getDocument().getText().contains("func B()")) {
                throw new AssertionError("review session lost after unchanged source: expected B staging Diff, got ordinary B source; "
                        + project.getService(io.github.youjiaxing.diffnav.StageNavigationService.class).getLastReason());
            }
            if (!at("b.go", NodeKind.STAGED) || normal != null) return;
            check(localTabs() == 1, "review navigation through unchanged source returns to staging Diff");
            action("Back");
            step = 20;
        } else if (step == 20) {
            var normal = source("clean.go");
            if (normal == null) return;
            check(normal.getCaretModel().getOffset() == normal.getDocument().getText().indexOf("B()"), "native Back restores intermediate source and caret");
            action("DiffNavigation.Back");
            step = 21;
        } else if (step == 21) {
            if (!at("c.go", NodeKind.UNSTAGED) || FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return;
            check(true, "mixed Back returns from source to Diff");
            action("DiffNavigation.Forward");
            step = 22;
        } else if (step == 22) {
            if (source("clean.go") == null) return;
            check(true, "mixed Forward restores source");
            action("DiffNavigation.Forward");
            step = 23;
        } else if (step == 23) {
            if (!at("b.go", NodeKind.STAGED) || FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return;
            check(true, "mixed Forward returns to Diff");
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
            check(localTabs() == 1, "existing ordinary source tabs are preserved");
            navigate("clean.go", "Clean()");
            step = 24;
        } else if (step == 24) {
            if (source("clean.go") == null) return;
            var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/c.go");
            var psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file);
            int offset = psi.getText().indexOf("Clean()");
            usage = new com.intellij.usages.UsageInfo2UsageAdapter(new com.intellij.usageView.UsageInfo(psi, offset, offset + 5));
            var presentation = new com.intellij.usages.UsageViewPresentation();
            presentation.setTabText("Diff Navigation Smoke");
            presentation.setUsagesString("usages");
            usages = com.intellij.usages.UsageViewManager.getInstance(project).showUsages(
                    new com.intellij.usages.UsageTarget[0], new com.intellij.usages.Usage[]{usage}, presentation);
            usages.selectUsages(new com.intellij.usages.Usage[]{usage});
            step = 25;
        } else if (step == 25) {
            var tree = find(usages.getComponent(), javax.swing.JTree.class);
            if (tree == null || usages.getSelectedUsages().isEmpty()) return;
            var context = DataManager.getInstance().getDataContext(tree);
            if (com.intellij.openapi.actionSystem.CommonDataKeys.NAVIGATABLE_ARRAY.getData(context) == null) return;
            check(true, "actual usage results provide selected navigatables");
            ActionUtil.invokeAction(ActionManager.getInstance().getAction("EditSource"), context, "DiffNavSmoke", null, null);
            step = 26;
        } else if (step == 26) {
            if (source("c.go") != null) throw new AssertionError("usage result opened ordinary source instead of staging Diff");
            if (!at("c.go", NodeKind.UNSTAGED) || FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return;
            check(right().getCaretModel().getOffset() == right().getDocument().getText().indexOf("Clean()"), "usage result Enter/EditSource returns to Diff at reference");
            check(localTabs() == 1, "usage navigation creates no new source tab");
            action("DiffNavigation.Back");
            step = 29;
        } else if (step == 29) {
            if (source("clean.go") == null) return;
            var tree = find(usages.getComponent(), javax.swing.JTree.class);
            var path = tree.getSelectionPath();
            var bounds = path == null ? null : tree.getPathBounds(path);
            if (bounds == null) return;
            var click = new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, bounds.x + 5, bounds.y + bounds.height / 2, 2, false, java.awt.event.MouseEvent.BUTTON1);
            com.intellij.ide.IdeEventQueue.getInstance().dispatchEvent(click);
            step = 30;
        } else if (step == 30) {
            if (source("c.go") != null) throw new AssertionError("usage double click opened source");
            if (!at("c.go", NodeKind.UNSTAGED) || FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return;
            check(true, "usage result double click follows review session");
            usages.close();
            action("DiffNavigation.ToggleReview");
            call("Clean()");
            step = 27;
        } else if (step == 27) {
            if (source("clean.go") == null) return;
            var normal = source("clean.go");
            normal.getCaretModel().moveToOffset(normal.getDocument().getText().indexOf("B()"));
            action("GotoDeclaration");
            step = 28;
        } else if (step == 28) {
            if (source("b.go") == null) return;
            check(localTabs() == 2, "paused review restores ordinary source navigation");
            check(project.getService(io.github.youjiaxing.diffnav.StageNavigationService.class).statusText().contains("Idle"), "paused review has visible idle status");
            var configurable = new io.github.youjiaxing.diffnav.NavigationConfigurable(project);
            check(configurable.createComponent() != null && !configurable.isModified(), "project settings page initializes from saved preferences");
            configurable.disposeUIResources();
            try { PositionChecks.run(this::check); } catch (Exception error) { throw new RuntimeException(error); }
            io.github.youjiaxing.diffnav.NavigationSettings.get(project).getState().targetScope = io.github.youjiaxing.diffnav.NavigationSettings.TargetScope.STAGED_ONLY;
            project.getService(io.github.youjiaxing.diffnav.StageNavigationService.class).settingsChanged();
            preview.openPreview(true);
            step = 40;
        } else if (step == 40) {
            if (FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return;
            action("DiffNavigation.ToggleReview");
            navigate("partial.go", "Partial()");
            step = 31;
        } else if (step == 31) {
            if (!at("partial.go", NodeKind.STAGED) || FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return;
            check(right().getCaretModel().getLogicalPosition().line == 2, "staged-only policy maps shifted local symbol to INDEX coordinates");
            check(!right().getDocument().getText().contains("worktree-only"), "staged-only policy preserves commit baseline");
            action("DiffNavigation.OpenSource");
            step = 32;
        } else if (step == 32) {
            if (source("partial.go") == null) return;
            check(source("partial.go").getCaretModel().getLogicalPosition().line == 3, "open source once maps INDEX location back to local source");
            action("Back");
            step = 33;
        } else if (step == 33) {
            if (!at("partial.go", NodeKind.STAGED) || FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return;
            check(true, "open source once preserves return to review Diff");
            navigate("c.go", "C()");
            step = 34;
        } else if (step == 34) {
            if (source("c.go") == null) return;
            check(true, "staged-only policy leaves unstaged-only target in source");
            io.github.youjiaxing.diffnav.NavigationSettings.get(project).getState().targetScope = io.github.youjiaxing.diffnav.NavigationSettings.TargetScope.FOLLOW_TARGET;
            project.getService(io.github.youjiaxing.diffnav.StageNavigationService.class).settingsChanged();
            preview.openPreview(true);
            step = 41;
        } else if (step == 41) {
            if (FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return;
            navigate("partial.go", "Partial()");
            step = 35;
        } else if (step == 35) {
            if (!at("partial.go", NodeKind.UNSTAGED) || FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return;
            check(right().getCaretModel().getLogicalPosition().line == 3, "follow-target policy uses worktree coordinates in unstaged Diff");
            navigate("b.go", "B()");
            step = 36;
        } else if (step == 36) {
            if (!at("b.go", NodeKind.STAGED)) return;
            navigate("partial.go", "Partial()");
            step = 37;
        } else if (step == 37) {
            if (!at("partial.go", NodeKind.UNSTAGED)) return;
            var staged = GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(stage.getStageTree()).find(w ->
                    w.getFilePath().getName().equals("partial.go") && ((GitFileStatusNode) w.getUserObject()).getKind() == NodeKind.STAGED);
            GitStageDiffPreviewHandler.INSTANCE.selectChange(stage.getStageTree(), staged);
            stage.setCurrentChange(staged);
            step = 38;
        } else if (step == 38) {
            if (!at("partial.go", NodeKind.STAGED)) return;
            action("DiffNavigation.Back");
            step = 39;
        } else if (step == 39) {
            if (!at("b.go", NodeKind.STAGED)) return;
            check(true, "changing current file staging group preserves review Back");
            action("DiffNavigation.Forward");
            step = 42;
        } else if (step == 42) {
            if (!at("partial.go", NodeKind.STAGED)) return;
            check(true, "Forward remembers latest staging group without clearing history");
            features = new FeatureChecks(project, stage, preview, this::check);
            step = 50;
        } else if (step == 50) {
            try { if (!features.tick()) return; } catch (Exception failure) { throw new RuntimeException(failure); }
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
    private com.intellij.openapi.editor.Editor source(String name) {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        var file = editor == null ? null : com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file != null && file.getName().equals(name) ? editor : null;
    }
    private void navigate(String name, String expression) {
        var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + name);
        var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
        int offset = document.getText().indexOf(expression);
        var marker = document.createRangeMarker(offset, offset);
        var selected = FileEditorManager.getInstance(project).getSelectedTextEditor();
        var context = DataManager.getInstance().getDataContext((selected == null ? right() : selected).getContentComponent());
        com.intellij.codeInsight.navigation.actions.IdeKt.navigateRequest(project,
                new com.intellij.platform.backend.navigation.impl.SourceNavigationRequest(file, marker, null), context);
    }
    private void call(String expression) {
        var editor = right();
        int offset = editor.getDocument().getText().indexOf(expression);
        check(offset >= 0, "find call " + expression);
        editor.getCaretModel().moveToOffset(offset);
        action("GotoDeclaration");
        log("NATIVE returned, files=" + java.util.Arrays.toString(FileEditorManager.getInstance(project).getOpenFiles()));
    }
    private void action(String id) {
        var selected = FileEditorManager.getInstance(project).getSelectedTextEditor();
        var context = DataManager.getInstance().getDataContext((selected == null ? right() : selected).getContentComponent());
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
