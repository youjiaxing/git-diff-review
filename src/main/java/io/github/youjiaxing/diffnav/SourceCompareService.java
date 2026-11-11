package io.github.youjiaxing.diffnav;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.util.DiffUtil;
import com.intellij.diff.util.Side;
import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl;
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.changes.ChangeViewDiffRequestProcessor.Wrapper;
import com.intellij.openapi.vcs.changes.EditorTabDiffPreview;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.Alarm;
import git4idea.index.ui.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;

/** One explicit source comparison, from action interception through native preview loading (EDT only). */
@Service(Service.Level.PROJECT)
public final class SourceCompareService implements Disposable {
    private final Project project;
    private Request pending;
    private int own;

    public SourceCompareService(Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void selectionChanged(FileEditorManagerEvent event) {
                // Selection notifications are asynchronous in 262; compare the actual window,
                // rather than canceling a newer command because an older event arrived late.
                if (own == 0 && pending != null) pending.valid();
            }
            @Override public void fileClosed(FileEditorManager manager, VirtualFile file) {
                if (pending != null && (file.equals(pending.file) || file.equals(pending.preview))) cancel();
            }
        });
    }

    boolean isPending() { return pending != null; }

    Runnable prepare(DataContext context, Runnable fallback) {
        var editor = CommonDataKeys.EDITOR.getData(context);
        var file = CommonDataKeys.VIRTUAL_FILE.getData(context);
        var window = FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
        if (!NavigationSettings.get(project).getState().enabled || editor == null || editor.isDisposed() || editor.getProject() != project
                || editor.getEditorKind() != EditorKind.MAIN_EDITOR || DiffDataKeys.DIFF_CONTEXT.getData(context) != null
                || file == null || !file.isValid() || !file.isInLocalFileSystem() || file.isDirectory() || file.getFileType().isBinary()
                || !file.equals(FileDocumentManager.getInstance().getFile(editor.getDocument()))
                || window == null || window.isDisposed() || !file.equals(window.getSelectedFile())
                || window.getSelectedComposite() == null || !(window.getSelectedComposite().getSelectedEditor() instanceof TextEditor text)
                || text.getEditor() != editor || !git4idea.index.GitStageManagerKt.isStagingAreaAvailable(project)) return null;
        Request request = new Request(file, editor, window, fallback);
        pending = request;
        // Install cancellation before leaving the action callback, including the deferred-start gap.
        var tool = ToolWindowManager.getInstance(project).getToolWindow("Commit");
        if (tool != null) request.bind(find(tool.getContentManager().getComponent(), GitStagePanel.class));
        return request::start;
    }

    void cancel() {
        Request request = pending;
        pending = null;
        if (request != null) Disposer.dispose(request.lifetime);
    }

    private final class Request {
        final VirtualFile file;
        final Editor source;
        final EditorWindow window;
        final Runnable fallback;
        final boolean untracked;
        final NavigationPosition position;
        final Disposable lifetime = Disposer.newDisposable("Source comparison");
        final Alarm alarm;
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        GitStageTree tree;
        GitStageDiffRequestProcessor stage;
        VirtualFile preview;
        boolean opened;

        Request(VirtualFile file, Editor source, EditorWindow window, Runnable fallback) {
            this.file = file; this.source = source; this.window = window; this.fallback = fallback;
            var status = git4idea.index.GitStageTrackerKt.status(git4idea.index.GitStageTracker.getInstance(project), file);
            untracked = status != null && status.isUntracked();
            position = NavigationPosition.capture(source.getDocument(), source.getCaretModel().getOffset());
            Disposer.register(SourceCompareService.this, lifetime);
            Disposer.register(lifetime, position::close);
            alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, lifetime);
        }

        boolean valid() {
            if (pending != this) return false;
            if (project.isDisposed() || window.isDisposed() || source.isDisposed() || !file.isValid()
                    || !NavigationSettings.get(project).getState().enabled
                    || FileEditorManagerEx.getInstanceEx(project).getCurrentWindow() != window
                    || !(opened ? preview : file).equals(window.getSelectedFile())) { cancel(); return false; }
            return true;
        }

        void start() {
            if (!valid()) return;
            var tool = ToolWindowManager.getInstance(project).getToolWindow("Commit");
            if (tool == null) { fail(); return; }
            own++;
            try { tool.show(); } finally { own--; }
            advance();
        }

        void bind(GitStagePanel panel) {
            if (panel == null || !(panel.getTree() instanceof GitStageTree found) || tree == found) return;
            tree = found;
            var mouse = new MouseAdapter() { @Override public void mousePressed(MouseEvent event) { if (own == 0) cancel(); } };
            var keys = new KeyAdapter() { @Override public void keyPressed(KeyEvent event) { if (own == 0) cancel(); } };
            tree.addMouseListener(mouse); tree.addKeyListener(keys);
            Disposer.register(lifetime, () -> { found.removeMouseListener(mouse); found.removeKeyListener(keys); });
            tree.addSelectionListener(() -> { if (own == 0 && tree.hasFocus() && !tree.isModelUpdateInProgress()) cancel(); }, lifetime);
        }

        void retry() { alarm.addRequest(this::advance, 100, ModalityState.nonModal()); }

        void advance() {
            if (!valid()) return;
            if (System.nanoTime() > deadline) { fail(); return; }
            var tool = ToolWindowManager.getInstance(project).getToolWindow("Commit");
            if (tool == null || !tool.isVisible()) { cancel(); return; }
            var panel = find(tool.getContentManager().getComponent(), GitStagePanel.class);
            if (panel == null) { retry(); return; }
            bind(panel);
            if (tree == null) { fail(); return; }
            if (SwingUtilities.getWindowAncestor(panel) != SwingUtilities.getWindowAncestor(window.getOwner())) { fail(); return; }
            if (tree.isModelUpdateInProgress()) { retry(); return; }
            Wrapper target = GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(tree).find(w ->
                    w.getUserObject() instanceof GitFileStatusNode node && (node.getKind() == NodeKind.UNSTAGED || untracked && node.getKind() == NodeKind.UNTRACKED)
                            && file.equals(node.getFilePath().getVirtualFile()));
            if (target == null) { retry(); return; }
            if (!supported((GitFileStatusNode) target.getUserObject())) { fail(); return; }
            if (!opened) {
                var data = DiffDataKeys.EDITOR_TAB_DIFF_PREVIEW.getData(DataManager.getInstance().getDataContext(panel));
                if (!(data instanceof EditorTabDiffPreview tab) || tab.isDisposed()) { fail(); return; }
                preview = tab.getPreviewFile();
                project.getService(StageNavigationService.class).beforeExplicitComparison(tree);
                own++;
                try {
                    GitStageDiffPreviewHandler.INSTANCE.selectChange(tree, target);
                    var composite = FileEditorManagerEx.getInstanceEx(project).openFile(preview, window, new FileEditorOpenOptions()
                            .withSelectAsCurrent(true).withRequestFocus(true).withReuseOpen(false)
                            .withOpenMode(FileEditorManagerImpl.OpenMode.DEFAULT));
                    for (var editor : composite.getAllEditors()) if (editor instanceof DiffEditorViewerFileEditor diff
                            && diff.getEditorViewer() instanceof GitStageDiffRequestProcessor processor && processor.getStageTree() == tree) stage = processor;
                    opened = preview.equals(window.getSelectedFile());
                    if (stage != null) {
                        boolean stale = stage.getActiveViewer() instanceof com.intellij.diff.tools.util.base.DiffViewerBase viewer && StageRefreshService.isStale(viewer);
                        stage.setCurrentChange(target);
                        if (stale) stage.updateRequest(true, false, null);
                    }
                } finally { own--; }
                if (!valid()) return;
                if (stage == null || !opened) { fail(); return; }
            }
            if (stage.isDisposed()) { cancel(); return; }
            if (!target.equals(stage.getCurrentChange())) { fail(); return; }
            StageDiffView view = StageDiffView.current(stage);
            if (view == null) {
                // Loading has no supported text viewer yet; timeout retains native comparison semantics.
                retry(); return;
            }
            var selected = window.getSelectedComposite();
            if (selected == null || !(selected.getSelectedEditor() instanceof DiffEditorViewerFileEditor diff)
                    || diff.getEditorViewer() != stage) { cancel(); return; }
            Editor editor = view.editor(Side.RIGHT);
            int offset = position.resolve(editor.getDocument());
            if (offset >= 0) {
                view.scrollToLine(Side.RIGHT, editor.getDocument().getLineNumber(offset));
                editor.getCaretModel().moveToOffset(offset);
                DiffUtil.scrollToCaret(editor, false);
            }
            // Position mapping is best-effort; confirmed INDEX↔LOCAL remains open if it fails.
            project.getService(StageNavigationService.class).explicitComparisonOpened(stage, file, position);
            cancel();
        }

        void fail() {
            if (!valid()) return;
            cancel();
            if (!untracked) fallback.run();
            else {
                if (opened) window.setSelectedComposite(file, true);
                project.getService(StageNavigationService.class).comparisonFailed("无法打开新增文件提交预览，请从提交面板重试");
            }
        }
    }

    private static boolean supported(GitFileStatusNode node) {
        var status = node.getStatus();
        return !status.isConflicted() && status.getOrigPath() == null && status.getIndex() != 'R' && status.getIndex() != 'C'
                && status.getWorkTree() != 'R' && status.getWorkTree() != 'C';
    }

    private static <T> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container parent) for (Component child : parent.getComponents()) {
            T found = find(child, type); if (found != null) return found;
        }
        return null;
    }
    @Override public void dispose() { cancel(); }
}
