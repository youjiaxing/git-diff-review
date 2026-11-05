package io.github.youjiaxing.diffnav;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;

@Service(Service.Level.PROJECT)
@State(name = "GoLandDiffNavigation", storages = @Storage("diff-navigation.xml"))
public final class NavigationSettings implements PersistentStateComponent<NavigationSettings.Options> {
    public enum TargetScope {
        FOLLOW_TARGET("跟随本地导航目标"), STAGED_ONLY("只审已暂存修改");
        private final String label;
        TargetScope(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public static final class Options {
        public boolean enabled = true;
        public boolean autoStart = true;
        public TargetScope targetScope = TargetScope.FOLLOW_TARGET;
    }
    private Options state = new Options();
    public static NavigationSettings get(Project project) { return project.getService(NavigationSettings.class); }
    @Override public Options getState() { return state; }
    @Override public void loadState(Options options) { state = options; }
}
