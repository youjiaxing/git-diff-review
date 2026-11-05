package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;

public final class OpenReviewSourceAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public void update(AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(event.getProject() != null
                && StageNavigationService.processor(event.getDataContext()) != null);
    }
    @Override public void actionPerformed(AnActionEvent event) {
        if (event.getProject() != null) event.getProject().getService(StageNavigationService.class).openCurrentSource(event.getDataContext());
    }
}
