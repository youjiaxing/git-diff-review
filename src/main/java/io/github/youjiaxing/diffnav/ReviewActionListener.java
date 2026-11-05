package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionResult;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.ex.AnActionListener;

/** Also handle the IDE's existing Back/Forward toolbar buttons when available. */
public final class ReviewActionListener implements AnActionListener {
    @Override public void beforeActionPerformed(AnAction action, AnActionEvent event) {
        var project = event.getProject();
        if (project == null) return;
        String id = ActionManager.getInstance().getId(action);
        int direction = "Back".equals(id) ? -1 : "Forward".equals(id) ? 1 : 0;
        if (direction != 0 && project.getService(StageNavigationService.class).travel(event.getDataContext(), direction)) {
            event.getPresentation().putClientProperty(ActionUtil.SKIP_ACTION_EXECUTION, true);
        }
    }
    @Override public void afterActionPerformed(AnAction action, AnActionEvent event, AnActionResult result) {
        event.getPresentation().putClientProperty(ActionUtil.SKIP_ACTION_EXECUTION, null);
    }
}
