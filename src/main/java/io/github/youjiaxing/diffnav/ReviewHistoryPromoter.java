package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.ActionPromoter;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import java.util.List;

public final class ReviewHistoryPromoter implements ActionPromoter {
    @Override public List<AnAction> promote(List<? extends AnAction> actions, DataContext context) {
        var project = CommonDataKeys.PROJECT.getData(context);
        if (project == null) return List.of();
        var service = project.getService(StageNavigationService.class);
        return actions.stream().filter(action ->
                (action instanceof ReviewBackAction && service.canTravel(context, -1))
                || (action instanceof ReviewForwardAction && service.canTravel(context, 1)))
                .map(action -> (AnAction) action).toList();
    }
}
