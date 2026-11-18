package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.EditorTabDiffPreview;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.wm.ToolWindowManager;
import git4idea.index.GitStageTracker;
import git4idea.index.ui.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.*;
import java.util.List;
import java.util.function.BiConsumer;

/** A real cross-Diff request to a method whose receiver is wider than the target viewport. */
final class HorizontalNavigationChecks {
    private final Project project;
    private final BiConsumer<Boolean, String> check;
    private GitStageDiffRequestProcessor stage;
    private EditorTabDiffPreview preview;
    private VirtualFile target;
    private int step, waits;
    private int targetOffset;
    private int manualX;
    private VirtualFile added;
    HorizontalNavigationChecks(Project project, BiConsumer<Boolean, String> check) { this.project = project; this.check = check; }
    boolean tick() throws Exception {
        if (++waits > 200) throw new AssertionError("Horizontal navigation timeout " + step);
        if (step == 0) {
            String origin = "package horizontal\n\nfunc Origin() { Destination() }\n";
            var lines = new java.util.ArrayList<String>(); lines.add("package horizontal");
            for (int i = 1; i < 49; i++) lines.add("// target context " + i);
            lines.add("func (receiver" + "LongName".repeat(20) + " *CommandService) Destination(ctx Context) { // " + "long suffix ".repeat(40));
            for (int i = 0; i < 60; i++) lines.add("    // target body " + i);
            lines.add("}");
            var root = Path.of(project.getBasePath());
            Files.writeString(root.resolve("horizontal-origin.go"), origin);
            Files.writeString(root.resolve("horizontal-target.go"), String.join("\n", lines) + "\n");
            Files.writeString(root.resolve("horizontal-added.go"), "package horizontal\n\nfunc (s CommandService) AddedDestination() { // " + "long line ".repeat(80) + "\n}\n");
            added = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("horizontal-added.go"));
            git("add", "--", "horizontal-origin.go", "horizontal-target.go");
            git("commit", "-m", "horizontal navigation fixture", "--only", "--", "horizontal-origin.go", "horizontal-target.go");
            Files.writeString(root.resolve("horizontal-origin.go"), origin + "// local origin change\n");
            lines.set(55, "    // local target change");
            Files.writeString(root.resolve("horizontal-target.go"), String.join("\n", lines) + "\n");
            target = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("horizontal-target.go"));
            var originFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("horizontal-origin.go"));
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(target);
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(added);
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(originFile);
            GitStageTracker.getInstance(project).updateTrackerState();
            ToolWindowManager.getInstance(project).getToolWindow("Commit").show(); next();
        } else if (step == 1) {
            var window = ToolWindowManager.getInstance(project).getToolWindow("Commit");
            var panel = find(window.getContentManager().getComponent(), GitStagePanel.class); if (panel == null) return false;
            var wrapper = GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(panel.getTree()).find(w -> w.getFilePath().getName().equals("horizontal-origin.go")
                    && ((GitFileStatusNode) w.getUserObject()).getKind() == NodeKind.UNSTAGED);
            if (wrapper == null) return false;
            preview = (EditorTabDiffPreview) DiffDataKeys.EDITOR_TAB_DIFF_PREVIEW.getData(DataManager.getInstance().getDataContext(panel));
            if (preview == null) return false;
            GitStageDiffPreviewHandler.INSTANCE.selectChange(panel.getTree(), wrapper); preview.openPreview(true); next();
        } else if (step == 2) {
            for (var e : FileEditorManager.getInstance(project).getAllEditors()) if (e instanceof DiffEditorViewerFileEditor diff && diff.getEditorViewer() instanceof GitStageDiffRequestProcessor p) stage = p;
            if (!at("horizontal-origin.go")) return false;
            navigate(target, "Destination"); next();
        } else if (step == 3) {
            if (!at("horizontal-target.go") || waits < 12) return false;
            var two = (TwosideTextDiffViewer) stage.getActiveViewer();
            var editor = two.getEditor2();
            check.accept(editor.getCaretModel().getOffset() == targetOffset, "cross-Diff jump keeps exact method-name caret offset");
            int left = two.getEditor1().getScrollingModel().getHorizontalScrollOffset(), right = editor.getScrollingModel().getHorizontalScrollOffset();
            Files.writeString(Path.of(System.getProperty("diffnav.smoke.report")), "HORIZONTAL-JUMP left=" + left + " right=" + right + " caretColumn=" + editor.getCaretModel().getLogicalPosition().column + "\n", StandardOpenOption.APPEND);
            check.accept(left == 0 && right == 0, "cross-Diff method jump shows first column on both sides [left=" + left + ", right=" + right + "]");
            var visible = editor.getScrollingModel().getVisibleArea();
            int y = editor.logicalPositionToXY(editor.getCaretModel().getLogicalPosition()).y;
            check.accept(y >= visible.y && y < visible.y + visible.height, "method jump still shows the target row vertically");
            action("DiffNavigation.Back"); next();
        } else if (step == 4) {
            if (!at("horizontal-origin.go") || waits < 10) return false;
            var two = (TwosideTextDiffViewer) stage.getActiveViewer();
            check.accept(two.getEditor1().getScrollingModel().getHorizontalScrollOffset() == 0 && two.getEditor2().getScrollingModel().getHorizontalScrollOffset() == 0,
                    "review Back shows the first column without changing its file destination");
            action("DiffNavigation.Forward"); next();
        } else if (step == 5) {
            if (!at("horizontal-target.go") || waits < 10) return false;
            var two = (TwosideTextDiffViewer) stage.getActiveViewer();
            check.accept(two.getEditor2().getCaretModel().getOffset() == targetOffset && two.getEditor1().getScrollingModel().getHorizontalScrollOffset() == 0
                    && two.getEditor2().getScrollingModel().getHorizontalScrollOffset() == 0, "review Forward keeps the method caret and resets horizontal viewport");
            navigate(added, "AddedDestination"); next();
        } else if (step == 6) {
            if (stage.getCurrentChange() == null || !stage.getCurrentChange().getFilePath().getName().equals("horizontal-added.go")
                    || !(stage.getActiveViewer() instanceof com.intellij.diff.tools.util.side.OnesideTextDiffViewer one) || one.hasPendingRediff() || waits < 12) return false;
            var editor = one.getEditor();
            check.accept(editor.getCaretModel().getOffset() == targetOffset && editor.getScrollingModel().getHorizontalScrollOffset() == 0,
                    "new-file one-sided Diff shows line start and keeps exact method caret");
            var scrolling = editor.getScrollingModel(); scrolling.disableAnimation();
            try { scrolling.scrollHorizontally(240); } finally { scrolling.enableAnimation(); }
            manualX = scrolling.getHorizontalScrollOffset();
            check.accept(manualX > 0, "horizontal-scroll follow-up starts from actual manual offset"); next();
        } else if (step == 7) {
            if (waits < 10) return false;
            var one = (com.intellij.diff.tools.util.side.OnesideTextDiffViewer) stage.getActiveViewer();
            check.accept(one.getEditor().getScrollingModel().getHorizontalScrollOffset() == manualX, "later manual horizontal scrolling is not overwritten by navigation callbacks");
            return true;
        }
        return false;
    }
    private void navigate(VirtualFile file, String token) {
        var editor = ((TwosideTextDiffViewer) stage.getActiveViewer()).getEditor2();
        var document = FileDocumentManager.getInstance().getDocument(file);
        targetOffset = document.getText().indexOf(token);
        com.intellij.codeInsight.navigation.actions.IdeKt.navigateRequest(project,
                new com.intellij.platform.backend.navigation.impl.SourceNavigationRequest(file, document.createRangeMarker(targetOffset, targetOffset), null),
                DataManager.getInstance().getDataContext(editor.getContentComponent()));
    }
    private void action(String id) {
        var editor = ((TwosideTextDiffViewer) stage.getActiveViewer()).getEditor2();
        com.intellij.openapi.actionSystem.ex.ActionUtil.invokeAction(com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(id),
                DataManager.getInstance().getDataContext(editor.getContentComponent()), "HorizontalNavigationSmoke", null, null);
    }
    private boolean at(String name) {
        return stage != null && stage.getCurrentChange() != null && stage.getCurrentChange().getFilePath().getName().equals(name)
                && stage.getActiveViewer() instanceof TwosideTextDiffViewer two && !two.hasPendingRediff();
    }
    private void next() { step++; waits = 0; }
    private void git(String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("git", "-C", project.getBasePath())); cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).redirectErrorStream(true).start(); var output = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new AssertionError(output);
    }
    private static <T> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container parent) for (var child : parent.getComponents()) { var match = find(child, type); if (match != null) return match; }
        return null;
    }
}
