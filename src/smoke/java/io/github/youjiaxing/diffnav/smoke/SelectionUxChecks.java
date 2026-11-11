package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import git4idea.index.ui.GitFileStatusNode;
import git4idea.index.ui.NodeKind;
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import java.awt.Component;
import java.awt.Container;
import java.util.function.BiConsumer;
import javax.swing.JButton;

/** Checks the actual selection toolbar in a partially staged file. */
final class SelectionUxChecks {
    private final Project project;
    private final BiConsumer<Boolean, String> check;
    private int step, ticks;
    private GitStageDiffRequestProcessor stage;
    private com.intellij.openapi.editor.Document document;
    private String disk, index;
    private boolean dialogDone;
    private boolean mixedChecked;
    private Object refreshProbe, oldViewer;
    private Throwable dialogFailure;
    SelectionUxChecks(Project project, BiConsumer<Boolean, String> check) { this.project = project; this.check = check; }
    boolean tick() throws Exception {
        if (dialogFailure != null) throw new AssertionError(dialogFailure);
        if (step == 99) return true;
        if (++ticks > 100) {
            if (stage != null && stage.getActiveViewer() instanceof TwosideTextDiffViewer two) dump(two.getEditor2());
            if (document != null) java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("diffnav.smoke.report")), "SELECTION-DIAG document=" + document.getText().replace('\n', '|') + " active=" + stage.getActiveViewer() + " current=" + stage.getCurrentChange() + "\n", java.nio.file.StandardOpenOption.APPEND);
            throw new AssertionError("Selection UX timed out at " + step + " reason=" + project.getService(io.github.youjiaxing.diffnav.StageNavigationService.class).getLastReason());
        }
        var manager = FileEditorManager.getInstance(project);
        if (step == 0) {
            var file = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath() + "/selection.txt");
            manager.openTextEditor(new OpenFileDescriptor(project, file), true); next();
        } else if (step == 1) {
            var source = manager.getSelectedTextEditor();
            if (source == null) return false;
            var file = FileDocumentManager.getInstance().getFile(source.getDocument());
            if (git4idea.index.GitStageTrackerKt.status(git4idea.index.GitStageTracker.getInstance(project), file) == null) return false;
            ActionUtil.invokeAction(ActionManager.getInstance().getAction("Git.Stage.Compare.Local.Staged"),
                    DataManager.getInstance().getDataContext(source.getContentComponent()), "DiffNavSmoke", null, null); next();
        } else if (step == 2) {
            for (var editor : manager.getAllEditors()) if (editor instanceof DiffEditorViewerFileEditor diff
                    && diff.getEditorViewer() instanceof GitStageDiffRequestProcessor p) stage = p;
            if (stage == null || !(stage.getActiveViewer() instanceof TwosideTextDiffViewer two) || two.hasPendingRediff()) return false;
            if (!two.getEditor1().getDocument().getText().contains("staged-one")) return false;
            select(two.getEditor1(), "staged-one"); offer(two.getEditor1()); next();
        } else if (step == 3) {
            var popup = popup(((TwosideTextDiffViewer) stage.getActiveViewer()).getEditor1());
            if (popup == null) {
                offer(((TwosideTextDiffViewer) stage.getActiveViewer()).getEditor1());
                if (ticks == 30) {
                    var controller = controller(((TwosideTextDiffViewer) stage.getActiveViewer()).getEditor1());
                    for (String name : new String[]{"pending", "prepared", "generation", "wantPopup"}) {
                        var field = controller.getClass().getDeclaredField(name); field.setAccessible(true);
                        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("diffnav.smoke.report")), "SELECTION-DIAG " + name + "=" + field.get(controller) + "\n", java.nio.file.StandardOpenOption.APPEND);
                    }
                    var field = controller.getClass().getDeclaredField("pending"); field.setAccessible(true); var pending = field.get(controller);
                    if (pending != null) for (String name : new String[]{"current", "stable", "ownsWindow"}) {
                        var method = pending.getClass().getDeclaredMethod(name); method.setAccessible(true);
                        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("diffnav.smoke.report")), "SELECTION-DIAG " + name + "=" + method.invoke(pending) + "\n", java.nio.file.StandardOpenOption.APPEND);
                    }
                }
                return false;
            }
            check.accept(button(popup.getContent(), "Unstage 选中行") != null, "INDEX selection offers Unstage for a staged addition");
            check.accept(button(popup.getContent(), "Stage 选中行") == null, "staged-only selection does not offer ineffective Stage");
            check.accept(!enabled(two().getEditor2(), "DiffNavigation.SelectedLines") && !enabled(two().getEditor2(), "DiffNavigation.DiscardSelectedLines"), "unselected opposite editor cannot reuse another side's cached plan");
            button(popup.getContent(), "Unstage 选中行").doClick(); next();
        } else if (step == 4) {
            if (!index("selection.txt").equals("head\nanchor\ntail\n") || !twoReady("selection.txt")) return false;
            check.accept(two().getEditor1().getDocument().getText().equals("head\nanchor\ntail\n"), "INDEX-left Unstage refreshes the current comparison without reopening");
            select(two().getEditor2(), "staged-one");
            if (!invokeWhenReady("DiffNavigation.SelectedLines", two().getEditor2())) return false;
            next();
        } else if (step == 5) {
            if (!index("selection.txt").contains("staged-one") || !twoReady("selection.txt")) return false;
            document = two().getEditor2().getDocument(); disk = read("selection.txt"); index = index("selection.txt");
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> document.insertString(document.getTextLength(), "unsaved-keep\n"));
            next();
        } else if (step == 6) {
            if (!twoReady("selection.txt")) return false;
            select(two().getEditor2(), "local-one"); offer(two().getEditor2());
            var popup = popup(two().getEditor2()); if (popup == null) return false;
            check.accept(button(popup.getContent(), "Stage 选中行") == null, "unsaved source does not offer executable Stage");
            check.accept(button(popup.getContent(), "Discard 选中修改") != null, "unsaved LOCAL still offers Discard");
            button(popup.getContent(), "Discard 选中修改").doClick(); next();
        } else if (step == 7) {
            if (document.getText().contains("local-one") || !twoReady("selection.txt")) return false;
            check.accept(document.getText().contains("unsaved-keep") && document.getText().contains("staged-one"), "Discard preserves other unsaved edits and staged content");
            check.accept(index("selection.txt").equals(index) && read("selection.txt").equals(disk), "Discard changes the real document without saving or writing INDEX");
            undo(false); next();
        } else if (step == 8) {
            if (!document.getText().contains("local-one") || !twoReady("selection.txt")) return false;
            check.accept(document.getText().contains("unsaved-keep"), "Undo restores the selected edit without undoing earlier unsaved typing");
            undo(true); next();
        } else if (step == 9) {
            if (document.getText().contains("local-one") || !twoReady("selection.txt")) return false;
            select(two().getEditor2(), "unsaved-keep");
            if (!invokeWhenReady("DiffNavigation.DiscardSelectedLines", two().getEditor2())) return false;
            next();
        } else if (step == 10) {
            if (document.getText().contains("unsaved-keep") || !twoReady("selection.txt")) return false;
            undo(false); next();
        } else if (step == 11) {
            if (!document.getText().contains("unsaved-keep") || !twoReady("selection.txt")) return false;
            check.accept(!document.getText().contains("local-one"), "consecutive Discards are independent Undo commands");
            FileDocumentManager.getInstance().saveDocument(document); next();
        } else if (step == 12) {
            if (!twoReady("selection.txt")) return false;
            select(two().getEditor2(), "anchor"); offer(two().getEditor2());
            if (!computed(two().getEditor2())) {
                if (ticks == 20) dump(two().getEditor2());
                return false;
            }
            check.accept(popup(two().getEditor2()) == null && !enabled(two().getEditor2(), "DiffNavigation.SelectedLines")
                    && !enabled(two().getEditor2(), "DiffNavigation.DiscardSelectedLines"), "unchanged context has no executable selection actions");
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> document.setText(document.getText().replace("anchor\n", "")));
            FileDocumentManager.getInstance().saveDocument(document); next();
        } else if (step == 13) {
            if (!twoReady("selection.txt")) return false;
            if (!mixedChecked) {
                var editor = two().getEditor1(); String text = editor.getDocument().getText();
                editor.getSelectionModel().setSelection(text.indexOf("staged-one"), text.indexOf("anchor") + "anchor".length()); offer(editor);
                var popup = popup(editor); if (popup == null) return false;
                check.accept(button(popup.getContent(), "Stage 选中行") != null && button(popup.getContent(), "Unstage 选中行") != null && button(popup.getContent(), "Discard 选中修改") != null,
                        "mixed INDEX selection explicitly lists all three effective operations");
                invokeWhenReady("DiffNavigation.SelectedLines", editor);
                popup = popup(editor);
                check.accept(popup != null && button(popup.getContent(), "Stage 选中行") != null && button(popup.getContent(), "Unstage 选中行") != null && button(popup.getContent(), "Discard 选中修改") == null,
                        "legacy combined action asks Stage or Unstage without implicitly discarding");
                popup.cancel(); mixedChecked = true; return false;
            }
            select(two().getEditor1(), "anchor"); offer(two().getEditor1());
            var popup = popup(two().getEditor1()); if (popup == null) return false;
            check.accept(button(popup.getContent(), "Stage 选中行") != null && button(popup.getContent(), "Discard 选中修改") != null
                    && button(popup.getContent(), "Unstage 选中行") == null, "INDEX old deletion offers Stage and Discard rather than incorrect Unstage");
            button(popup.getContent(), "Discard 选中修改").doClick(); next();
        } else if (step == 14) {
            if (!document.getText().contains("anchor") || !twoReady("selection.txt")) return false;
            check.accept(index("selection.txt").equals(index), "Discard selected deletion restores LOCAL and leaves INDEX intact");
            undo(false, two().getEditor1()); step = 141; ticks = 0;
        } else if (step == 141) {
            if (document.getText().contains("anchor") || !twoReady("selection.txt")) return false;
            check.accept(true, "native Undo from INDEX-side context restores discarded LOCAL deletion");
            undo(true, two().getEditor1()); step = 142; ticks = 0;
        } else if (step == 142) {
            if (!document.getText().contains("anchor") || !twoReady("selection.txt")) return false;
            open("selection-new.txt"); next();
            step = 15;
        } else if (step == 15) {
            if (!compareSource()) return false; next();
        } else if (step == 16) {
            if (!oneReady("selection-new.txt")) return false;
            select(one().getEditor(), "beta"); if (!invokeWhenReady("DiffNavigation.SelectedLines", one().getEditor())) return false; next();
        } else if (step == 17) {
            if (!twoReady("selection-new.txt") || !index("selection-new.txt").equals("beta\n")) return false;
            select(two().getEditor1(), "beta"); if (!invokeWhenReady("DiffNavigation.SelectedLines", two().getEditor1())) return false; next();
        } else if (step == 18) {
            if (!oneReady("selection-new.txt") || node().getKind() != NodeKind.UNTRACKED || !index("selection-new.txt").isEmpty()) return false;
            check.accept(one().getEditor().getDocument().getText().equals("alpha\nbeta\ngamma"), "full INDEX-left Unstage returns automatically to the original untracked preview");
            document = one().getEditor().getDocument();
            documentSelectAll(one().getEditor()); if (!invokeWhenReady("DiffNavigation.DiscardSelectedLines", one().getEditor())) return false; next();
        } else if (step == 19) {
            if (!document.getText().isEmpty()) return false;
            check.accept(java.nio.file.Files.exists(java.nio.file.Path.of(project.getBasePath(), "selection-new.txt")) && index("selection-new.txt").isEmpty(), "Discard all untracked contents retains the file and creates no INDEX entry");
            undo(false); next();
        } else if (step == 20) {
            if (!document.getText().equals("alpha\nbeta\ngamma")) return false;
            check.accept(true, "Undo restores untracked contents including no-final-newline state");
            open("selection-replace.txt"); next();
        } else if (step == 21) {
            if (!compareSource()) return false; next();
        } else if (step == 22) {
            if (!twoReady("selection-replace.txt")) return false;
            select(two().getEditor2(), "newOne");
            if (!enabled(two().getEditor2(), "DiffNavigation.DiscardSelectedLines")) return false;
            document = two().getEditor2().getDocument(); watchPreview(false);
            invokeWhenReady("DiffNavigation.DiscardSelectedLines", two().getEditor2()); next();
        } else if (step == 23) {
            if (!dialogDone) return false;
            check.accept(document.getText().equals("newOne\nnewTwo\ntail\n"), "canceling ambiguous Discard leaves LOCAL unchanged");
            if (!enabled(two().getEditor2(), "DiffNavigation.DiscardSelectedLines")) return false;
            watchPreview(true); invokeWhenReady("DiffNavigation.DiscardSelectedLines", two().getEditor2()); next();
        } else if (step == 24) {
            if (!dialogDone || !document.getText().equals("oldOne\nnewTwo\ntail\n") || !twoReady("selection-replace.txt")) return false;
            check.accept(index("selection-replace.txt").equals("oldOne\noldTwo\ntail\n"), "confirmed partial Discard preserves the other replacement and INDEX");
            select(two().getEditor2(), "newTwo"); if (!invokeWhenReady("DiffNavigation.DiscardSelectedLines", two().getEditor2())) return false;
            select(two().getEditor2(), "tail"); next();
        } else if (step == 25) {
            if (ticks < 10) return false;
            check.accept(document.getText().equals("oldOne\nnewTwo\ntail\n"), "changing selection before deferred Discard cancels the old candidate");
            var refresh = project.getService(io.github.youjiaxing.diffnav.StageRefreshService.class);
            var begin = refresh.getClass().getDeclaredMethod("begin", GitStageDiffRequestProcessor.class, Editor.class, GitFileStatusNode.class); begin.setAccessible(true);
            refreshProbe = begin.invoke(refresh, stage, two().getEditor2(), node()); oldViewer = stage.getActiveViewer();
            stage.updateRequest(true, false, null); next();
        } else if (step == 26) {
            if (stage.getActiveViewer() == oldViewer || !(stage.getActiveViewer() instanceof TwosideTextDiffViewer view) || view.hasPendingRediff()) return false;
            var canceled = refreshProbe.getClass().getDeclaredField("canceled"); canceled.setAccessible(true);
            check.accept(!(boolean) canceled.get(refreshProbe), "refresh intent survives native viewer replacement until user input");
            var component = view.getEditor2().getContentComponent();
            component.dispatchEvent(new java.awt.event.MouseWheelEvent(component, java.awt.event.MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                    10, 10, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1));
            check.accept((boolean) canceled.get(refreshProbe), "scrolling in the replacement viewer cancels delayed position restoration");
            for (var file : manager.getOpenFiles()) manager.closeFile(file);
            step = 99; return true;
        }
        return false;
    }
    private void next() { step++; ticks = 0; }
    private GitFileStatusNode node() { return (GitFileStatusNode) stage.getCurrentChange().getUserObject(); }
    private TwosideTextDiffViewer two() { return (TwosideTextDiffViewer) stage.getActiveViewer(); }
    private OnesideTextDiffViewer one() { return (OnesideTextDiffViewer) stage.getActiveViewer(); }
    private boolean twoReady(String name) throws Exception {
        if (!(stage.getActiveViewer() instanceof TwosideTextDiffViewer two) || two.hasPendingRediff() || !node().getFilePath().getName().equals(name)) return false;
        var refresh = project.getService(io.github.youjiaxing.diffnav.StageRefreshService.class);
        var method = refresh.getClass().getDeclaredMethod("ready", GitStageDiffRequestProcessor.class, com.intellij.diff.tools.util.base.DiffViewerBase.class); method.setAccessible(true);
        return (boolean) method.invoke(refresh, stage, two) && two.getEditor1().getDocument().getText().equals(index(name));
    }
    private boolean oneReady(String name) { return stage.getActiveViewer() instanceof OnesideTextDiffViewer one && !one.hasPendingRediff() && node().getFilePath().getName().equals(name); }
    private void open(String name) { FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + name)), true); }
    private boolean compareSource() {
        var source = FileEditorManager.getInstance(project).getSelectedTextEditor(); if (source == null) return false;
        var file = FileDocumentManager.getInstance().getFile(source.getDocument());
        if (git4idea.index.GitStageTrackerKt.status(git4idea.index.GitStageTracker.getInstance(project), file) == null) return false;
        ActionUtil.invokeAction(ActionManager.getInstance().getAction("Git.Stage.Compare.Local.Staged"), DataManager.getInstance().getDataContext(source.getContentComponent()), "DiffNavSmoke", null, null); return true;
    }
    private void undo(boolean redo) {
        Editor editor = stage.getActiveViewer() instanceof TwosideTextDiffViewer two ? two.getEditor2() : one().getEditor();
        undo(redo, editor);
    }
    private void undo(boolean redo, Editor editor) {
        var context = DataManager.getInstance().getDataContext(editor.getContentComponent());
        var fileEditor = com.intellij.openapi.actionSystem.PlatformCoreDataKeys.FILE_EDITOR.getData(context);
        var action = ActionManager.getInstance().getAction(redo ? com.intellij.openapi.actionSystem.IdeActions.ACTION_REDO : com.intellij.openapi.actionSystem.IdeActions.ACTION_UNDO);
        var event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null, "DiffNavSmoke", context);
        action.update(event);
        check.accept(event.getPresentation().isEnabled(), "Discard is available through the native side-editor Undo/Redo action");
        ActionUtil.invokeAction(action, context, "DiffNavSmoke", null, null);
    }
    private FileEditor source(String name) { return FileEditorManager.getInstance(project).getEditors(LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + name))[0]; }
    private String read(String name) throws Exception { return java.nio.file.Files.readString(java.nio.file.Path.of(project.getBasePath(), name)).replace("\r\n", "\n"); }
    private String index(String name) throws Exception {
        var process = new ProcessBuilder("git", "-C", project.getBasePath(), "show", ":" + name).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); process.waitFor(); return result.replace("\r\n", "\n");
    }
    private static void documentSelectAll(Editor editor) { editor.getSelectionModel().setSelection(0, editor.getDocument().getTextLength()); }
    private static boolean computed(Editor editor) throws Exception { var controller = controller(editor); var field = controller.getClass().getDeclaredField("prepared"); field.setAccessible(true); return field.get(controller) != null; }
    private static void dump(Editor editor) throws Exception {
        var value = controller(editor); var out = new StringBuilder("SELECTION-DIAG ");
        for (String name : new String[]{"pending", "prepared", "generation", "wantPopup", "busy"}) {
            var field = value.getClass().getDeclaredField(name); field.setAccessible(true); out.append(name).append('=').append(field.get(value)).append(' ');
        }
        var field = value.getClass().getDeclaredField("pending"); field.setAccessible(true); var pending = field.get(value);
        if (pending != null) for (String name : new String[]{"current", "stable", "ownsWindow"}) {
            var method = pending.getClass().getDeclaredMethod(name); method.setAccessible(true); out.append(name).append('=').append(method.invoke(pending)).append(' ');
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("diffnav.smoke.report")), out + "\n", java.nio.file.StandardOpenOption.APPEND);
    }
    private void watchPreview(boolean confirm) {
        dialogDone = false; final int[] attempts = {0}; var timer = new javax.swing.Timer(100, null);
        timer.addActionListener(event -> {
            try {
                if (++attempts[0] > 150) throw new AssertionError("Expected Discard preview");
                for (var window : java.awt.Window.getWindows()) if (window instanceof javax.swing.JDialog dialog && dialog.isShowing() && dialog.getTitle().equals("确认丢弃选中修改")) {
                    var button = button(dialog, confirm ? "确认 Discard" : "取消");
                    if (button == null && !confirm) button = button(dialog, "Cancel");
                    if (button == null) throw new AssertionError("Missing preview button");
                    timer.stop(); button.doClick(); dialogDone = true; return;
                }
            } catch (Throwable failure) { timer.stop(); dialogFailure = failure; }
        }); timer.start();
    }
    static boolean enabled(Editor editor, String id) {
        var action = ActionManager.getInstance().getAction(id);
        var event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null, "DiffNavSmoke",
                DataManager.getInstance().getDataContext(editor.getContentComponent()));
        action.update(event); return event.getPresentation().isEnabled();
    }
    static boolean invokeWhenReady(String id, Editor editor) {
        if (!enabled(editor, id)) return false;
        ActionUtil.invokeAction(ActionManager.getInstance().getAction(id), DataManager.getInstance().getDataContext(editor.getContentComponent()), "DiffNavSmoke", null, null);
        return true;
    }
    static void select(Editor editor, String text) {
        int start = editor.getDocument().getText().indexOf(text);
        if (start < 0) throw new AssertionError("Missing selection: " + text);
        editor.getCaretModel().moveToOffset(start);
        editor.getSelectionModel().setSelection(start, start + text.length());
    }
    static Object controller(Editor editor) throws Exception {
        var type = Class.forName("io.github.youjiaxing.diffnav.LineStaging");
        var field = type.getDeclaredField("CONTROLLER"); field.setAccessible(true);
        return editor.getUserData((com.intellij.openapi.util.Key<?>) field.get(null));
    }
    static void offer(Editor editor) throws Exception {
        var value = controller(editor);
        var method = value.getClass().getDeclaredMethod("offer", Editor.class); method.setAccessible(true); method.invoke(value, editor);
    }
    static com.intellij.openapi.ui.popup.JBPopup popup(Editor editor) throws Exception {
        var value = controller(editor);
        var field = value.getClass().getDeclaredField("popup"); field.setAccessible(true);
        return (com.intellij.openapi.ui.popup.JBPopup) field.get(value);
    }
    static JButton button(Component component, String text) {
        if (component instanceof JButton b && text.equals(b.getText())) return b;
        if (component instanceof Container parent) for (var child : parent.getComponents()) {
            var found = button(child, text); if (found != null) return found;
        }
        return null;
    }
}
