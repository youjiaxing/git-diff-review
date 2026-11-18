package io.github.youjiaxing.diffnav;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ui.popup.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** One native popup, positioned inside its owning code viewport throughout a valid selection. */
final class SelectionToolbar implements AutoCloseable {
    private final Editor editor;
    private final List<JButton> buttons;
    private final BooleanSupplier valid;
    private final Runnable dismiss;
    private final JPanel panel = new JPanel();
    private final JScrollPane surface = new JScrollPane(panel);
    private final Disposable lifetime = Disposer.newDisposable("Selection toolbar position");
    private final JBPopup popup;
    private boolean positioning;

    SelectionToolbar(Editor editor, List<JButton> buttons, BooleanSupplier valid, Runnable dismiss, Disposable owner) {
        this.editor = editor; this.buttons = buttons; this.valid = valid; this.dismiss = dismiss;
        Disposer.register(owner, lifetime);
        var background = JBColor.namedColor("Popup.background", UIUtil.getPanelBackground());
        panel.setBackground(background);
        panel.setBorder(JBUI.Borders.empty(6));
        buttons.forEach(panel::add);
        surface.setBorder(JBUI.Borders.empty());
        surface.getViewport().setBackground(background);
        surface.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        surface.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        surface.setMinimumSize(new Dimension(1, 1));
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(surface, null)
                .setRequestFocus(false).setFocusable(false).setModalContext(false)
                .setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true)
                .setShowBorder(true).setShowShadow(true).setBorderColor(JBColor.namedColor("Popup.borderColor", JBColor.GRAY))
                .setLocateByContent(false).setLocateWithinScreenBounds(false).setMinSize(new Dimension(1, 1)).createPopup();
        popup.addListener(new JBPopupListener() {
            @Override public void onClosed(LightweightWindowEvent event) { close(); dismiss.run(); }
        });
        editor.getScrollingModel().addVisibleAreaListener(event -> repositionSafely(), lifetime);
        var resize = new ComponentAdapter() { @Override public void componentResized(ComponentEvent event) { repositionSafely(); } };
        var ancestors = new HierarchyBoundsAdapter() {
            @Override public void ancestorMoved(HierarchyEvent event) { repositionSafely(); }
            @Override public void ancestorResized(HierarchyEvent event) { repositionSafely(); }
        };
        editor.getComponent().addComponentListener(resize);
        editor.getComponent().addHierarchyBoundsListener(ancestors);
        Disposer.register(lifetime, () -> {
            editor.getComponent().removeComponentListener(resize);
            editor.getComponent().removeHierarchyBoundsListener(ancestors);
        });
    }
    JBPopup popup() { return popup; }
    void show() {
        var bounds = visibleBounds();
        if (bounds.isEmpty() || !valid.getAsBoolean()) { dismiss.run(); return; }
        layout(bounds.width - JBUI.scale(4), bounds.height - JBUI.scale(4));
        var initial = locate(bounds, surface.getPreferredSize());
        popup.showInScreenCoordinates(editor.getContentComponent(), initial);
        reposition();
    }
    private void repositionSafely() {
        if (!Disposer.isDisposed(lifetime)) ReadAction.run(this::reposition);
    }
    private Rectangle visibleBounds() {
        var area = editor.getScrollingModel().getVisibleArea().intersection(editor.getContentComponent().getVisibleRect());
        if (!editor.getContentComponent().isShowing() || area.isEmpty()) return new Rectangle();
        var origin = new Point(area.x, area.y);
        SwingUtilities.convertPointToScreen(origin, editor.getContentComponent());
        var bounds = new Rectangle(origin.x, origin.y, area.width, area.height);
        var configuration = editor.getContentComponent().getGraphicsConfiguration();
        if (configuration != null) {
            var screen = new Rectangle(configuration.getBounds());
            var insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            screen.x += insets.left; screen.y += insets.top;
            screen.width -= insets.left + insets.right; screen.height -= insets.top + insets.bottom;
            bounds = bounds.intersection(screen);
        }
        int gap = Math.min(JBUI.scale(6), Math.max(0, Math.min(bounds.width, bounds.height) / 8));
        bounds.grow(-gap, -gap);
        return bounds;
    }
    private void layout(int width, int height) {
        int gap = JBUI.scale(6);
        for (var button : buttons) button.setText(button.getActionCommand());
        panel.setLayout(new FlowLayout(FlowLayout.LEFT, gap, 0));
        if (panel.getPreferredSize().width > width) {
            panel.setLayout(new GridLayout(0, 1, 0, gap));
            for (var button : buttons) if (button.getPreferredSize().width + gap * 2 > width) {
                button.setToolTipText(button.getActionCommand());
                button.setText("<html><center>" + button.getActionCommand().replace(" ", "<br>") + "</center></html>");
            }
        }
        var preferred = panel.getPreferredSize();
        int scrollbar = preferred.height > height ? surface.getVerticalScrollBar().getPreferredSize().width : 0;
        surface.setPreferredSize(new Dimension(Math.max(1, Math.min(width, preferred.width + scrollbar)), Math.max(1, Math.min(height, preferred.height))));
        panel.revalidate();
    }
    private Point locate(Rectangle bounds, Dimension size) {
        var caret = editor.visualPositionToXY(editor.getCaretModel().getVisualPosition());
        SwingUtilities.convertPointToScreen(caret, editor.getContentComponent());
        int gap = JBUI.scale(8);
        int x = caret.x + gap;
        int y = caret.y + editor.getLineHeight() + gap;
        if (x + size.width > bounds.x + bounds.width) x = caret.x - size.width - gap;
        if (y + size.height > bounds.y + bounds.height) y = caret.y - size.height - gap;
        return new Point(Math.max(bounds.x, Math.min(x, bounds.x + bounds.width - size.width)),
                Math.max(bounds.y, Math.min(y, bounds.y + bounds.height - size.height)));
    }
    private void reposition() {
        if (positioning || Disposer.isDisposed(lifetime) || popup.isDisposed()) return;
        if (!valid.getAsBoolean()) { dismiss.run(); return; }
        var bounds = visibleBounds();
        if (bounds.isEmpty() || !popup.isVisible()) return;
        var window = SwingUtilities.getWindowAncestor(popup.getContent());
        if (window == null) return;
        positioning = true;
        try {
            int extraWidth = Math.max(0, window.getWidth() - surface.getWidth());
            int extraHeight = Math.max(0, window.getHeight() - surface.getHeight());
            layout(Math.max(1, bounds.width - extraWidth), Math.max(1, bounds.height - extraHeight));
            popup.pack(true, true);
            var target = locate(bounds, window.getSize());
            var content = popup.getLocationOnScreen();
            var outer = window.getLocationOnScreen();
            popup.setLocation(new Point(target.x + content.x - outer.x, target.y + content.y - outer.y));
        } finally { positioning = false; }
    }
    @Override public void close() { if (!Disposer.isDisposed(lifetime)) Disposer.dispose(lifetime); }
}
