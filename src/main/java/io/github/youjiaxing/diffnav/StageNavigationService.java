package io.github.youjiaxing.diffnav;

import com.intellij.diff.DiffContext;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.util.DiffUtil;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.diff.tools.util.base.DiffViewerListener;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.platform.backend.navigation.impl.SourceNavigationRequest;
import com.intellij.platform.ide.navigation.NavigationOptions;
import com.intellij.openapi.vcs.changes.ChangeViewDiffRequestProcessor.Wrapper;
import git4idea.index.ui.GitFileStatusNode;
import git4idea.index.ui.GitStageDiffPreviewHandler;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import git4idea.index.ui.NodeKind;
import git4idea.index.vfs.GitIndexVirtualFile;
import java.awt.Component;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** All mutable state belongs to a project and is confined to the EDT. */
@Service(Service.Level.PROJECT)
public final class StageNavigationService implements Disposable {
    static final Key<Boolean> STAGE_EDITOR = Key.create("diffnav.stage.editor");
    private static final Key<StageNavigationService> OWNER = Key.create("diffnav.pending.owner");
    private final Project project;
    private final ReviewHistory<Location> history = new ReviewHistory<>(100, Location::sameFile);
    private WeakReference<GitStageDiffRequestProcessor> session = new WeakReference<>(null);
    private Pending pending;
    private Disposable operation;
    private volatile boolean enabled = true;

    public StageNavigationService(Project project) { this.project = project; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) {
        enabled = value;
        history.clear();
        cancelPending();
    }

    static GitStageDiffRequestProcessor processor(DataContext context) {
        Editor editor = CommonDataKeys.EDITOR.getData(context);
        if (editor == null) return null;
        for (Component c = editor.getComponent(); c != null; c = c.getParent()) {
            if (c instanceof DiffRequestProcessor.MyPanel panel
                    && panel.getProcessor() instanceof GitStageDiffRequestProcessor stage
                    && stage.getContext() == DiffDataKeys.DIFF_CONTEXT.getData(context)) return stage;
        }
        return null;
    }

    boolean navigate(SourceNavigationRequest request, NavigationOptions options, DataContext context) {
        if (!enabled || pending != null || request.getOffsetMarker() == null
                || !request.getOffsetMarker().isValid()) return false;
        // Splits and caret preservation have different semantics: leave them to the IDE.
        if (!(options instanceof NavigationOptions.Impl flags) || flags.openInRightSplit
                || flags.getPreserveCaret()) return false;
        GitStageDiffRequestProcessor stage = processor(context);
        if (stage == null || stage.isDisposed() || !supported(stage.getActiveViewer())) return false;
        VirtualFile file = request.getFile();
        if (!file.isValid() || !file.isInLocalFileSystem() || file.isDirectory()) return false;
        Wrapper target = findTarget(stage, file.getPath(), null);
        if (target == null) return false;
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) return false;
        Location from = snapshot(stage);
        if (from == null || previewFile(stage) == null) return false;
        Location to = new Location(file.getPath(), kind(target), Side.RIGHT,
                request.getOffsetMarker().getStartOffset(), digest(document));
        if (session.get() != stage) {
            history.clear();
            session = new WeakReference<>(stage);
        }
        return start(stage, target, to, flags.getRequestFocus(), true, () -> history.record(from, to));
    }

    private boolean start(GitStageDiffRequestProcessor stage, Wrapper target, Location to,
                          boolean focus, boolean allowSourceFallback, Runnable commit) {
        if (pending != null || stage.isDisposed()) return false;
        boolean sameChange = Objects.equals(stage.getCurrentChange(), target);
        pending = new Pending(stage, target, to, focus, allowSourceFallback, commit);
        operation = Disposer.newDisposable("Diff navigation");
        Disposer.register(this, operation);
        stage.getContext().putUserData(OWNER, this);
        stage.addListener((com.intellij.diff.impl.DiffRequestProcessorListener) () -> {
            if (stage.getActiveViewer() instanceof DiffViewerBase viewer && !viewer.hasPendingRediff()) finish(viewer);
        }, operation);
        Pending expected = pending;
        VirtualFile preview = previewFile(stage);
        project.getMessageBus().connect(operation).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override public void fileClosed(FileEditorManager manager, VirtualFile file) {
                        if (file.equals(preview)) cancelPending();
                    }
                    @Override public void selectionChanged(com.intellij.openapi.fileEditor.FileEditorManagerEvent event) {
                        if (!Objects.equals(event.getNewFile(), preview)) cancelPending();
                    }
                });
        stage.getStageTree().addSelectionListener(() -> {
            if (pending == expected && !selectionContainsTarget(expected)) cancelPending();
        }, operation);
        var timeout = new com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, operation);
        timeout.addRequest(() -> {
            if (pending == expected) {
                if (!stage.isDisposed() && selectionContainsTarget(expected)
                        && preview != null && FileEditorManager.getInstance(project).isFileOpen(preview)) fallback(expected);
                else cancelPending();
            }
        }, 5000);
        // The native history has no per-file identity inside this shared preview.
        // Keep these transitions in the review history instead of recording duplicate native places.
        IdeDocumentHistory.getInstance(project).reallyExcludeCurrentCommandAsNavigation();
        GitStageDiffPreviewHandler.INSTANCE.selectChange(stage.getStageTree(), target);
        stage.setCurrentChange(target);
        if (sameChange && stage.getActiveViewer() instanceof DiffViewerBase viewer
                && !viewer.hasPendingRediff()) finish(viewer);
        return true;
    }

    static void viewerCreated(FrameDiffTool.DiffViewer viewer, DiffContext context, DiffRequest request) {
        if ("Stage".equals(context.getUserData(com.intellij.diff.util.DiffUserDataKeys.PLACE))
                && viewer instanceof TwosideTextDiffViewer two) {
            for (Editor editor : two.getEditors()) editor.putUserData(STAGE_EDITOR, true);
        }
        StageNavigationService owner = context.getUserData(OWNER);
        if (owner == null || !(viewer instanceof DiffViewerBase base)) return;
        base.addListener(new DiffViewerListener() {
            @Override protected void onAfterRediff() {
                ApplicationManager.getApplication().invokeLater(() -> owner.finish(base), ModalityState.nonModal());
            }
        });
    }

    private void finish(DiffViewerBase viewer) {
        Pending p = pending;
        if (p == null || p.stage().isDisposed() || viewer.isDisposed()
                || p.stage().getActiveViewer() != viewer
                || !Objects.equals(p.stage().getCurrentChange(), p.wrapper())) return;
        if (!matchesActiveRequest(p.stage())) return;
        if (!(viewer instanceof TwosideTextDiffViewer two)) { fallback(p); return; }
        Editor editor = two.getEditor(p.location().side());
        // New declarations use working-tree coordinates; history uses the exact side
        // saved on departure, which may be HEAD or INDEX in a partially staged file.
        if (!digest(editor.getDocument()).equals(p.location().digest())) {
            fallback(p);
            return;
        }
        int offset = Math.clamp(p.location().offset(), 0, editor.getDocument().getTextLength());
        int line = editor.getDocument().getLineNumber(offset);
        two.scrollToLine(p.location().side(), line);
        editor.getCaretModel().moveToOffset(offset);
        DiffUtil.scrollToCaret(editor, false);
        VirtualFile preview = previewFile(p.stage());
        if (preview != null) FileEditorManager.getInstance(project).openFile(preview, p.focus(), true);
        if (p.focus()) IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true);
        cancelPending();
        p.commit().run();
    }

    private void fallback(Pending p) {
        cancelPending();
        if (!p.allowSourceFallback()) { history.clear(); return; }
        VirtualFile file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(p.location().path());
        if (file != null) new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, p.location().offset()).navigate(p.focus());
    }

    boolean canTravel(DataContext context, int direction) {
        GitStageDiffRequestProcessor stage = processor(context);
        return enabled && pending == null && stage != null && !stage.isDisposed() && stage == session.get()
                && history.current() != null && matchesActiveRequest(stage)
                && FileUtil.pathsEqual(stage.getCurrentChange().getFilePath().getPath(), history.current().path())
                && kind(stage.getCurrentChange()) == history.current().kind()
                && history.peek(direction) != null;
    }

    boolean travel(DataContext context, int direction) {
        if (!canTravel(context, direction)) return false;
        GitStageDiffRequestProcessor stage = session.get();
        Location to = history.peek(direction);
        Location from = snapshot(stage);
        Wrapper target = findTarget(stage, to.path(), to.kind());
        if (target == null || from == null) { history.clear(); return false; }
        return start(stage, target, to, true, false, () -> history.commitTravel(direction, from));
    }

    private static Wrapper findTarget(GitStageDiffRequestProcessor stage, String path, NodeKind required) {
        Wrapper best = null;
        for (Wrapper w : stage.iterateAllChanges()) {
            if (!(w.getUserObject() instanceof GitFileStatusNode node)
                    || !FileUtil.pathsEqual(node.getFilePath().getPath(), path)) continue;
            NodeKind k = node.getKind();
            if (required != null && k != required) continue;
            if (k == NodeKind.UNSTAGED || k == NodeKind.UNTRACKED) return w;
            if (k == NodeKind.STAGED) best = w;
        }
        return best;
    }

    private static NodeKind kind(Wrapper w) { return ((GitFileStatusNode) w.getUserObject()).getKind(); }

    private static boolean supported(FrameDiffTool.DiffViewer viewer) {
        return viewer instanceof TwosideTextDiffViewer;
    }

    private static Location snapshot(GitStageDiffRequestProcessor stage) {
        if (stage == null || stage.isDisposed() || stage.getCurrentChange() == null
                || !(stage.getCurrentChange().getUserObject() instanceof GitFileStatusNode node)) return null;
        if (!matchesActiveRequest(stage) || !(stage.getActiveViewer() instanceof TwosideTextDiffViewer two)) return null;
        Editor editor = two.getCurrentEditor();
        Side side = editor == two.getEditor1() ? Side.LEFT : Side.RIGHT;
        return new Location(node.getFilePath().getPath(), node.getKind(), side,
                editor.getCaretModel().getOffset(), digest(editor.getDocument()));
    }

    private static boolean selectionContainsTarget(Pending p) {
        return p.stage().getStageTree().selectedStatusNodes().contains(p.wrapper().getUserObject());
    }

    private static boolean matchesActiveRequest(GitStageDiffRequestProcessor stage) {
        if (!(stage.getActiveViewer() instanceof TwosideTextDiffViewer two) || two.hasPendingRediff()
                || !(stage.getCurrentChange() != null && stage.getCurrentChange().getUserObject() instanceof GitFileStatusNode node)) return false;
        VirtualFile file = FileDocumentManager.getInstance().getFile(two.getEditor2().getDocument());
        boolean index = file instanceof GitIndexVirtualFile;
        String path = index ? ((GitIndexVirtualFile) file).getFilePath().getPath() : file == null ? null : file.getPath();
        return path != null && FileUtil.pathsEqual(path, node.getFilePath().getPath())
                && index == (node.getKind() == NodeKind.STAGED);
    }

    private static String digest(Document document) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(document.getText().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private VirtualFile previewFile(GitStageDiffRequestProcessor stage) {
        for (var editor : FileEditorManager.getInstance(project).getAllEditors()) {
            if (editor instanceof DiffEditorViewerFileEditor diff && diff.getEditorViewer() == stage) return editor.getFile();
        }
        return null;
    }

    private void cancelPending() {
        if (pending != null) pending.stage().getContext().putUserData(OWNER, null);
        pending = null;
        if (operation != null) { Disposer.dispose(operation); operation = null; }
    }

    @Override public void dispose() { cancelPending(); history.clear(); session.clear(); }

    private record Location(String path, NodeKind kind, Side side, int offset, String digest) {
        boolean sameFile(Location other) { return kind == other.kind && FileUtil.pathsEqual(path, other.path); }
    }
    private record Pending(GitStageDiffRequestProcessor stage, Wrapper wrapper, Location location,
                           boolean focus, boolean allowSourceFallback, Runnable commit) {}
}
