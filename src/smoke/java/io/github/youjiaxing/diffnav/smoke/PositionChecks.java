package io.github.youjiaxing.diffnav.smoke;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import java.util.function.BiConsumer;

/** Exercise production position tracking inside the sandbox application. */
final class PositionChecks {
    static void run(BiConsumer<Boolean, String> check) throws Exception {
        for (String[] pair : new String[][]{{"", "a"}, {"a", ""}, {"a\n", "a"}, {"a", "a\n"}}) {
            var fragments = com.intellij.diff.comparison.ComparisonManager.getInstance().compareLines(pair[0], pair[1],
                    com.intellij.diff.comparison.ComparisonPolicy.DEFAULT, new com.intellij.openapi.progress.EmptyProgressIndicator());
            var changes = fragments.stream().map(f -> new io.github.youjiaxing.diffnav.SelectedLines.Change(
                    f.getStartLine1(), f.getEndLine1(), f.getStartLine2(), f.getEndLine2())).toList();
            var result = io.github.youjiaxing.diffnav.SelectedLines.apply(pair[0], !pair[0].isEmpty(), pair[1], !pair[1].isEmpty(),
                    changes, !pair[1].isEmpty(), 0, 1);
            check.accept(result.text().equals(pair[1]), "actual SDK fragments preserve empty document and EOF semantics " + pair[0].length() + "→" + pair[1].length());
        }
        var type = Class.forName("io.github.youjiaxing.diffnav.NavigationPosition");
        var capture = type.getDeclaredMethod("capture", Document.class, int.class);
        var resolve = type.getDeclaredMethod("resolve", Document.class);
        var close = type.getDeclaredMethod("close");
        capture.setAccessible(true); resolve.setAccessible(true); close.setAccessible(true);
        String original = "package main\n\nfunc Target() int { return 1 }\n";
        Document document = EditorFactory.getInstance().createDocument(original);
        Object position = capture.invoke(null, document, original.indexOf("Target"));
        try {
            write(() -> document.insertString(0, "// added before navigation finishes\n"));
            check.accept((int) resolve.invoke(position, document) == document.getText().indexOf("Target"), "live target tracks insertion without stale offset");
            Document snapshot = EditorFactory.getInstance().createDocument("package main\n// different index context\nfunc Target() int { return 1 }\n");
            check.accept((int) resolve.invoke(position, snapshot) == snapshot.getText().indexOf("Target"), "unique unchanged symbol line maps across INDEX versions");
            Document duplicate = EditorFactory.getInstance().createDocument(original + original);
            check.accept((int) resolve.invoke(position, duplicate) == -1, "ambiguous version mapping refuses to guess");
            int target = document.getText().indexOf("Target");
            write(() -> document.deleteString(target, target + "Target".length()));
            check.accept((int) resolve.invoke(position, document) == -1, "deleted target cannot reuse old coordinates in source fallback");
            Document repeated = EditorFactory.getInstance().createDocument(original + original);
            Object ambiguous = capture.invoke(null, repeated, original.indexOf("Target"));
            try {
                check.accept((int) resolve.invoke(ambiguous, EditorFactory.getInstance().createDocument(original)) == -1,
                        "ambiguous origin cannot map to a different surviving code block");
            } finally { close.invoke(ambiguous); }
        } finally { close.invoke(position); }
    }
    private static void write(Runnable edit) {
        com.intellij.openapi.command.CommandProcessor.getInstance().runUndoTransparentAction(
                () -> ApplicationManager.getApplication().runWriteAction(edit));
    }
}
