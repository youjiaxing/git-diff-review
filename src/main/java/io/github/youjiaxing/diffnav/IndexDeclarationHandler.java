package io.github.youjiaxing.diffnav;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiElement;
import git4idea.index.vfs.GitIndexVirtualFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/** Resolve an identical INDEX snapshot using the native working-tree PSI context. */
public final class IndexDeclarationHandler implements GotoDeclarationHandler {
    private static final ThreadLocal<Boolean> RESOLVING = new ThreadLocal<>();

    @Override public PsiElement[] getGotoDeclarationTargets(PsiElement source, int offset, Editor editor) {
        if (Boolean.TRUE.equals(RESOLVING.get()) || editor == null || editor.getProject() == null
                || !(editor instanceof EditorEx) || !Boolean.TRUE.equals(editor.getUserData(StageNavigationService.STAGE_EDITOR))
                || !editor.getProject().getService(StageNavigationService.class).isEnabled()) return null;
        var documents = FileDocumentManager.getInstance();
        if (!(documents.getFile(editor.getDocument()) instanceof GitIndexVirtualFile index)) return null;
        var file = index.getFilePath().getVirtualFile();
        var document = file == null ? null : documents.getDocument(file);
        if (document == null || !document.getText().equals(editor.getDocument().getText())) return null;
        // This facade is only passed to the native read-only target lookup. No editor
        // is opened, created, disposed or attached to a different document.
        Editor local = (Editor) Proxy.newProxyInstance(EditorEx.class.getClassLoader(), new Class<?>[]{EditorEx.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getDocument")) return document;
                    if (method.getName().equals("getVirtualFile")) return file;
                    try { return method.invoke(editor, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
        RESOLVING.set(true);
        try { return GotoDeclarationAction.findAllTargetElements(editor.getProject(), local, offset); }
        finally { RESOLVING.remove(); }
    }
}
