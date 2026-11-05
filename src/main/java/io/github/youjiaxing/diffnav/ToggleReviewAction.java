package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;

public final class ToggleReviewAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public void update(AnActionEvent event) {
        var project = event.getProject();
        var service = project == null ? null : project.getService(StageNavigationService.class);
        event.getPresentation().setEnabled(service != null && service.canToggleReview(event.getDataContext()));
        event.getPresentation().setText(service != null && service.isReviewing(event.getDataContext()) ? "Pause Diff Review" : "Start Diff Review");
    }
    @Override public void actionPerformed(AnActionEvent event) {
        if (event.getProject() != null) event.getProject().getService(StageNavigationService.class).toggleReview(event.getDataContext());
    }
}
