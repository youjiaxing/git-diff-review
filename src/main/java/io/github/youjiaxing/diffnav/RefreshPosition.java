package io.github.youjiaxing.diffnav;

import com.intellij.openapi.editor.Editor;
import java.awt.Point;

/** Keeps visible content in place independently of a caret outside the operated selection. */
final class RefreshPosition implements AutoCloseable {
    private final NavigationPosition caret;
    private final NavigationPosition anchor;
    private final int relativeY;
    private final int horizontalOffset;
    private final javax.swing.JComponent frame;
    private final int anchorFrameY;
    private Editor appliedEditor;
    private java.awt.Rectangle appliedArea;
    private int appliedAnchorY;
    private long appliedStamp;

    RefreshPosition(Editor editor, javax.swing.JComponent frame) {
        this.frame = frame;
        caret = NavigationPosition.capture(editor.getDocument(), editor.getCaretModel().getOffset());
        var area = editor.getScrollingModel().getVisibleArea();
        int offset = editor.visualPositionToOffset(editor.xyToVisualPosition(new Point(area.x, area.y)));
        var selection = editor.getSelectionModel();
        if (selection.hasSelection()) {
            int start = selection.getSelectionStart();
            int y = editor.offsetToXY(start).y;
            // Prefer the visible part of the operation, including a selection extending off screen.
            if (y >= area.y && y < area.y + area.height) offset = start;
        }
        anchor = NavigationPosition.capture(editor.getDocument(), offset);
        relativeY = editor.offsetToXY(offset).y - area.y;
        anchorFrameY = javax.swing.SwingUtilities.convertPoint(editor.getContentComponent(), 0, editor.offsetToXY(offset).y, frame).y;
        horizontalOffset = area.x;
    }

    boolean restore(Editor editor) {
        var area = editor.getScrollingModel().getVisibleArea();
        if (!editor.getContentComponent().isShowing() || area.width <= 0 || area.height <= 0) return false;
        int anchorOffset = anchor.resolve(editor.getDocument());
        if (anchorOffset < 0) return true;
        int anchorY = editor.offsetToXY(anchorOffset).y;
        int targetRelativeY = frame.isShowing() ? anchorFrameY
                - javax.swing.SwingUtilities.convertPoint(editor.getContentComponent(), 0, area.y, frame).y : relativeY;
        int targetY = Math.max(0, anchorY - targetRelativeY);
        int lastLineBottom = editor.offsetToXY(editor.getDocument().getTextLength()).y + editor.getLineHeight();
        boolean reached = Math.abs(area.y - targetY) <= 1
                || area.y < targetY && area.y + area.height >= lastLineBottom;
        // Confirm in a later EDT turn: rediff completion can precede the final layout/scroll.
        if (reached && editor == appliedEditor && editor.getDocument().getModificationStamp() == appliedStamp
                && area.equals(appliedArea) && anchorY == appliedAnchorY) return true;
        var folding = editor.getFoldingModel();
        if (folding.getCollapsedRegionAtOffset(anchorOffset) != null) folding.runBatchFoldingOperation(() -> {
            var region = folding.getCollapsedRegionAtOffset(anchorOffset);
            while (region != null) {
                region.setExpanded(true);
                var next = folding.getCollapsedRegionAtOffset(anchorOffset);
                if (next == region) break;
                region = next;
            }
        });
        int caretOffset = caret.resolve(editor.getDocument());
        if (caretOffset >= 0) editor.getCaretModel().moveToOffset(caretOffset);
        var scrolling = editor.getScrollingModel();
        scrolling.disableAnimation();
        try { scrolling.scroll(horizontalOffset, Math.max(0, editor.offsetToXY(anchorOffset).y - targetRelativeY)); }
        finally { scrolling.enableAnimation(); }
        appliedEditor = editor;
        appliedStamp = editor.getDocument().getModificationStamp();
        appliedArea = new java.awt.Rectangle(scrolling.getVisibleArea());
        appliedAnchorY = editor.offsetToXY(anchorOffset).y;
        return false;
    }

    @Override public void close() { caret.close(); anchor.close(); }
}
