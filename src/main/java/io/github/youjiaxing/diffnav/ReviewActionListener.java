package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionResult;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.util.Key;

/** Also handle the IDE's existing Back/Forward toolbar buttons when available. */
public final class ReviewActionListener implements AnActionListener {
    private static final Key<Boolean> OWN_SKIP = Key.create("diffnav.own.skip");
    private static final Key<Boolean> PREVIOUS_SKIP = Key.create("diffnav.previous.skip");
    @Override public void beforeActionPerformed(AnAction action, AnActionEvent event) {
        var project = event.getProject();
        if (project == null) return;
        String id = ActionManager.getInstance().getId(action);
        int direction = "Back".equals(id) ? -1 : "Forward".equals(id) ? 1 : 0;
        boolean consumed = direction != 0 && project.getService(StageNavigationService.class).travel(event.getDataContext(), direction);
        if ("EditSource".equals(id)) consumed = UsageNavigation.navigate(project, event.getDataContext());
        if (consumed) {
            event.getPresentation().putClientProperty(PREVIOUS_SKIP, event.getPresentation().getClientProperty(ActionUtil.SKIP_ACTION_EXECUTION));
            event.getPresentation().putClientProperty(OWN_SKIP, true);
            event.getPresentation().putClientProperty(ActionUtil.SKIP_ACTION_EXECUTION, true);
        }
    }
    @Override public void afterActionPerformed(AnAction action, AnActionEvent event, AnActionResult result) {
        if (Boolean.TRUE.equals(event.getPresentation().getClientProperty(OWN_SKIP))) {
            event.getPresentation().putClientProperty(ActionUtil.SKIP_ACTION_EXECUTION, event.getPresentation().getClientProperty(PREVIOUS_SKIP));
            event.getPresentation().putClientProperty(OWN_SKIP, null);
            event.getPresentation().putClientProperty(PREVIOUS_SKIP, null);
        }
    }
}
