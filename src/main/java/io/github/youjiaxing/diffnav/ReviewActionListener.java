package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionResult;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import java.util.function.BooleanSupplier;

/** Also handle the IDE's existing Back/Forward toolbar buttons when available. */
public final class ReviewActionListener implements AnActionListener {
    private static final Key<Boolean> OWN_SKIP = Key.create("diffnav.own.skip");
    private static final Key<Boolean> PREVIOUS_SKIP = Key.create("diffnav.previous.skip");
    private static final Key<Runnable> DEFERRED = Key.create("diffnav.deferred.action");
    private static final Key<Boolean> NATIVE_FALLBACK = Key.create("diffnav.native.fallback");
    @Override public void beforeActionPerformed(AnAction action, AnActionEvent event) {
        if (Boolean.TRUE.equals(event.getPresentation().getClientProperty(NATIVE_FALLBACK))) return;
        var project = event.getProject();
        if (project == null) return;
        String id = ActionManager.getInstance().getId(action);
        int direction = "Back".equals(id) ? -1 : "Forward".equals(id) ? 1 : 0;
        var service = project.getService(StageNavigationService.class);
        var context = event.getDataContext();
        var compare = project.getService(SourceCompareService.class);
        compare.cancel();
        Runnable explicit = "Git.Stage.Compare.Local.Staged".equals(id)
                ? compare.prepare(context, () -> nativeFallback(action, event)) : null;
        BooleanSupplier navigate = direction != 0 && service.prepareTravel(context, direction) ? () -> service.travel(context, direction)
                : "EditSource".equals(id) && UsageNavigation.canNavigate(project, context) ? () -> UsageNavigation.navigate(project, context) : null;
        if (explicit != null || navigate != null) {
            event.getPresentation().putClientProperty(PREVIOUS_SKIP, event.getPresentation().getClientProperty(ActionUtil.SKIP_ACTION_EXECUTION));
            event.getPresentation().putClientProperty(OWN_SKIP, true);
            event.getPresentation().putClientProperty(ActionUtil.SKIP_ACTION_EXECUTION, true);
            // Action callbacks prohibit nested AWT dispatch; opening an editor can pump events.
            event.getPresentation().putClientProperty(DEFERRED, explicit != null ? explicit : () -> {
                if (project.isDisposed() || navigate.getAsBoolean()) return;
                nativeFallback(action, event);
            });
        }
    }
    @Override public void afterActionPerformed(AnAction action, AnActionEvent event, AnActionResult result) {
        if (Boolean.TRUE.equals(event.getPresentation().getClientProperty(OWN_SKIP))) {
            Runnable navigate = event.getPresentation().getClientProperty(DEFERRED);
            event.getPresentation().putClientProperty(ActionUtil.SKIP_ACTION_EXECUTION, event.getPresentation().getClientProperty(PREVIOUS_SKIP));
            event.getPresentation().putClientProperty(OWN_SKIP, null);
            event.getPresentation().putClientProperty(PREVIOUS_SKIP, null);
            event.getPresentation().putClientProperty(DEFERRED, null);
            if (navigate != null) ApplicationManager.getApplication().invokeLater(navigate, ModalityState.nonModal());
        }
    }
    private static void nativeFallback(AnAction action, AnActionEvent event) {
        var fallback = AnActionEvent.createEvent(action, event.getDataContext(), event.getPresentation().clone(),
                event.getPlace(), event.getUiKind(), event.getInputEvent());
        fallback.getPresentation().putClientProperty(NATIVE_FALLBACK, true);
        ActionUtil.invokeAction(action, fallback, null);
    }
}
