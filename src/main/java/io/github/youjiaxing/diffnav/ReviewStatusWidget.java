package io.github.youjiaxing.diffnav;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;

public final class ReviewStatusWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {
    static final String ID = "GitDiffReview";
    private final Project project;
    private StatusBar status;
    ReviewStatusWidget(Project project) { this.project = project; }
    @Override public String ID() { return ID; }
    @Override public WidgetPresentation getPresentation() { return this; }
    @Override public void install(StatusBar status) { this.status = status; }
    @Override public String getText() { return project.getService(StageNavigationService.class).statusText(); }
    @Override public float getAlignment() { return 0; }
    @Override public String getTooltipText() { return project.getService(StageNavigationService.class).getLastReason(); }
    @Override public com.intellij.util.Consumer<java.awt.event.MouseEvent> getClickConsumer() {
        return event -> {
            if (status == null || project.isDisposed()) return;
            var group = new DefaultActionGroup();
            group.add(ActionManager.getInstance().getAction("DiffNavigation.ToggleReview"));
            group.add(ActionManager.getInstance().getAction("DiffNavigation.Diagnostics"));
            group.add(ActionManager.getInstance().getAction("DiffNavigation.Settings"));
            JBPopupFactory.getInstance().createActionGroupPopup("Diff Review", group,
                    DataManager.getInstance().getDataContext(status.getComponent()),
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true).showInFocusCenter();
        };
    }
    @Override public void dispose() { status = null; }
}
