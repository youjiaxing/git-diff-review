package io.github.youjiaxing.diffnav;

import com.intellij.diff.DiffContext;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.diff.tools.util.base.DiffViewerListener;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.DiffUtil;
import com.intellij.diff.util.Side;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.changes.ChangeViewDiffRequestProcessor.Wrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.platform.backend.navigation.impl.SourceNavigationRequest;
import com.intellij.platform.ide.navigation.NavigationOptions;
import git4idea.index.ui.GitFileStatusNode;
import git4idea.index.ui.GitStageDiffPreviewHandler;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import git4idea.index.ui.NodeKind;
import git4idea.index.vfs.GitIndexVirtualFile;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import javax.swing.SwingUtilities;

/** Sessions own mixed source/diff histories. All mutable state is confined to the EDT. */
@Service(Service.Level.PROJECT)
public final class StageNavigationService implements Disposable {
    static final Key<Boolean> STAGE_EDITOR = Key.create("diffnav.stage.editor");
    private static final Key<StageNavigationService> OWNER = Key.create("diffnav.pending.owner");
    private final Project project;
    private final Map<GitStageDiffRequestProcessor, ReviewSession> sessions = new IdentityHashMap<>();
    private final Map<GitStageDiffRequestProcessor, Boolean> paused = new java.util.WeakHashMap<>();
    private ReviewSession active;
    private Pending pending;
    private Disposable operation;
    private boolean openingEditor;
    private String lastReason = "尚未开始审阅";

    public StageNavigationService(Project project) {
        this.project = project;
        com.intellij.ide.IdeEventQueue.getInstance().addDispatcher(event -> {
            if (!(event instanceof java.awt.event.MouseEvent mouse) || mouse.getID() != java.awt.event.MouseEvent.MOUSE_CLICKED
                    || mouse.getClickCount() != 2 || mouse.getButton() != java.awt.event.MouseEvent.BUTTON1
                    || mouse.getModifiersEx() != 0 || !(mouse.getSource() instanceof javax.swing.JTree tree)) return false;
            var path = tree.getPathForLocation(mouse.getX(), mouse.getY());
            if (path == null || !tree.isPathSelected(path) || !tree.getModel().isLeaf(path.getLastPathComponent())) return false;
            var context = com.intellij.ide.DataManager.getInstance().getDataContext(tree);
            return CommonDataKeys.PROJECT.getData(context) == project && UsageNavigation.navigate(project, context);
        }, this);
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override public void fileClosed(FileEditorManager manager, VirtualFile file) {
                        for (var session : java.util.List.copyOf(sessions.values())) {
                            if (session.preview.equals(file)) remove(session);
                        }
                    }
                    @Override public void selectionChanged(FileEditorManagerEvent event) {
                        if (openingEditor) return;
                        for (ReviewSession review : sessions.values()) {
                            if (review.preview.equals(event.getNewFile()) && review.parked != null && pending == null) {
                                restoreParked(review);
                                break;
                            }
                        }
                        if (pending != null && !pending.session.preview.equals(event.getNewFile())) cancelPending();
                        if (active != null && !selected(active)) active = null;
                        updateStatus();
                    }
                });
    }

    public boolean isEnabled() { return NavigationSettings.get(project).getState().enabled; }
    boolean isNavigating(git4idea.index.ui.GitStageTree tree) { return pending != null && pending.session.stage.getStageTree() == tree; }
    void beforeAutomaticTreeSelection(git4idea.index.ui.GitStageTree tree) {
        for (ReviewSession review : sessions.values()) {
            if (review.stage.getStageTree() == tree && review.parked == null && !review.preview.equals(selectedFile())) review.parked = snapshotDiff(review.stage);
        }
    }
    void manualTreeSelection(git4idea.index.ui.GitStageTree tree) {
        if (isNavigating(tree)) cancelPending();
        for (ReviewSession review : sessions.values()) if (review.stage.getStageTree() == tree && review.parked != null) {
            review.parked.close(); review.parked = null;
        }
    }
    private void restoreParked(ReviewSession review) {
        Location to = review.parked; review.parked = null;
        Wrapper wrapper = findTarget(review.stage, to.file, to.kind, NavigationSettings.TargetScope.FOLLOW_TARGET);
        if (wrapper == null) { to.close(); return; }
        Location current = review.history.current();
        Location from = current == null ? to.copy() : current.copy();
        start(review, wrapper, from, to, false, (departed, arrived) -> {
            // An explicit return to the preview is a user transition; the background tree follow isn't.
            if (departed.samePlace(arrived) && departed.kind == arrived.kind) { departed.close(); arrived.close(); }
            else review.history.record(departed, arrived);
        }, true);
    }
    public void setEnabled(boolean value) {
        NavigationSettings.get(project).getState().enabled = value;
        settingsChanged();
    }
    public void settingsChanged() {
        cancelPending();
        sessions.values().forEach(ReviewSession::close);
        sessions.clear();
        paused.clear();
        active = null;
        reason(isEnabled() ? "设置已更新，从 Diff 开始下一次审阅" : "审阅导航已禁用");
    }
    public String getLastReason() { return lastReason; }
    public String statusText() { return !isEnabled() ? "Diff Review: Off" : active == null ? "Diff Review: Idle" : "Diff Review: On"; }
    public boolean isReviewing(DataContext context) { return session(context, false) != null; }
    public boolean canToggleReview(DataContext context) { return isEnabled() && (contextStage(context) != null || session(context, false) != null); }
    public void toggleReview(DataContext context) {
        ReviewSession current = session(context, false);
        if (current != null) {
            paused.put(current.stage, true);
            remove(current);
            reason("审阅已暂停，普通导航已恢复");
        } else {
            var stage = contextStage(context);
            if (isEnabled() && stage != null && supported(stage.getActiveViewer())) {
                paused.remove(stage);
                createSession(stage);
                reason("审阅已开始");
            }
        }
    }

    public void openCurrentSource(DataContext context) {
        ReviewSession review = session(context, true);
        if (review == null) return;
        Location from = snapshot(review, context);
        if (from == null) return;
        cancelPending();
        reason("本次打开普通源码，审阅会话保留");
        openSource(review, from, from.copy(), true, review.history::record);
    }

    static GitStageDiffRequestProcessor processor(DataContext context) {
        Editor editor = CommonDataKeys.EDITOR.getData(context);
        if (editor == null) return null;
        for (Component c = editor.getComponent(); c != null; c = c.getParent()) {
            if (c instanceof DiffRequestProcessor.MyPanel panel && panel.getProcessor() instanceof GitStageDiffRequestProcessor stage
                    && stage.getContext() == DiffDataKeys.DIFF_CONTEXT.getData(context)) return stage;
        }
        return null;
    }

    private GitStageDiffRequestProcessor contextStage(DataContext context) {
        var direct = processor(context);
        if (direct != null) return direct;
        var selected = selectedFile();
        if (selected != null) for (var editor : FileEditorManager.getInstance(project).getEditors(selected)) {
            if (editor instanceof DiffEditorViewerFileEditor diff && diff.getEditorViewer() instanceof GitStageDiffRequestProcessor stage) return stage;
        }
        return null;
    }

    boolean navigate(SourceNavigationRequest request, NavigationOptions options, DataContext context) {
        if (!isEnabled() || request.getOffsetMarker() == null || !request.getOffsetMarker().isValid()) return false;
        if (!(options instanceof NavigationOptions.Impl flags) || flags.openInRightSplit || flags.getPreserveCaret()) {
            reason("保留原生分屏或光标行为");
            return false;
        }
        VirtualFile file = request.getFile();
        if (!file.isValid() || !file.isInLocalFileSystem() || file.isDirectory()) return false;
        ReviewSession review = session(context, true);
        if (review == null) return false;
        Location from = pending != null && pending.session == review ? pending.from.copy() : snapshot(review, context);
        if (from == null) return false;
        cancelPending();
        var document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) { from.close(); return false; }
        Location to = new Location(file, null, Side.RIGHT, NavigationPosition.capture(document, request.getOffsetMarker().getStartOffset()));
        active = review;
        IdeDocumentHistory.getInstance(project).reallyExcludeCurrentCommandAsNavigation();
        var scope = NavigationSettings.get(project).getState().targetScope;
        Wrapper target = findTarget(review.stage, file, null, scope);
        BiConsumer<Location, Location> commit = review.history::record;
        if (target == null) {
            reason(scope == NavigationSettings.TargetScope.STAGED_ONLY ? "目标没有可用的已暂存 Diff，打开源码" : "目标没有可用的暂存区 Diff，打开源码");
            return openSource(review, from, to, flags.getRequestFocus(), commit);
        }
        to.kind = kind(target);
        return start(review, target, from, to, flags.getRequestFocus(), commit);
    }

    private ReviewSession session(DataContext context, boolean autoStart) {
        if (!isEnabled()) return null;
        var stage = contextStage(context);
        if (stage != null) {
            if (stage.isDisposed() || !supported(stage.getActiveViewer())) return null;
            ReviewSession review = sessions.get(stage);
            if (review == null && autoStart && processor(context) == stage
                    && NavigationSettings.get(project).getState().autoStart && !paused.containsKey(stage)) review = createSession(stage);
            if (review != null) active = review;
            return review;
        }
        return active != null && selected(active) ? active : null;
    }

    private ReviewSession createSession(GitStageDiffRequestProcessor stage) {
        VirtualFile preview = previewFile(stage);
        if (preview == null) return null;
        var review = new ReviewSession(stage, preview, FileEditorManagerEx.getInstanceEx(project).getCurrentWindow());
        sessions.put(stage, review);
        active = review;
        updateStatus();
        return review;
    }

    private boolean selected(ReviewSession session) {
        if (session.stage.isDisposed() || !FileEditorManager.getInstance(project).isFileOpen(session.preview)) return false;
        var window = FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
        if (session.window.get() != window || window == null) return false;
        VirtualFile selected = window.getSelectedFile();
        if (session.preview.equals(selected)) return true;
        Location current = session.history.current();
        return current != null && current.kind == null && current.file.equals(selected);
    }

    private boolean start(ReviewSession session, Wrapper target, Location from, Location to, boolean focus, BiConsumer<Location, Location> commit) {
        return start(session, target, from, to, focus, commit, false);
    }
    private boolean start(ReviewSession session, Wrapper target, Location from, Location to, boolean focus,
                          BiConsumer<Location, Location> commit, boolean passiveRestore) {
        if (session.parked != null) { session.parked.close(); session.parked = null; }
        if (session.stage.isDisposed()) { from.close(); to.close(); return false; }
        boolean sameChange = Objects.equals(session.stage.getCurrentChange(), target);
        pending = new Pending(session, target, from, to, focus, KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(), commit);
        pending.passiveRestore = passiveRestore;
        Pending expected = pending;
        operation = Disposer.newDisposable("Diff review navigation");
        Disposer.register(this, operation);
        session.stage.getContext().putUserData(OWNER, this);
        session.stage.addListener((com.intellij.diff.impl.DiffRequestProcessorListener) () -> {
            if (session.stage.getActiveViewer() instanceof DiffViewerBase viewer && !viewer.hasPendingRediff()) finish(viewer);
        }, operation);
        session.stage.getStageTree().addSelectionListener(() -> {
            if (pending == expected && !selectionContainsTarget(expected)) cancelPending();
        }, operation);
        var timeout = new com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, operation);
        timeout.addRequest(() -> {
            if (pending == expected) {
                if (session.stage.getActiveViewer() instanceof DiffViewerBase viewer) finish(viewer);
                if (pending != expected) return;
                if (selected(session) && selectionContainsTarget(expected)) fallback(expected, "Diff 加载超时，打开源码");
                else cancelPending();
            }
        }, 5000);
        // A hidden preview postpones applying its request; select its existing tab before loading.
        openingEditor = true;
        try {
            var window = session.window.get();
            if (window != null) window.setSelectedComposite(session.preview, focus);
        } finally { openingEditor = false; }
        GitStageDiffPreviewHandler.INSTANCE.selectChange(session.stage.getStageTree(), target);
        session.stage.setCurrentChange(target);
        if (sameChange && session.stage.getActiveViewer() instanceof DiffViewerBase viewer && !viewer.hasPendingRediff()) finish(viewer);
        return true;
    }

    static void viewerCreated(FrameDiffTool.DiffViewer viewer, DiffContext context, DiffRequest request) {
        if ("Stage".equals(context.getUserData(com.intellij.diff.util.DiffUserDataKeys.PLACE)) && viewer instanceof TwosideTextDiffViewer two) {
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
        if (p == null || p.session.stage.isDisposed() || viewer.isDisposed() || p.session.stage.getActiveViewer() != viewer
                || !Objects.equals(p.session.stage.getCurrentChange(), p.wrapper) || !matchesActiveRequest(p.session.stage)) return;
        if (!(viewer instanceof TwosideTextDiffViewer two)) { fallback(p, "目标视图不支持双栏定位，打开源码"); return; }
        Editor editor = two.getEditor(p.to.side);
        int offset = p.to.position.resolve(editor.getDocument());
        if (offset < 0) { fallback(p, "目标版本或位置已变化，安全回退到源码"); return; }
        two.scrollToLine(p.to.side, editor.getDocument().getLineNumber(offset));
        editor.getCaretModel().moveToOffset(offset);
        DiffUtil.scrollToCaret(editor, false);
        boolean focus = focusAllowed(p);
        openingEditor = true;
        try {
            var window = p.session.window.get();
            if (window != null) window.setSelectedComposite(p.session.preview, focus);
            if (focus) IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true);
        } finally { openingEditor = false; }
        Location arrived = new Location(p.to.file, kind(p.wrapper), p.to.side, NavigationPosition.capture(editor.getDocument(), offset));
        detachPending();
        p.to.close();
        p.commit.accept(p.from, arrived);
        active = p.session;
        reason(kind(p.wrapper) == NodeKind.STAGED ? "已进入 HEAD ↔ INDEX" : "已进入 INDEX ↔ 本地");
    }

    private boolean focusAllowed(Pending p) {
        if (!p.focus) return false;
        Component current = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (current == null || current == p.focusOwner.get()) return true;
        return p.session.stage.getActiveViewer() != null && SwingUtilities.isDescendingFrom(current, p.session.stage.getActiveViewer().getComponent());
    }

    private void fallback(Pending p, String why) {
        boolean focus = focusAllowed(p);
        detachPending();
        reason(why);
        if (p.passiveRestore) { p.from.close(); p.to.close(); return; }
        openSource(p.session, p.from, p.to, focus, p.commit);
    }

    private boolean openSource(ReviewSession review, Location from, Location to, boolean focus, BiConsumer<Location, Location> commit) {
        if (!to.file.isValid()) { from.close(); to.close(); reason("目标文件已不存在，保留其他历史"); return false; }
        var document = FileDocumentManager.getInstance().getDocument(to.file);
        int offset = document == null ? -1 : to.position.resolve(document);
        openingEditor = true;
        Editor editor;
        try {
            var descriptor = offset < 0 ? new OpenFileDescriptor(project, to.file) : new OpenFileDescriptor(project, to.file, offset);
            editor = FileEditorManager.getInstance(project).openTextEditor(descriptor.setUseCurrentWindow(true), focus);
        } finally { openingEditor = false; }
        to.close();
        if (editor == null) { from.close(); return false; }
        Location arrived = new Location(to.file, null, Side.RIGHT, NavigationPosition.capture(editor.getDocument(), editor.getCaretModel().getOffset()));
        commit.accept(from, arrived);
        active = review;
        if (offset < 0) reason("无法确认目标位置，已打开源码文件而未使用旧坐标");
        else updateStatus();
        return true;
    }

    boolean canTravel(DataContext context, int direction) {
        ReviewSession review = session(context, false);
        if (review == null || pending != null || review.history.current() == null || review.history.peek(direction) == null) return false;
        if (review.preview.equals(selectedFile())) {
            if (!matchesActiveRequest(review.stage)) return false;
            var node = (GitFileStatusNode) review.stage.getCurrentChange().getUserObject();
            Location current = review.history.current();
            return current.file.equals(node.getFilePath().getVirtualFile());
        }
        return selected(review);
    }

    boolean travel(DataContext context, int direction) {
        cancelPending();
        if (!canTravel(context, direction)) return false;
        ReviewSession review = session(context, false);
        Location from = snapshot(review, context);
        if (from == null) return false;
        while (review.history.peek(direction) != null && !review.history.peek(direction).file.isValid()) review.history.discardNext(direction);
        Location saved = review.history.peek(direction);
        if (saved == null) { from.close(); reason("已跳过不存在的历史文件"); return false; }
        Location to = saved.copy();
        IdeDocumentHistory.getInstance(project).reallyExcludeCurrentCommandAsNavigation();
        BiConsumer<Location, Location> commit = (departed, arrived) -> review.history.commitTravel(direction, departed, arrived);
        if (to.kind == null) return openSource(review, from, to, true, commit);
        Wrapper target = findTarget(review.stage, to.file, to.kind, NavigationSettings.TargetScope.FOLLOW_TARGET);
        if (target == null) target = findTarget(review.stage, to.file, null, NavigationSettings.TargetScope.FOLLOW_TARGET);
        if (target == null) {
            reason("历史文件已无可用 Diff，打开源码并保留审阅路径");
            return openSource(review, from, to, true, commit);
        }
        to.kind = kind(target);
        return start(review, target, from, to, true, commit);
    }

    private Location snapshot(ReviewSession review, DataContext context) {
        if (processor(context) == review.stage || review.preview.equals(selectedFile())) return snapshotDiff(review.stage);
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        VirtualFile file = editor == null ? null : FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file == null || !file.isInLocalFileSystem() ? null
                : new Location(file, null, Side.RIGHT, NavigationPosition.capture(editor.getDocument(), editor.getCaretModel().getOffset()));
    }

    private static Location snapshotDiff(GitStageDiffRequestProcessor stage) {
        if (!matchesActiveRequest(stage) || !(stage.getActiveViewer() instanceof TwosideTextDiffViewer two)) return null;
        var node = (GitFileStatusNode) stage.getCurrentChange().getUserObject();
        VirtualFile file = node.getFilePath().getVirtualFile();
        if (file == null) return null;
        Editor editor = two.getCurrentEditor();
        return new Location(file, node.getKind(), editor == two.getEditor1() ? Side.LEFT : Side.RIGHT,
                NavigationPosition.capture(editor.getDocument(), editor.getCaretModel().getOffset()));
    }

    private static Wrapper findTarget(GitStageDiffRequestProcessor stage, VirtualFile file, NodeKind required, NavigationSettings.TargetScope scope) {
        Wrapper best = null;
        for (Wrapper wrapper : stage.iterateAllChanges()) {
            if (!(wrapper.getUserObject() instanceof GitFileStatusNode node) || !file.equals(node.getFilePath().getVirtualFile())) continue;
            NodeKind kind = node.getKind();
            if (required != null && kind != required) continue;
            if (scope != NavigationSettings.TargetScope.STAGED_ONLY && (kind == NodeKind.UNSTAGED || kind == NodeKind.UNTRACKED)) return wrapper;
            if (kind == NodeKind.STAGED) best = wrapper;
        }
        return best;
    }

    private static NodeKind kind(Wrapper wrapper) { return ((GitFileStatusNode) wrapper.getUserObject()).getKind(); }
    private static boolean supported(FrameDiffTool.DiffViewer viewer) { return viewer instanceof TwosideTextDiffViewer; }
    private static boolean selectionContainsTarget(Pending p) {
        return !p.session.stage.isDisposed() && p.session.stage.getStageTree().selectedStatusNodes().contains(p.wrapper.getUserObject());
    }
    private static boolean matchesActiveRequest(GitStageDiffRequestProcessor stage) {
        if (stage.isDisposed() || !(stage.getActiveViewer() instanceof TwosideTextDiffViewer two) || two.hasPendingRediff()
                || stage.getCurrentChange() == null || !(stage.getCurrentChange().getUserObject() instanceof GitFileStatusNode node)) return false;
        VirtualFile file = FileDocumentManager.getInstance().getFile(two.getEditor2().getDocument());
        boolean index = file instanceof GitIndexVirtualFile;
        VirtualFile local = index ? ((GitIndexVirtualFile) file).getFilePath().getVirtualFile() : file;
        return local != null && local.equals(node.getFilePath().getVirtualFile()) && index == (node.getKind() == NodeKind.STAGED);
    }
    private VirtualFile previewFile(GitStageDiffRequestProcessor stage) {
        for (var editor : FileEditorManager.getInstance(project).getAllEditors()) {
            if (editor instanceof DiffEditorViewerFileEditor diff && diff.getEditorViewer() == stage) return editor.getFile();
        }
        return null;
    }
    private VirtualFile selectedFile() {
        EditorWindow window = FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
        return window == null ? null : window.getSelectedFile();
    }
    private void reason(String text) { lastReason = text; updateStatus(); }
    private void updateStatus() {
        var status = WindowManager.getInstance().getStatusBar(project);
        if (status != null) status.updateWidget(ReviewStatusWidget.ID);
    }
    private void remove(ReviewSession review) {
        if (pending != null && pending.session == review) cancelPending();
        sessions.remove(review.stage);
        review.close();
        if (active == review) active = null;
        updateStatus();
    }
    private void detachPending() {
        if (pending != null) pending.session.stage.getContext().putUserData(OWNER, null);
        pending = null;
        if (operation != null) { Disposer.dispose(operation); operation = null; }
    }
    private void cancelPending() {
        Pending abandoned = pending;
        detachPending();
        if (abandoned != null) { abandoned.from.close(); abandoned.to.close(); }
    }
    @Override public void dispose() {
        cancelPending();
        sessions.values().forEach(ReviewSession::close);
        sessions.clear();
        paused.clear();
        active = null;
    }

    private static final class ReviewSession {
        final GitStageDiffRequestProcessor stage;
        final VirtualFile preview;
        final WeakReference<EditorWindow> window;
        final ReviewHistory<Location> history = new ReviewHistory<>(100, Location::samePlace, Location::close);
        Location parked;
        ReviewSession(GitStageDiffRequestProcessor stage, VirtualFile preview, EditorWindow window) {
            this.stage = stage; this.preview = preview; this.window = new WeakReference<>(window);
        }
        void close() { history.clear(); if (parked != null) { parked.close(); parked = null; } }
    }
    private static final class Location implements AutoCloseable {
        final VirtualFile file;
        NodeKind kind;
        final Side side;
        final NavigationPosition position;
        Location(VirtualFile file, NodeKind kind, Side side, NavigationPosition position) {
            this.file = file; this.kind = kind; this.side = side; this.position = position;
        }
        boolean samePlace(Location other) { return file.equals(other.file); }
        Location copy() { return new Location(file, kind, side, position.copy()); }
        @Override public void close() { position.close(); }
    }
    private static final class Pending {
        final ReviewSession session;
        final Wrapper wrapper;
        final Location from;
        final Location to;
        final boolean focus;
        final WeakReference<Component> focusOwner;
        final BiConsumer<Location, Location> commit;
        boolean passiveRestore;
        Pending(ReviewSession session, Wrapper wrapper, Location from, Location to, boolean focus, Component focusOwner, BiConsumer<Location, Location> commit) {
            this.session = session; this.wrapper = wrapper; this.from = from; this.to = to;
            this.focus = focus; this.focusOwner = new WeakReference<>(focusOwner); this.commit = commit;
        }
    }
}
