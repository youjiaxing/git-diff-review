package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import java.awt.*;
import javax.swing.SwingUtilities;
import java.util.function.BiConsumer;

/** Tests the actual JetBrains popup in the actual staging editor. */
final class PopupUxChecks {
    private final Project project;
    private final BiConsumer<Boolean, String> check;
    private int step, waits;
    private GitStageDiffRequestProcessor stage;
    private volatile Boolean menuEnabled;
    private volatile Throwable failure;
    private boolean updating;
    private com.intellij.openapi.ui.popup.JBPopup savedPopup;
    private com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo originalLaf;
    private com.intellij.diff.tools.util.DiffSplitter splitter;
    private int narrowHeight;
    private String originalText, unrelated;
    PopupUxChecks(Project project, BiConsumer<Boolean, String> check) { this.project = project; this.check = check; }
    boolean tick() throws Exception {
        if (step == 18) return true;
        if (failure != null) throw new AssertionError(failure);
        if (++waits > 150) throw new AssertionError("Popup UX timeout step=" + step);
        var manager = FileEditorManager.getInstance(project);
        if (step == 0) {
            originalLaf = com.intellij.ide.ui.LafManager.getInstance().getCurrentUIThemeLookAndFeel();
            originalText = java.nio.file.Files.readString(java.nio.file.Path.of(file().getPath())); unrelated = git("show", ":b.go");
            manager.openTextEditor(new OpenFileDescriptor(project, file()), true); next();
        } else if (step == 1) {
            if (git4idea.index.GitStageTrackerKt.status(git4idea.index.GitStageTracker.getInstance(project), file()) == null) return false;
            if (!Boolean.TRUE.equals(menuEnabled)) {
                if (menuEnabled != null) { updating = false; menuEnabled = null; }
                if (!updating) {
                    updating = true;
                    var action = ActionManager.getInstance().getAction("Git.Stage.Compare.Local.Staged");
                    var event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.EDITOR_POPUP, DataManager.getInstance().getDataContext(manager.getSelectedTextEditor().getContentComponent()));
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try { ReadAction.run(() -> action.update(event)); menuEnabled = event.getPresentation().isEnabled(); }
                        catch (Throwable error) { failure = error; }
                    });
                }
                return false;
            }
            ActionUtil.invokeAction(ActionManager.getInstance().getAction("Git.Stage.Compare.Local.Staged"),
                    DataManager.getInstance().getDataContext(manager.getSelectedTextEditor().getContentComponent()), "DiffNavSmoke", null, null); next();
        } else if (step == 2) {
            for (var editor : manager.getAllEditors()) if (editor instanceof DiffEditorViewerFileEditor diff
                    && diff.getEditorViewer() instanceof GitStageDiffRequestProcessor p) stage = p;
            if (stage == null || !(stage.getActiveViewer() instanceof OnesideTextDiffViewer one) || one.hasPendingRediff() || waits < 10) return false;
            var editor = one.getEditor(); editor.getSettings().setUseSoftWraps(false);
            int end = editor.getDocument().getLineStartOffset(59);
            editor.getCaretModel().moveToOffset(end);
            editor.getSelectionModel().setSelection(editor.getDocument().getLineEndOffset(179), end);
            editor.getScrollingModel().disableAnimation();
            editor.getScrollingModel().scrollVertically(editor.offsetToXY(end).y - editor.getLineHeight() * 5);
            editor.getScrollingModel().enableAnimation(); next();
        } else if (step == 3) {
            var editor = ((OnesideTextDiffViewer) stage.getActiveViewer()).getEditor();
            SelectionUxChecks.offer(editor);
            var popup = SelectionUxChecks.popup(editor); if (popup == null || !popup.isVisible()) return false;
            var view = viewport(editor); var bounds = outer(popup);
            var caret = editor.getContentComponent().getLocationOnScreen(); caret.translate(editor.offsetToXY(editor.getCaretModel().getOffset()).x, editor.offsetToXY(editor.getCaretModel().getOffset()).y);
            int distance = Math.min(Math.abs(bounds.y - caret.y), Math.abs(bounds.y + bounds.height - caret.y));
            check.accept(view.contains(bounds) && distance <= editor.getLineHeight() * 3,
                    "reverse long-selection popup stays in code viewport near active endpoint [viewport=" + view + ", popup=" + bounds + ", caret=" + caret + "]");
            check.accept(SelectionUxChecks.button(popup.getContent(), "Stage 选中行") != null
                    && SelectionUxChecks.button(popup.getContent(), "Discard 选中修改") != null, "valid new-file popup exposes both Stage and Discard");
            capture(editor, popup, "dark-reverse"); savedPopup = popup;
            editor.getScrollingModel().scrollVertically(editor.getLineHeight() * 190); next();
        } else if (step == 4) {
            var editor = editor(); var popup = SelectionUxChecks.popup(editor);
            check.accept(popup == savedPopup && popup.isVisible() && viewport(editor).contains(outer(popup)), "scrolling the endpoint off screen keeps the same actionable popup inside the code viewport");
            pick(editor, 99, 129, false, editor.getScrollingModel().getVisibleArea().height - editor.getLineHeight() * 2, 0); next();
        } else if (step == 5) {
            var editor = editor(); var popup = offered(editor); if (popup == null) return false;
            check.accept(viewport(editor).contains(outer(popup)) && outer(popup).y + outer(popup).height <= caret(editor).y,
                    "bottom-edge selection flips the complete popup above the caret");
            pick(editor, 139, 140, false, editor.getLineHeight() * 5, 200); next();
        } else if (step == 6) {
            var editor = editor(); var popup = offered(editor); if (popup == null) return false;
            check.accept(viewport(editor).contains(outer(popup)) && outer(popup).x + outer(popup).width <= caret(editor).x,
                    "right-edge selection moves the complete popup left of the caret");
            editor.getSettings().setUseSoftWraps(true); next();
        } else if (step == 7) {
            if (waits < 4) return false;
            pick(editor(), 49, 50, false, editor().getLineHeight() * 5, 200); next();
        } else if (step == 8) {
            var editor = editor(); var popup = offered(editor); if (popup == null) return false;
            var bounds = outer(popup);
            check.accept(viewport(editor).contains(bounds) && Math.min(Math.abs(bounds.y - caret(editor).y), Math.abs(bounds.y + bounds.height - caret(editor).y)) <= editor.getLineHeight() * 3,
                    "soft-wrapped selection anchors to the caret visual row");
            var laf = com.intellij.ide.ui.LafManager.getInstance(); laf.setCurrentLookAndFeel(laf.getDefaultLightLaf(), false); laf.updateUI(); next();
        } else if (step == 9) {
            if (waits < 4) return false;
            var editor = editor(); var popup = offered(editor); if (popup == null) return false;
            var button = SelectionUxChecks.button(popup.getContent(), "Stage 选中行");
            check.accept(viewport(editor).contains(outer(popup)) && button != null && !button.isDefaultButton(),
                    "light-theme popup remains in bounds and Stage does not become the Enter default action");
            capture(editor, popup, "light-wrapped");
            var laf = com.intellij.ide.ui.LafManager.getInstance(); laf.setCurrentLookAndFeel(originalLaf, false); laf.updateUI(); next();
        } else if (step == 10) {
            if (waits < 4) return false;
            editor().getSettings().setUseSoftWraps(false);
            pick(editor(), 59, 59, false, editor().getLineHeight() * 5, 0); next();
        } else if (step == 11) {
            var popup = offered(editor()); if (popup == null) return false;
            SelectionUxChecks.button(popup.getContent(), "Stage 选中行").doClick(); next();
        } else if (step == 12) {
            String wanted = originalText.lines().skip(59).findFirst().orElseThrow() + "\n";
            if (!(stage.getActiveViewer() instanceof com.intellij.diff.tools.util.side.TwosideTextDiffViewer two) || two.hasPendingRediff()
                    || !git("show", ":popup-review.txt").equals(wanted) || !two.getEditor1().getDocument().getText().equals(wanted)) return false;
            var service = project.getService(io.github.youjiaxing.diffnav.StageRefreshService.class);
            var ready = service.getClass().getDeclaredMethod("ready", GitStageDiffRequestProcessor.class, com.intellij.diff.tools.util.base.DiffViewerBase.class); ready.setAccessible(true);
            if (!(boolean) ready.invoke(service, stage, two)) return false;
            check.accept(java.nio.file.Files.readString(java.nio.file.Path.of(file().getPath())).equals(originalText) && git("show", ":b.go").equals(unrelated),
                    "clicking the real floating Stage button stages exactly the chosen line and preserves LOCAL and unrelated INDEX");
            splitter = findSplitter(two.getComponent());
            if (splitter == null) throw new AssertionError("Native diff splitter missing");
            splitter.setProportion(0.82f); next();
        } else if (step == 13) {
            if (waits < 4) return false;
            pick(editor(), 69, 69, false, editor().getLineHeight() * 5, 0); next();
        } else if (step == 14) {
            var editor = editor(); var popup = offered(editor); if (popup == null) return false;
            var bounds = outer(popup); narrowHeight = bounds.height; savedPopup = popup;
            var stageButton = actionButton(popup.getContent(), "Stage 选中行");
            var discardButton = actionButton(popup.getContent(), "Discard 选中修改");
            capture(editor, popup, "dark-narrow");
            check.accept(viewport(editor).width < 260 && viewport(editor).contains(bounds)
                            && stageButton != null && stageButton.isShowing() && discardButton != null && discardButton.isShowing(),
                    "narrow native diff side keeps all effective actions visible in a bounded vertical popup [view=" + viewport(editor)
                            + ", popup=" + bounds + ", Stage=" + (stageButton == null ? null : stageButton.getText())
                            + ", Discard=" + (discardButton == null ? null : discardButton.getText()) + "]");
            splitter.setProportion(0.5f); next();
        } else if (step == 15) {
            if (waits < 4) return false;
            var editor = editor(); var popup = SelectionUxChecks.popup(editor);
            check.accept(popup == savedPopup && popup.isVisible() && viewport(editor).contains(outer(popup)) && outer(popup).height < narrowHeight,
                    "resizing the diff split reflows the same popup without losing the valid selection");
            var left = ((com.intellij.diff.tools.util.side.TwosideTextDiffViewer) stage.getActiveViewer()).getEditor1();
            SelectionUxChecks.select(left, "popup line 060"); next();
        } else if (step == 16) {
            var left = ((com.intellij.diff.tools.util.side.TwosideTextDiffViewer) stage.getActiveViewer()).getEditor1();
            if (offered(left) == null) return false;
            var right = editor();
            mouse(left, java.awt.event.MouseEvent.MOUSE_RELEASED);
            mouse(right, java.awt.event.MouseEvent.MOUSE_PRESSED);
            SelectionUxChecks.select(right, "popup line 080");
            mouse(right, java.awt.event.MouseEvent.MOUSE_RELEASED);
            if (!right.getSelectionModel().hasSelection()) SelectionUxChecks.select(right, "popup line 080");
            check.accept(left.getSelectionModel().hasSelection() && right.getSelectionModel().hasSelection(),
                    "both sides retain selected text during the rapid-side-switch race");
            next();
        } else if (step == 17) {
            if (waits < 4) return false;
            var editor = editor();
            var controller = SelectionUxChecks.controller(editor);
            var owner = controller.getClass().getDeclaredField("selectionEditor"); owner.setAccessible(true);
            var popup = SelectionUxChecks.popup(editor);
            if (owner.get(controller) == editor && popup == null && waits < 30) return false;
            check.accept(owner.get(controller) == editor && popup != null && popup.isVisible()
                            && actionButton(popup.getContent(), "Stage 选中行") != null && viewport(editor).contains(outer(popup)),
                    "late mouse release from INDEX cannot reopen its popup over the new LOCAL selection [ownerRight=" + (owner.get(controller) == editor)
                            + ", popup=" + popup + ", visible=" + (popup != null && popup.isVisible()) + "]");
            for (var file : manager.getOpenFiles()) manager.closeFile(file);
            next(); return true;
        }
        return false;
    }
    private void next() { step++; waits = 0; }
    private Editor editor() {
        return stage.getActiveViewer() instanceof OnesideTextDiffViewer one ? one.getEditor()
                : ((com.intellij.diff.tools.util.side.TwosideTextDiffViewer) stage.getActiveViewer()).getEditor2();
    }
    private com.intellij.openapi.ui.popup.JBPopup offered(Editor editor) throws Exception {
        SelectionUxChecks.offer(editor);
        var popup = SelectionUxChecks.popup(editor);
        return popup != null && popup.isVisible() ? popup : null;
    }
    private static Point caret(Editor editor) {
        var point = editor.visualPositionToXY(editor.getCaretModel().getVisualPosition());
        SwingUtilities.convertPointToScreen(point, editor.getContentComponent()); return point;
    }
    private static void pick(Editor editor, int startLine, int endLine, boolean reverse, int relativeY, int column) {
        int start = editor.getDocument().getLineStartOffset(startLine);
        int end = column == 0 ? editor.getDocument().getLineEndOffset(endLine)
                : Math.min(editor.getDocument().getLineStartOffset(endLine) + column, editor.getDocument().getLineEndOffset(endLine));
        editor.getCaretModel().moveToOffset(reverse ? start : end);
        editor.getSelectionModel().setSelection(reverse ? end : start, reverse ? start : end);
        var scroll = editor.getScrollingModel(); scroll.disableAnimation();
        scroll.scrollVertically(editor.visualPositionToXY(editor.getCaretModel().getVisualPosition()).y - relativeY);
        scroll.scrollHorizontally(column == 0 || editor.getSettings().isUseSoftWraps() ? 0 : Math.max(0, editor.offsetToXY(end).x - scroll.getVisibleArea().width + 12));
        scroll.enableAnimation();
    }
    static Rectangle outer(com.intellij.openapi.ui.popup.JBPopup popup) {
        var window = SwingUtilities.getWindowAncestor(popup.getContent());
        return new Rectangle(window.getLocationOnScreen(), window.getSize());
    }
    private static com.intellij.diff.tools.util.DiffSplitter findSplitter(Component component) {
        if (component instanceof com.intellij.diff.tools.util.DiffSplitter split) return split;
        if (component instanceof Container parent) for (var child : parent.getComponents()) { var result = findSplitter(child); if (result != null) return result; }
        return null;
    }
    private static javax.swing.JButton actionButton(Component component, String action) {
        if (component instanceof javax.swing.JButton button && action.equals(button.getActionCommand())) return button;
        if (component instanceof Container parent) for (var child : parent.getComponents()) { var result = actionButton(child, action); if (result != null) return result; }
        return null;
    }
    private static void mouse(Editor editor, int type) {
        var area = editor.getScrollingModel().getVisibleArea();
        editor.getContentComponent().dispatchEvent(new java.awt.event.MouseEvent(editor.getContentComponent(), type, System.currentTimeMillis(),
                type == java.awt.event.MouseEvent.MOUSE_PRESSED ? java.awt.event.InputEvent.BUTTON1_DOWN_MASK : 0,
                area.x + 10, area.y + editor.getLineHeight(), 1, false, java.awt.event.MouseEvent.BUTTON1));
    }
    private String git(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>(); command.add("git"); command.add("-C"); command.add(project.getBasePath()); command.addAll(java.util.List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return process.waitFor() == 0 ? text.replace("\r\n", "\n") : "";
    }
    private void capture(Editor editor, com.intellij.openapi.ui.popup.JBPopup popup, String name) throws Exception {
        var bounds = viewport(editor); var image = new java.awt.image.BufferedImage(bounds.width, bounds.height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            var origin = editor.getContentComponent().getLocationOnScreen(); graphics.translate(origin.x - bounds.x, origin.y - bounds.y);
            editor.getContentComponent().printAll(graphics); graphics.translate(bounds.x - origin.x, bounds.y - origin.y);
            var window = SwingUtilities.getWindowAncestor(popup.getContent()); var location = window.getLocationOnScreen();
            graphics.translate(location.x - bounds.x, location.y - bounds.y); window.printAll(graphics);
        } finally { graphics.dispose(); }
        var output = java.nio.file.Path.of(System.getProperty("diffnav.smoke.report")).getParent().resolve("popup-frames");
        java.nio.file.Files.createDirectories(output); javax.imageio.ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
    }
    private com.intellij.openapi.vfs.VirtualFile file() { return LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/popup-review.txt"); }
    static Rectangle viewport(Editor editor) {
        var area = editor.getScrollingModel().getVisibleArea(); var origin = editor.getContentComponent().getLocationOnScreen();
        return new Rectangle(origin.x + area.x, origin.y + area.y, area.width, area.height);
    }
}
