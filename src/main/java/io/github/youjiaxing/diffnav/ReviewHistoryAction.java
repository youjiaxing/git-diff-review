package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;

abstract class ReviewHistoryAction extends DumbAwareAction {
    private final int direction;
    ReviewHistoryAction(int direction) { this.direction = direction; }
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public void update(AnActionEvent event) {
        var project = event.getProject();
        event.getPresentation().setEnabledAndVisible(project != null
                && project.getService(StageNavigationService.class).canTravel(event.getDataContext(), direction));
    }
    @Override public void actionPerformed(AnActionEvent event) {
        var project = event.getProject();
        if (project != null) project.getService(StageNavigationService.class).travel(event.getDataContext(), direction);
    }
}
