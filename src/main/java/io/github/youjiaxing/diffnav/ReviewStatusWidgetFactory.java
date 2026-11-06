package io.github.youjiaxing.diffnav;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;

public final class ReviewStatusWidgetFactory implements StatusBarWidgetFactory {
    @Override public String getId() { return ReviewStatusWidget.ID; }
    @Override public String getDisplayName() { return "Git Diff Review"; }
    @Override public StatusBarWidget createWidget(Project project) { return new ReviewStatusWidget(project); }
    @Override public boolean canBeEnabledOn(StatusBar statusBar) { return true; }
}
