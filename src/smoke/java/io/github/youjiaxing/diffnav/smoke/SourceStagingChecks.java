package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.ActiveGutterRenderer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vcs.ex.LineStatusMarkerRenderer;
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import git4idea.index.GitStageLineStatusTracker;
import git4idea.index.GitStageTracker;
import git4idea.index.ui.*;
import io.github.youjiaxing.diffnav.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.*;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Real source editors, native markers and actual Git writes in the isolated source-only sandbox. */
final class SourceStagingChecks {
    private static final String HEAD = "header\nold-one\nanchor\nold-two\ntail\n";
    private static final String STAGED = "header\nstaged-one\nanchor\nold-two\ntail\n";
    private static final String LOCAL = "header\nstaged-one\nanchor\nlocal-two\ntail\n";
    private final Project project;
    private final BiConsumer<Boolean, String> check;
    private Editor source;
    private com.intellij.openapi.vfs.VirtualFile file;
    private int step, ticks;
    private int cancelProbe;
    private String beforeDiff;
    private ActiveGutterRenderer original;
    private GitStageDiffRequestProcessor diff;
    private boolean rebuiltMarker;
    private JTextComponent findField;
    private String findBaseline;
    private com.intellij.openapi.application.AccessToken autoSave;
    private Document vetoDocument;
    private final com.intellij.openapi.Disposable saveVetoLifetime = com.intellij.openapi.util.Disposer.newDisposable("Source staging unsaved check");
    SourceStagingChecks(Project project, BiConsumer<Boolean, String> check) { this.project = project; this.check = check; }

    boolean tick() throws Exception {
        if (ticks > 0 && ticks % 30 == 0 && source != null && binding() != null) {
            var ready = field(binding(), "ready");
            Files.writeString(Path.of(System.getProperty("diffnav.smoke.report")), "SOURCE-WAIT step=" + step + " ready=" + ready
                    + " unsaved=" + FileDocumentManager.getInstance().isDocumentUnsaved(source.getDocument())
                    + " selectedSource=" + (FileEditorManager.getInstance(project).getSelectedTextEditor() == source)
                    + " buttons=" + button("stage").isVisible() + "/" + button("unstage").isVisible() + "/" + button("stagedDiff").isVisible() + "\n", StandardOpenOption.APPEND);
        }
        if (++ticks > 450) throw new AssertionError("Source staging timeout at " + step + ", header=" + (source == null ? null : source.getHeaderComponent()) + ", reason=" + project.getService(StageNavigationService.class).getLastReason());
        var manager = FileEditorManager.getInstance(project);
        if (step == 0) {
            autoSave = com.intellij.ide.SaveAndSyncHandler.getInstance().disableAutoSave();
            com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer.EP_NAME.getPoint().registerExtension(
                    new com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer() {
                        @Override public boolean maySaveDocument(Document document, boolean explicit) { return document != vetoDocument; }
                    }, saveVetoLifetime);
            com.intellij.ide.GeneralSettings.getInstance().setAutoSaveIfInactive(false);
            com.intellij.ide.GeneralSettings.getInstance().setSaveOnFrameDeactivation(false);
            var path = Path.of(project.getBasePath(), "source-staging.txt");
            Files.writeString(path, HEAD); git("add", "--", "source-staging.txt"); git("commit", "-m", "source staging fixture", "--only", "--", "source-staging.txt");
            Files.writeString(path, STAGED); git("add", "--", "source-staging.txt"); Files.writeString(path, LOCAL);
            file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file == null) return false;
            source = manager.openTextEditor(new OpenFileDescriptor(project, file), true);
            var repository = git4idea.repo.GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file);
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(repository::update);
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(file);
            GitStageTracker.getInstance(project).updateTrackerState();
            project.getService(SourceStagingService.class).refresh(); next();
        } else if (step == 1) {
            if (!ready()) return false;
            var marker = marker(); if (marker == null || !marker.getLineMarkerRenderer().getClass().getName().contains("SourceMarkerService$Proxy")) return false;
            Object proxy = marker.getLineMarkerRenderer(); original = (ActiveGutterRenderer) field(proxy, "original");
            check.accept(original.getClass().getName().contains("LineStatusGutterMarkerRendererWithPopup"), "source marker proxies native renderer without adding a track");
            check.accept(source.getInlayModel().getAfterLineEndElementsInRange(0, source.getDocument().getTextLength()).size() >= 2, "source change blocks show persistent status text");
            if (!markerState("staged-one", STAGED, true, false) || !markerState("local-two", STAGED, false, true)) return false;
            capture("source-staging-initial.png");
            select("local-two"); next();
        } else if (step == 2) {
            if (cancelProbe == 1) {
                if (!ready()) return false;
                check.accept(index().equals(STAGED), "changing selection before queued source Stage cancels the old write");
                cancelProbe = 2; select("local-two"); return false;
            }
            if (!button("stage").isVisible()) return false;
            check.accept(!button("unstage").isVisible(), "local-only source selection offers Stage without guessed Unstage");
            if (cancelProbe == 0) {
                button("stage").doClick(); source.getSelectionModel().removeSelection(); cancelProbe = 1; return false;
            }
            button("stage").doClick(); next();
        } else if (step == 3) {
            if (!index().equals(LOCAL) || !ready() || !button("unstage").isVisible()) return false;
            if (!markerState("local-two", LOCAL, true, false)) return false;
            check.accept(source.getDocument().getText().equals(LOCAL) && Files.readString(Path.of(file.getPath())).equals(LOCAL), "source Stage preserves local document and disk");
            check.accept(manager.getSelectedTextEditor() == source && source.getSelectionModel().getSelectedText().equals("local-two"), "source Stage retains editor and selection");
            button("unstage").doClick(); next();
        } else if (step == 4) {
            if (!index().equals(STAGED) || !ready()) return false;
            if (!markerState("local-two", STAGED, false, true)) return false;
            check.accept(source.getDocument().getText().equals(LOCAL), "source Unstage only changes selected index content");
            select("staged-one"); next();
        } else if (step == 5) {
            if (!button("unstage").isVisible()) return false;
            button("unstage").doClick(); next();
        } else if (step == 6) {
            if (!index().equals(HEAD) || !ready() || !button("stage").isVisible()) return false;
            check.accept(source.getDocument().getText().equals(LOCAL), "unstaging original staged selection keeps unselected local changes");
            button("stage").doClick(); next();
        } else if (step == 7) {
            if (!index().equals(STAGED) || !ready()) return false;
            vetoDocument = source.getDocument();
            WriteCommandAction.runWriteCommandAction(project, () -> {
                int at = source.getDocument().getText().indexOf("staged-one");
                source.getDocument().replaceString(at, at + "staged-one".length(), "edited-one");
            });
            select("edited-one"); next();
        } else if (step == 8) {
            if (!ready()) return false;
            if (!markerState("edited-one", STAGED, true, true)) return false;
            check.accept(FileDocumentManager.getInstance().isDocumentUnsaved(source.getDocument()) && !button("stage").isVisible(),
                    "unsaved source does not offer executable Stage");
            vetoDocument = null;
            FileDocumentManager.getInstance().saveDocument(source.getDocument()); next();
        } else if (step == 9) {
            if (!ready() || !button("stagedDiff").isVisible() || !button("stage").isVisible()) return false;
            if (!markerState("edited-one", STAGED, true, true)) return false;
            check.accept(!button("unstage").isVisible(), "edited staged content requires selecting actual INDEX content in Diff");
            check.accept(index().equals(STAGED), "editing and saving source preserves previous staged version");
            var tracker = (GitStageLineStatusTracker) LineStatusTrackerManager.getInstanceImpl(project).getLineStatusTracker(source.getDocument());
            var range = tracker.getRanges().stream().filter(r -> r.getHasStaged() && r.getHasUnstaged()).findFirst().orElse(null);
            check.accept(range != null, "native tracker exposes staged and subsequent local edit together");
            var marker = marker(); var renderer = (ActiveGutterRenderer) marker.getLineMarkerRenderer();
            var gutter = ((com.intellij.openapi.editor.ex.EditorEx) source).getGutterComponentEx();
            var area = com.intellij.openapi.diff.LineStatusMarkerDrawUtil.getGutterArea(source);
            int y = source.logicalPositionToXY(new LogicalPosition(range.getLine1(), 0)).y + source.getLineHeight() / 2;
            var event = new MouseEvent(gutter, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, area.first + 1, y, 1, false, MouseEvent.BUTTON1);
            check.accept(renderer.canDoAction(source, event), "custom marker keeps native click hit testing");
            renderer.doAction(source, event); next();
        } else if (step == 10) {
            var popupService = com.intellij.openapi.vcs.ex.LineStatusMarkerPopupService.getInstance();
            var lifetime = (com.intellij.openapi.util.CheckedDisposable) field(popupService, "activePopupDisposable");
            if (lifetime == null || lifetime.isDisposed()) return false;
            check.accept(true, "marker click opens a native code comparison popup");
            popupService.closeActivePopup();
            capture("source-staging-mixed.png");
            NavigationSettings.get(project).getState().sourceStaging = false;
            project.getService(StageNavigationService.class).settingsChanged();
            check.accept(marker().getLineMarkerRenderer() == original, "disabling source enhancement restores original renderer identity");
            check.accept(source.getInlayModel().getAfterLineEndElementsInRange(0, source.getDocument().getTextLength()).isEmpty(), "disabling source enhancement removes its text annotations");
            NavigationSettings.get(project).getState().sourceStaging = true;
            project.getService(StageNavigationService.class).settingsChanged(); next();
        } else if (step == 11) {
            if (!ready() || !button("stagedDiff").isVisible()) return false;
            if (!rebuiltMarker) {
                var tracker = (GitStageLineStatusTracker) LineStatusTrackerManager.getInstanceImpl(project).getLineStatusTracker(source.getDocument());
                marker().dispose();
                ((LineStatusMarkerRenderer) field(tracker, "renderer")).scheduleUpdate();
                rebuiltMarker = true; return false;
            }
            if (marker() == null || !marker().getLineMarkerRenderer().getClass().getName().contains("SourceMarkerService$Proxy")) return false;
            check.accept(true, "native highlighter rebuilding restores enhancement without an extra track");
            beforeDiff = index(); button("stagedDiff").doClick(); next();
        } else if (step == 12) {
            for (var editor : manager.getAllEditors()) if (editor instanceof DiffEditorViewerFileEditor e && e.getEditorViewer() instanceof GitStageDiffRequestProcessor p) diff = p;
            if (diff == null || diff.getCurrentChange() == null || !(diff.getCurrentChange().getUserObject() instanceof GitFileStatusNode node)
                    || node.getKind() != NodeKind.STAGED || !(diff.getActiveViewer() instanceof TwosideTextDiffViewer two) || two.hasPendingRediff()) return false;
            if (!two.getEditor2().getDocument().getText().contains("staged-one")) return false;
            check.accept(index().equals(beforeDiff) && java.util.Arrays.asList(manager.getOpenFiles()).contains(file), "opening staged Diff writes nothing and preserves source tab");
            select(two.getEditor2(), "staged-one"); next();
        } else if (step == 13) {
            var two = (TwosideTextDiffViewer) diff.getActiveViewer();
            var action = ActionManager.getInstance().getAction("DiffNavigation.SelectedLines");
            var context = DataManager.getInstance().getDataContext(two.getEditor2().getContentComponent());
            var presentation = action.getTemplatePresentation().clone();
            var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(context, presentation, "SourceStagingSmoke", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
            action.update(event); if (!presentation.isEnabledAndVisible()) return false;
            ActionUtil.invokeAction(action, context, "SourceStagingSmoke", null, null); next();
        } else if (step == 14) {
            if (!index().equals(HEAD)) return false;
            if (!markerState("edited-one", HEAD, false, true)) return false;
            check.accept(Files.readString(Path.of(file.getPath())).contains("edited-one") && source.getDocument().getText().contains("local-two"), "reselected Diff Unstage keeps subsequent source edits and unselected work");
            manager.openTextEditor(new OpenFileDescriptor(project, file), true);
            var path = Path.of(project.getBasePath(), "source-deletion.txt");
            Files.writeString(path, "a\nold\nz\n"); git("add", "--", "source-deletion.txt"); git("commit", "-m", "source deletion fixture", "--only", "--", "source-deletion.txt");
            Files.writeString(path, "a\nz\n"); git("add", "--", "source-deletion.txt");
            file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            source = manager.openTextEditor(new OpenFileDescriptor(project, file), true);
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(file);
            GitStageTracker.getInstance(project).updateTrackerState(); next();
        } else if (step == 15) {
            if (!ready()) return false;
            select("z"); next();
        } else if (step == 16) {
            if (!ready()) return false;
            check.accept(button("stagedDiff").isVisible() && !button("unstage").isVisible(), "staged deletion exposes Diff fallback instead of an ineffective source action");
            autoSave.finish(); com.intellij.openapi.util.Disposer.dispose(saveVetoLifetime);
            var path = Path.of(project.getBasePath(), "find-focus.txt");
            var head = "head\nneedle one\nneedle two\ntail\n";
            Files.writeString(path, head); git("add", "--", "find-focus.txt"); git("commit", "-m", "find focus fixture", "--only", "--", "find-focus.txt");
            var local = "head\nneedle one\nneedle two\nneedle three\ntail\n";
            Files.writeString(path, local);
            file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            source = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file), true);
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(file);
            GitStageTracker.getInstance(project).updateTrackerState();
            project.getService(SourceStagingService.class).refresh(); next();
        } else if (step == 17) {
            if (!ready()) return false;
            var action = ActionManager.getInstance().getAction(IdeActions.ACTION_FIND);
            ActionUtil.invokeAction(action, DataManager.getInstance().getDataContext(source.getContentComponent()), "SourceFindFocusSmoke", null, null);
            next();
        } else if (step == 18) {
            findField = findTextComponent(source.getHeaderComponent());
            if (findField == null) return false;
            findBaseline = source.getDocument().getText();
            findField.requestFocusInWindow(); findField.setText("needle");
            dispatchKey(findField, KeyEvent.VK_ENTER, '\n');
            next();
        } else if (step == 19) {
            if (ticks < 4) return false;
            var focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            check.accept(focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, findField), "Ctrl+F keeps focus in the Find field after Enter advances a match");
            check.accept(findField.getText().equals("needle"), "Find query remains active after advancing a match");
            check.accept(source.getDocument().getText().equals(findBaseline), "Find navigation does not modify source code");
            var escape = ActionManager.getInstance().getAction(IdeActions.ACTION_EDITOR_ESCAPE);
            if (escape != null) ActionUtil.invokeAction(escape, DataManager.getInstance().getDataContext(findField), "SourceFindFocusSmoke", null, null);
            else dispatchKey(findField, KeyEvent.VK_ESCAPE, (char) 27);
            source.getContentComponent().requestFocusInWindow();
            next();
        } else if (step == 20) {
            if (ticks < 2) return false;
            var focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focusOwner == null || !SwingUtilities.isDescendingFrom(focusOwner, source.getContentComponent())) return false;
            if (!ready()) return false;
            check.accept(true, "closing Find returns to the source editor and resumes staging state refresh");
            return true;
        }
        return false;
    }
    private boolean markerState(String text, String expectedIndex, boolean staged, boolean unstaged) throws Exception {
        var tracker = LineStatusTrackerManager.getInstanceImpl(project).getLineStatusTracker(source.getDocument());
        if (!(tracker instanceof GitStageLineStatusTracker git) || !git.isValid() || !git.getStagedDocument().getText().equals(expectedIndex)) return false;
        var h = marker();
        if (h == null || h.getLineMarkerRenderer() == null || !h.getLineMarkerRenderer().getClass().getName().contains("SourceMarkerService$Proxy")) return false;
        var watch = field(h.getLineMarkerRenderer(), "watch");
        var current = watch.getClass().getDeclaredMethod("current"); current.setAccessible(true);
        if (!(boolean) current.invoke(watch)) return false;
        int line = source.getDocument().getLineNumber(source.getDocument().getText().indexOf(text));
        Object found = null;
        for (var m : (List<?>) field(watch, "marks")) {
            var range = (com.intellij.openapi.vcs.ex.Range) m;
            if (range.getLine1() <= line && line < range.getLine2()) { found = m; break; }
        }
        check.accept(found != null && (boolean) field(found, "staged") == staged && (boolean) field(found, "unstaged") == unstaged,
                "live source marker step=" + step + " text=" + text + " staged=" + staged + " unstaged=" + unstaged);
        return true;
    }
    private void next() { step++; ticks = 0; }
    private void select(String text) { select(source, text); }
    private static void select(Editor editor, String text) {
        int at = editor.getDocument().getText().indexOf(text);
        if (at < 0) throw new AssertionError("No selection text: " + text);
        editor.getCaretModel().moveToOffset(at); editor.getSelectionModel().setSelection(at, at + text.length());
    }
    private static JTextComponent findTextComponent(Component component) {
        if (component instanceof JTextComponent text) return text;
        if (component instanceof Container parent) for (var child : parent.getComponents()) {
            var found = findTextComponent(child); if (found != null) return found;
        }
        return null;
    }
    private static void dispatchKey(Component target, int code, char character) {
        long now = System.currentTimeMillis();
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, now, 0, code, character));
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, now + 1, 0, code, character));
    }
    private boolean ready() throws Exception {
        if (source == null || source.isDisposed() || binding() == null || field(binding(), "ready") == null || (boolean) field(binding(), "busy")) return false;
        var ticket = field(field(binding(), "ready"), "ticket");
        var method = ticket.getClass().getDeclaredMethod("current"); method.setAccessible(true);
        return (boolean) method.invoke(ticket);
    }
    private Object binding() throws Exception {
        var type = Class.forName("io.github.youjiaxing.diffnav.SourceStagingService");
        var method = type.getDeclaredMethod("binding", Editor.class); method.setAccessible(true); return method.invoke(null, source);
    }
    private JButton button(String field) throws Exception { return (JButton) field(binding(), field); }
    private static Object field(Object owner, String name) throws Exception { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private RangeHighlighter marker() {
        for (var h : DocumentMarkupModel.forDocument(source.getDocument(), project, false).getAllHighlighters())
            if (Boolean.TRUE.equals(h.getUserData(LineStatusMarkerRenderer.Companion.getMAIN_KEY()))) return h;
        return null;
    }
    private String index() throws Exception { return git("show", ":source-staging.txt"); }
    private String git(String... args) throws Exception {
        var command = new java.util.ArrayList<String>(List.of("git", "-C", project.getBasePath())); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start(); var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError(output); return output.replace("\r\n", "\n");
    }
    private void capture(String name) throws Exception {
        var component = source.getComponent();
        if (component.getWidth() <= 0 || component.getHeight() <= 0) return;
        var image = new java.awt.image.BufferedImage(component.getWidth(), component.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); try { component.paint(g); } finally { g.dispose(); }
        javax.imageio.ImageIO.write(image, "png", Path.of(System.getProperty("diffnav.smoke.report")).getParent().resolve(name).toFile());
    }
}
