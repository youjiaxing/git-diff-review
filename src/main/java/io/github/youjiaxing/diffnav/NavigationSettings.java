package io.github.youjiaxing.diffnav;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.Converter;
import com.intellij.util.xmlb.annotations.OptionTag;

@Service(Service.Level.PROJECT)
@State(name = "GitDiffReview", storages = @Storage("diff-navigation.xml"))
public final class NavigationSettings implements PersistentStateComponent<NavigationSettings.Options> {
    public enum TargetScope {
        // Keep the enum identity while the converter accepts labels written by older versions.
        FOLLOW_TARGET("只审未暂存修改"), STAGED_ONLY("只审已暂存修改");
        private final String label;
        TargetScope(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public static final class Options {
        public boolean enabled = true;
        public boolean autoStart = true;
        public boolean openNewFilesInDiff = true;
        @OptionTag(converter = TargetScopeConverter.class)
        public TargetScope targetScope = TargetScope.FOLLOW_TARGET;
    }
    public static final class TargetScopeConverter extends Converter<TargetScope> {
        @Override public TargetScope fromString(String value) {
            return "STAGED_ONLY".equals(value) || "只审已暂存修改".equals(value)
                    ? TargetScope.STAGED_ONLY : TargetScope.FOLLOW_TARGET;
        }
        @Override public String toString(TargetScope value) { return value.name(); }
    }
    private Options state = new Options();
    public static NavigationSettings get(Project project) { return project.getService(NavigationSettings.class); }
    @Override public Options getState() { return state; }
    @Override public void loadState(Options options) {
        if (options.targetScope == null) options.targetScope = TargetScope.FOLLOW_TARGET;
        state = options;
    }
}
