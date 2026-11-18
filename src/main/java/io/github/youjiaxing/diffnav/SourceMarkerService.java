package io.github.youjiaxing.diffnav;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diff.LineStatusMarkerColorScheme;
import com.intellij.openapi.diff.LineStatusMarkerDrawUtil;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.ex.*;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.impl.event.MarkupModelListener;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.ex.*;
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager;
import com.intellij.util.ui.JBUI;
import git4idea.index.GitStageLineStatusTracker;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

/** Build-262 adapter: replace only native marker painting, preserving the native highlighter and actions. */
@Service(Service.Level.PROJECT)
public final class SourceMarkerService implements Disposable {
    private static final String NATIVE_RENDERER = "com.intellij.openapi.vcs.ex.LineStatusMarkerRendererWithPopup$LineStatusGutterMarkerRendererWithPopup";
    private final Project project;
    private final Map<GitStageLineStatusTracker, Watch> watches = new IdentityHashMap<>();
    private boolean disposed, queued;
    public SourceMarkerService(Project project) {
        this.project = project;
        LineStatusTrackerManager.getInstanceImpl(project).addTrackerListener(new LineStatusTrackerManager.Listener() {
            @Override public void onTrackerAdded(LineStatusTracker<?> tracker) { schedule(); }
            @Override public void onTrackerRemoved(LineStatusTracker<?> tracker) { schedule(); }
            @Override public void onTrackerBecomeValid(LineStatusTracker<?> tracker) { schedule(); }
        }, this);
        EditorFactory.getInstance().addEditorFactoryListener(new com.intellij.openapi.editor.event.EditorFactoryListener() {
            @Override public void editorCreated(com.intellij.openapi.editor.event.EditorFactoryEvent event) { schedule(); }
            @Override public void editorReleased(com.intellij.openapi.editor.event.EditorFactoryEvent event) { schedule(); }
        }, this);
        refresh();
    }
    private boolean enabled() {
        if (disposed || project.isDisposed()) return false;
        var options = NavigationSettings.get(project).getState();
        return !disposed && !project.isDisposed() && options.enabled && options.sourceStaging;
    }
    private void schedule() {
        if (queued || disposed || project.isDisposed()) return;
        queued = true;
        ApplicationManager.getApplication().invokeLater(() -> { queued = false; if (!disposed && !project.isDisposed()) refresh(); });
    }
    public void refresh() {
        if (disposed || project.isDisposed()) return;
        var current = new HashSet<GitStageLineStatusTracker>();
        if (enabled()) for (var tracker : LineStatusTrackerManager.getInstanceImpl(project).getTrackers())
            if (tracker instanceof GitStageLineStatusTracker git && !git.isReleased()) current.add(git);
        for (var tracker : List.copyOf(watches.keySet())) if (!current.contains(tracker)) Disposer.dispose(watches.remove(tracker));
        for (var tracker : current) {
            var watch = watches.get(tracker);
            if (watch == null) {
                watch = new Watch(tracker); watches.put(tracker, watch); Disposer.register(this, watch);
            }
            watch.update();
        }
    }
    boolean active(Document document) {
        return watches.values().stream().anyMatch(w -> w.tracker.getDocument() == document && w.highlighter != null
                && w.highlighter.isValid() && w.highlighter.getLineMarkerRenderer() == w.proxy && w.current());
    }
    private final class Watch implements Disposable {
        final GitStageLineStatusTracker tracker;
        final Map<Editor, List<Inlay<?>>> labels = new IdentityHashMap<>();
        final LineStatusTrackerListener listener = new LineStatusTrackerListener() {
            @Override public void onBecomingValid() { schedule(); }
            @Override public void onRangesChanged() { schedule(); }
        };
        RangeHighlighter highlighter;
        Proxy proxy;
        boolean closed;
        long generation;
        Snapshot projection;
        Snapshot fallback;
        List<SourceMarkerRanges.Mark> marks;
        private record Snapshot(Document headDocument, Document indexDocument, Document localDocument,
                                long headStamp, long indexStamp, long localStamp, CharSequence indexContent, String head, String index, String local) {}
        Snapshot snapshot() {
            return tracker.readLock(() -> {
                var head = tracker.getVcsDocument(); var index = tracker.getStagedDocument(); var local = tracker.getDocument();
                return new Snapshot(head, index, local, head.getModificationStamp(), index.getModificationStamp(), local.getModificationStamp(),
                        index.getImmutableCharSequence(), head.getText(), index.getText(), local.getText());
            });
        }
        boolean current() {
            return projection != null && marks != null && !closed && !tracker.isReleased() && tracker.isValid()
                    && highlighter != null && highlighter.isValid() && highlighter.getLineMarkerRenderer() == proxy
                    && sameVersion(projection);
        }
        boolean sameVersion(Snapshot value) {
            // INDEX refresh may replace its Document or update its contents while retaining a stamp.
            return value.headDocument == tracker.getVcsDocument() && value.indexDocument == tracker.getStagedDocument()
                    && value.localDocument == tracker.getDocument() && value.headStamp == tracker.getVcsDocument().getModificationStamp()
                    && value.indexStamp == tracker.getStagedDocument().getModificationStamp()
                    && value.localStamp == tracker.getDocument().getModificationStamp()
                    && com.intellij.openapi.util.text.StringUtil.equals(value.indexContent, tracker.getStagedDocument().getImmutableCharSequence());
        }
        Watch(GitStageLineStatusTracker tracker) {
            this.tracker = tracker; tracker.addListener(listener);
            var markup = DocumentMarkupModel.forDocument(tracker.getDocument(), project, true);
            if (markup instanceof MarkupModelEx model) model.addMarkupModelListener(this, new MarkupModelListener() {
                @Override public void afterAdded(RangeHighlighterEx h) { if (Boolean.TRUE.equals(h.getUserData(LineStatusMarkerRenderer.Companion.getMAIN_KEY()))) schedule(); }
                @Override public void afterRemoved(RangeHighlighterEx h) { if (h == highlighter) schedule(); }
                @Override public void attributesChanged(RangeHighlighterEx h, boolean renderersChanged, boolean fontChanged) {
                    if (h == highlighter && h.getLineMarkerRenderer() != proxy) schedule();
                }
            });
        }
        void update() {
            if (closed || !enabled() || tracker.isReleased() || !tracker.isValid() || !tracker.getMode().isVisible()) { generation++; projection = null; marks = null; clearLabels(); restore(); return; }
            if (fallback != null && sameVersion(fallback)) { restore(); return; }
            var candidates = new ArrayList<RangeHighlighter>();
            for (var h : DocumentMarkupModel.forDocument(tracker.getDocument(), project, false).getAllHighlighters()) {
                if (!h.isValid() || !Boolean.TRUE.equals(h.getUserData(LineStatusMarkerRenderer.Companion.getMAIN_KEY()))) continue;
                var renderer = h.getLineMarkerRenderer();
                if (renderer == proxy && proxy != null || renderer != null && renderer.getClass().getName().equals(NATIVE_RENDERER)) candidates.add(h);
            }
            if (candidates.size() != 1) { restore(); return; }
            var candidate = candidates.getFirst();
            if (candidate != highlighter || candidate.getLineMarkerRenderer() != proxy) {
                restore();
                var renderer = candidate.getLineMarkerRenderer();
                if (!(renderer instanceof ActiveGutterRenderer active) || !(renderer instanceof LineMarkerRendererEx ex)) return;
                proxy = new Proxy(this, active, ex); highlighter = candidate;
                candidate.setLineMarkerRenderer(proxy);
            }
            if (current()) { renderLabels(); return; }
            var captured = snapshot();
            if (projection != null && projection.equals(captured)) return;
            projection = captured; marks = null; clearLabels();
            long ticket = ++generation;
            if (captured.head.length() > 8 * 1024 * 1024 || captured.index.length() > 8 * 1024 * 1024 || captured.local.length() > 8 * 1024 * 1024) {
                fallback = captured;
                restore(); return;
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    var fine = SourceMarkerRanges.build(SourceMarkerRanges.lineCount(captured.index), SourceMarkerRanges.lineCount(captured.local),
                            changes(captured.head, captured.index), changes(captured.index, captured.local));
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (closed || ticket != generation || !enabled() || tracker.isReleased()) return;
                        fallback = null;
                        marks = fine;
                        if (!current()) { marks = null; schedule(); return; }
                        renderLabels();
                        project.getService(SourceStagingService.class).refresh();
                    });
                } catch (com.intellij.openapi.progress.ProcessCanceledException ignored) {
                    ApplicationManager.getApplication().invokeLater(() -> { if (!closed && ticket == generation) { projection = null; schedule(); } });
                } catch (Exception failure) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!closed && ticket == generation) {
                            fallback = captured;
                            marks = null; clearLabels(); restore();
                            com.intellij.openapi.diagnostic.Logger.getInstance(SourceMarkerService.class).warn("Source marker projection failed", failure);
                        }
                    });
                }
            });
        }
        void renderLabels() {
            clearLabels();
            if (!current()) return;
            for (var editor : EditorFactory.getInstance().getEditors(tracker.getDocument(), project)) {
                if (editor.isDisposed() || editor.getEditorKind() != EditorKind.MAIN_EDITOR || editor.isViewer()) continue;
                var inlays = new ArrayList<Inlay<?>>();
                var byLine = new TreeMap<Integer, List<String>>();
                for (var mark : marks) {
                    int line = Math.max(0, Math.min(editor.getDocument().getLineCount() - 1, mark.getLine1()));
                    String text = mark.hasLines() ? status(mark.staged, mark.unstaged)
                            : "行间删除：" + status(mark.staged, mark.unstaged);
                    byLine.computeIfAbsent(line, ignored -> new ArrayList<>()).add(text);
                }
                for (var entry : byLine.entrySet()) {
                    if (inlays.size() >= 500) break;
                    int offset = editor.getDocument().getLineCount() == 0 ? 0 : editor.getDocument().getLineEndOffset(entry.getKey());
                    var inlay = editor.getInlayModel().addAfterLineEndElement(offset, false,
                            new Label(String.join("；", entry.getValue()), Range.MODIFIED));
                    if (inlay != null) inlays.add(inlay);
                }
                labels.put(editor, inlays); editor.getComponent().repaint();
            }
        }
        void restore() {
            if (highlighter != null && highlighter.isValid() && highlighter.getLineMarkerRenderer() == proxy)
                highlighter.setLineMarkerRenderer(proxy.original);
            highlighter = null; proxy = null;
            generation++; projection = null; marks = null; clearLabels();
        }
        void clearLabels() {
            labels.values().forEach(list -> list.forEach(Disposer::dispose)); labels.clear();
        }
        @Override public void dispose() {
            closed = true; generation++; marks = null; tracker.removeListener(listener); clearLabels(); restore();
        }
    }
    private static List<SelectedLines.Change> changes(String before, String after) {
        return com.intellij.diff.comparison.ComparisonManager.getInstance().compareLines(before, after,
                com.intellij.diff.comparison.ComparisonPolicy.DEFAULT, new com.intellij.openapi.progress.EmptyProgressIndicator()).stream()
                .map(f -> new SelectedLines.Change(f.getStartLine1(), f.getEndLine1(), f.getStartLine2(), f.getEndLine2())).toList();
    }
    private static String status(boolean staged, boolean unstaged) {
        return staged ? unstaged ? "已暂存，另有修改" : "已暂存" : "未暂存";
    }
    private record Flags(boolean staged, boolean unstaged) {}
    private static final VisibleRangeMerger.FlagsProvider<Flags> FLAGS = new VisibleRangeMerger.FlagsProvider<>() {
        @Override public Flags getFlags(Range range) {
            var r = (SourceMarkerRanges.Mark) range; return new Flags(r.staged, r.unstaged);
        }
        @Override public Flags mergeFlags(Flags a, Flags b) { return new Flags(a.staged || b.staged, a.unstaged || b.unstaged); }
    };
    private final class Proxy implements ActiveGutterRenderer, LineMarkerRendererEx {
        final Watch watch;
        final GitStageLineStatusTracker tracker;
        final ActiveGutterRenderer original;
        final LineMarkerRendererEx originalEx;
        String tooltip = "实心＝未暂存；空心＝已暂存；半填充＝已暂存，另有修改";
        Proxy(Watch watch, ActiveGutterRenderer original, LineMarkerRendererEx originalEx) {
            this.watch = watch; this.tracker = watch.tracker; this.original = original; this.originalEx = originalEx;
        }
        @Override public Position getPosition() { return originalEx.getPosition(); }
        @Override public boolean isSticky() { return originalEx.isSticky(); }
        @Override public boolean canDoAction(Editor editor, MouseEvent event) {
            if (watch.current()) {
                // Both tooltip and local actions describe the clicked source row.
                int line = editor.xyToLogicalPosition(new Point(0, event.getY())).line;
                var descriptions = new LinkedHashSet<String>();
                for (var mark : watch.marks) {
                    if (mark.hasLines() && mark.getLine1() <= line && line < mark.getLine2()) descriptions.add(status(mark.staged, mark.unstaged));
                    if (!mark.hasLines() && Math.abs(event.getY() - editor.logicalPositionToXY(new LogicalPosition(mark.getLine1(), 0)).y) <= editor.getLineHeight() / 4)
                        descriptions.add("行间删除：" + status(mark.staged, mark.unstaged));
                }
                tooltip = descriptions.isEmpty() ? "点击查看此处修改的代码对比和操作"
                        : String.join("；", descriptions) + "；点击查看此处修改的代码对比和操作";
            }
            return original.canDoAction(editor, event);
        }
        @Override public boolean canDoAction(MouseEvent event) { return original.canDoAction(event); }
        @Override public void doAction(Editor editor, MouseEvent event) {
            if (!enabled() || editor.getEditorKind() != EditorKind.MAIN_EDITOR || editor.isViewer()) { original.doAction(editor, event); return; }
            if (watch.current()) {
                int line = editor.xyToLogicalPosition(new Point(0, event.getY())).line;
                SourceMarkerRanges.Mark target = null;
                for (var mark : watch.marks) if (!mark.hasLines()
                        && Math.abs(event.getY() - editor.logicalPositionToXY(new LogicalPosition(mark.getLine1(), 0)).y) <= editor.getLineHeight() / 4) {
                    target = mark; line = mark.getLine1(); break;
                }
                if (target == null) for (var mark : watch.marks) if (mark.hasLines() && mark.getLine1() <= line && line < mark.getLine2()) { target = mark; break; }
                if (target != null && project.getService(SourceStagingService.class).showMarker(editor, line, !target.hasLines(),
                        status(target.staged, target.unstaged), event, () -> original.doAction(editor, event))) {
                    // Native gutter actions consume release before the editor synthesizes a
                    // click for breakpoint listeners. Preserve that contract when taking over.
                    event.consume();
                    return;
                }
            }
            original.doAction(editor, event);
        }
        @Override public Rectangle calcBounds(Editor editor, int line, Rectangle rect) { return original.calcBounds(editor, line, rect); }
        @Override public String getTooltipText() { return tooltip; }
        @Override public String getAccessibleName() { return original.getAccessibleName(); }
        @Override public String getAccessibleTooltipText() { return tooltip; }
        @Override public void paint(Editor editor, Graphics graphics, Rectangle rect) {
            if (!enabled() || editor.getEditorKind() != EditorKind.MAIN_EDITOR || editor.isViewer()) { original.paint(editor, graphics, rect); return; }
            if (tracker.isReleased() || !tracker.isValid() || !tracker.getMode().isVisible()) return;
            if (!watch.current()) return;
            var g = (Graphics2D) graphics.create();
            try {
                // Keep deletion anchors separate so their flags never spread into neighboring rows.
                var clip = graphics.getClipBounds() == null ? rect : graphics.getClipBounds();
                for (boolean points : new boolean[]{false, true})
                    for (var block : VisibleRangeMerger.merge(editor, watch.marks.stream().filter(r -> r.hasLines() != points).toList(), FLAGS, clip))
                        paintBlock(g, editor, block.changes);
            } finally { g.dispose(); }
        }
    }
    /** Build-262 native stage rendering, applied to granular ranges instead of combined block flags. */
    private static void paintBlock(Graphics2D g, Editor editor, List<ChangedLines<Flags>> changes) {
        if (changes.isEmpty()) return;
        var scheme = LineStatusMarkerColorScheme.DEFAULT;
        Color border = scheme.getBorderColor(editor);
        var area = LineStatusMarkerDrawUtil.getGutterArea(editor);
        int x1 = area.first, x2 = area.second;
        boolean modern = com.intellij.ui.ExperimentalUI.isNewUI();
        int middle = modern ? (x1 + x2) / 2 : (x1 + x2 + 3) / 2;
        int hovered = ((EditorEx) editor).getGutterComponentEx().getHoveredFreeMarkersLine();
        for (var change : changes) {
            if (change.y1 == change.y2 || !change.flags.unstaged) continue;
            boolean expand = modern && LineStatusMarkerDrawUtil.isRangeHovered(editor, hovered, x1, change.y1, change.y2);
            int left = expand ? x1 - 1 : x1, right = expand ? x2 + 1 : x2;
            Color color = scheme.getColor(editor, change.type);
            if (!change.flags.staged) LineStatusMarkerDrawUtil.paintRect(g, color, null, left, change.y1, right, change.y2);
            else if (!modern) LineStatusMarkerDrawUtil.paintRect(g, color, null, left, change.y1, middle, change.y2);
            else paintHalfRoundedRect(g, color, left, change.y1 + 1, middle, change.y2 - 1, right - left);
        }
        if (border == null) {
            for (var change : changes) if (change.y1 != change.y2 && change.flags.staged) {
                boolean expand = modern && LineStatusMarkerDrawUtil.isRangeHovered(editor, hovered, x1, change.y1, change.y2);
                LineStatusMarkerDrawUtil.paintRect(g, null, scheme.getIgnoredBorderColor(editor, change.type),
                        expand ? x1 - 1 : x1, change.y1, expand ? x2 + 1 : x2, change.y2);
            }
        } else {
            int y1 = changes.getFirst().y1, y2 = changes.getLast().y2;
            if (y1 != y2) {
                boolean expand = modern && LineStatusMarkerDrawUtil.isRangeHovered(editor, hovered, x1, y1, y2);
                LineStatusMarkerDrawUtil.paintRect(g, null, border, expand ? x1 - 1 : x1, y1, expand ? x2 + 1 : x2, y2);
            }
        }
        for (var change : changes) if (change.y1 == change.y2) {
            Color color = scheme.getColor(editor, change.type);
            Color outline = border != null ? border : scheme.getIgnoredBorderColor(editor, change.type);
            if (change.flags.staged && change.flags.unstaged) paintHalfTriangle(g, editor, color, outline, x1, x2, change.y1);
            else LineStatusMarkerDrawUtil.paintTriangle(g, editor, change.flags.unstaged ? color : null,
                    change.flags.staged ? outline : border, x1, x2, change.y1);
        }
    }
    private static void paintHalfRoundedRect(Graphics2D g, Color color, int x1, int y1, int x2, int y2, int radius) {
        if (color == null) return;
        var path = new java.awt.geom.Path2D.Double();
        path.moveTo(x1, y1 + radius); path.quadTo(x1, y1, x1 + radius, y1);
        path.lineTo(x2, y1); path.lineTo(x2, y2); path.lineTo(x1 + radius, y2);
        path.quadTo(x1, y2, x1, y2 - radius); path.closePath();
        g.setColor(color);
        com.intellij.ui.paint.PaintUtil.paintWithAA(g, RenderingHints.VALUE_ANTIALIAS_DEFAULT, () -> g.fill(path));
    }
    private static void paintHalfTriangle(Graphics2D g, Editor editor, Color color, Color border, int x1, int x2, int y) {
        float scale = editor instanceof com.intellij.openapi.editor.impl.EditorImpl impl ? impl.getScale() : 1f;
        int radius = (int) com.intellij.ui.scale.JBUIScale.scale(5 * scale);
        y = Math.max(y, radius);
        int[] xs = {x1, x1, x2};
        if (color != null) { g.setColor(color); g.fillPolygon(xs, new int[]{y - radius, y, y}, 3); }
        if (border != null) {
            var stroke = g.getStroke();
            try {
                g.setColor(border); g.setStroke(new BasicStroke(JBUI.scale(1)));
                g.drawPolygon(xs, new int[]{y - radius, y + radius, y}, 3);
            } finally { g.setStroke(stroke); }
        }
    }
    private record Label(String text, byte type) implements EditorCustomElementRenderer {
        private Font font(Editor editor) {
            float size = Math.max(11f, editor.getColorsScheme().getEditorFontSize() - 1f);
            var font = com.intellij.util.ui.UIUtil.getLabelFont().deriveFont(Font.PLAIN, size);
            return font.canDisplayUpTo(text) < 0 ? font : new Font(Font.DIALOG, Font.PLAIN, Math.round(size));
        }
        @Override public int calcWidthInPixels(Inlay inlay) { return inlay.getEditor().getContentComponent().getFontMetrics(font(inlay.getEditor())).stringWidth(text) + JBUI.scale(20); }
        @Override public void paint(Inlay inlay, Graphics graphics, Rectangle target, TextAttributes attributes) {
            var editor = inlay.getEditor(); var g = (Graphics2D) graphics.create();
            try {
                g.setFont(font(editor));
                Color color = LineStatusMarkerColorScheme.DEFAULT.getColor(editor, type);
                g.setColor(color == null ? editor.getColorsScheme().getDefaultForeground() : color);
                int x = target.x + JBUI.scale(8), y = target.y + Math.max(g.getFontMetrics().getAscent(), (target.height + g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2);
                g.drawString(text, x, y);
            } finally { g.dispose(); }
        }
    }
    @Override public void dispose() { disposed = true; }
}
