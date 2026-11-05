package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;

public final class ToggleNavigationAction extends ToggleAction implements DumbAware {
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public boolean isSelected(AnActionEvent event) {
        return event.getProject() != null && event.getProject().getService(StageNavigationService.class).isEnabled();
    }
    @Override public void setSelected(AnActionEvent event, boolean state) {
        if (event.getProject() != null) event.getProject().getService(StageNavigationService.class).setEnabled(state);
    }
    @Override public void update(AnActionEvent event) {
        super.update(event);
        event.getPresentation().setEnabledAndVisible(StageNavigationService.processor(event.getDataContext()) != null);
    }
}
