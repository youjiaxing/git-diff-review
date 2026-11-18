package io.github.youjiaxing.diffnav.smoke;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.ActiveGutterRenderer;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ex.LineStatusMarkerRenderer;
import com.intellij.openapi.vfs.LocalFileSystem;
import git4idea.index.GitStageTracker;
import java.awt.event.MouseEvent;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Native code comparison at the gutter, never a detached single-line action card. */
final class NativeMarkerPopupChecks {
    private final Project project;
    private final BiConsumer<Boolean,String> check;
    private Editor editor;
    private int step,ticks;
    private int addedBreakpoints, baselineAdds;
    private Set<com.intellij.xdebugger.breakpoints.XBreakpoint<?>> breakpoints;
    private List<Integer> hitXs;
    private int hitIndex;
    private String fixtureName="native-marker-popup.go", expected, unchangedIndex;
    private javax.swing.JButton staleButton;
    private final com.intellij.openapi.Disposable saveVeto=com.intellij.openapi.util.Disposer.newDisposable("native popup save veto");
    private Document vetoDocument;
    NativeMarkerPopupChecks(Project project,BiConsumer<Boolean,String> check){this.project=project;this.check=check;}
    boolean tick()throws Exception{
        if(++ticks>200)throw new AssertionError("Native marker popup timed out at "+step);
        if(step==0){
            String base="package main\nfunc popup() {\n";
            for(int i=2;i<12;i++)base+="    println(\"original "+i+"\")\n";
            base+="}\n";
            Files.writeString(path(),base);git("add","--","native-marker-popup.go");
            Files.writeString(path(),base.replace("original 4","edited 4").replace("original 8","edited 8"));
            var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path());editor=FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project,file),true);
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(file);GitStageTracker.getInstance(project).updateTrackerState();
            project.getMessageBus().connect(project).subscribe(com.intellij.xdebugger.breakpoints.XBreakpointListener.TOPIC,
                    new com.intellij.xdebugger.breakpoints.XBreakpointListener<com.intellij.xdebugger.breakpoints.XBreakpoint<?>>() {
                        @Override public void breakpointAdded(com.intellij.xdebugger.breakpoints.XBreakpoint<?> breakpoint) { addedBreakpoints++; }
                    });
            next();return false;
        }
        if(ticks<10)return false;
        var renderer=renderer();if(renderer==null)return false;
        var watch=field(renderer,"watch");var current=watch.getClass().getDeclaredMethod("current");current.setAccessible(true);if(!(boolean)current.invoke(watch))return false;
        if(step==1){
            var gutter=((com.intellij.openapi.editor.ex.EditorEx)editor).getGutterComponentEx();
            dispatch(gutter.getIconAreaOffset()+3,4,false);next();return false;
        }
        if(step==2){
            check.accept(addedBreakpoints>0 && manager().getAllBreakpoints().length>0,"positive control: real breakpoint-area click adds a Go breakpoint");
            breakpoints=new HashSet<>(List.of(manager().getAllBreakpoints()));baselineAdds=addedBreakpoints;
            int at=editor.getDocument().getLineStartOffset(2);editor.getCaretModel().moveToOffset(at);editor.getSelectionModel().setSelection(at,editor.getDocument().getLineEndOffset(2));
            click(renderer,4);next();return false;
        }
        if(step==3){
            if(!prepared())return false;
            unchangedBreakpoints();
            var lifetime=(com.intellij.openapi.util.CheckedDisposable)field(com.intellij.openapi.vcs.ex.LineStatusMarkerPopupService.getInstance(),"activePopupDisposable");
            check.accept(lifetime!=null && !lifetime.isDisposed(),"gutter click opens the platform native code popup rather than the single-line action card");
            var popup=local();if(popup==null || field(popup,"prepared")==null)return false;
            var before=(com.intellij.ui.EditorTextField)field(popup,"before");var after=(com.intellij.ui.EditorTextField)field(popup,"after");
            check.accept(before.getText().contains("original 4") && after.getText().contains("edited 4"),"popup shows actual INDEX and LOCAL code for the clicked modification");
            check.accept(!before.getText().contains("original 8") && !after.getText().contains("edited 8"),"another unstaged hunk is excluded from the popup comparison and operation");
            check.accept(editor.getCaretModel().getLogicalPosition().line==2 && editor.getSelectionModel().getSelectedText().contains("original 2"),"native popup preserves source caret and selection");
            var panel=(javax.swing.JComponent)field(popup,"panel");
            check.accept(SelectionUxChecks.button(panel,"在 Diff 中选择此处修改")==null,"popup no longer redirects the user through the rejected Diff-choice button");
            capture(panel);SelectionUxChecks.button(panel,"暂存这处修改").doClick();next();return false;
        }
        if(step==4){
            if(!index().contains("edited 4"))return false;
            check.accept(index().contains("original 8") && Files.readString(path()).contains("edited 8"),"native-popup Stage writes only the displayed hunk and preserves remaining local work");
            click(renderer,8);next();return false;
        }
        if(step==5){
            var popup=local();if(popup==null || field(popup,"prepared")==null)return false;
            com.intellij.openapi.vcs.ex.LineStatusMarkerPopupService.getInstance().closeActivePopup();next();return false;
        }
        if(step==6){
            check.accept(index().contains("original 8"),"closing native code popup leaves its displayed modification unstaged");unchangedBreakpoints();
            var popup=local();staleButton=SelectionUxChecks.button((javax.swing.JComponent)field(popup,"panel"),"暂存这处修改");staleButton.doClick();
            hitXs=new ArrayList<>();var gutter=((com.intellij.openapi.editor.ex.EditorEx)editor).getGutterComponentEx();
            int y=editor.logicalPositionToXY(new LogicalPosition(8,0)).y+editor.getLineHeight()/2;
            for(int x=0;x<gutter.getWidth();x++)if(renderer.canDoAction(editor,new MouseEvent(gutter,MouseEvent.MOUSE_MOVED,0,0,x,y,0,false)))hitXs.add(x);
            check.accept(hitXs.size()>=6,"native marker accepts a click band wider than the visible thin line");next();return false;
        }
        if(step==7){
            check.accept(index().contains("original 8"),"closed popup's retained button cannot execute a hidden stale action");
            dispatch(hitXs.get(hitIndex),8,true);next();return false;
        }
        if(step==8){
            if(!prepared())return false;unchangedBreakpoints();
            var gutter=((com.intellij.openapi.editor.ex.EditorEx)editor).getGutterComponentEx();
            check.accept(gutter.getHoveredFreeMarkersLine()==8,"real mouse movement activates the marker hover band");
            closePopup();if(++hitIndex<hitXs.size()){step=7;ticks=0;return false;}
            click(renderer,8);step=80;ticks=0;return false;
        }
        if(step==80){if(!prepared())return false;button("取消这段暂存").doClick();step=81;ticks=0;return false;}
        if(step==81){
            if(index().contains("original 8"))return false;
            check.accept(index().contains("edited 4") && index().contains("original 7") && index().contains("original 9") && Files.readString(path()).contains("edited 8"),
                    "native-popup Unstage removes only the displayed INDEX row and preserves other staged rows and LOCAL");
            com.intellij.openapi.application.WriteAction.run(()->{for(var b:breakpoints)manager().removeBreakpoint(b);});
            breakpoints=Set.of();baselineAdds=addedBreakpoints;
            setup("native-marker-multiline.go","package main\nfunc popup(){\n println(1)\n println(2)\n println(3)\n}\n",
                    "package main\nfunc popup(){\n println(123)\n}\n");step=9;ticks=0;return false;
        }
        if(step==9){click(renderer,2);next();return false;}
        if(step==10){
            if(!prepared())return false;
            check.accept(before().contains("println(1)") && before().contains("println(2)") && before().contains("println(3)") && after().contains("println(123)"),"multi-row replacement shows every removed INDEX row and the actual LOCAL replacement");
            button("暂存这处修改").doClick();next();return false;
        }
        if(step==11){
            if(!index().equals(expected))return false;
            check.accept(true,"whole displayed three-to-one replacement is staged exactly");
            setup("native-marker-deletion.go","package main\nfunc popup(){\n println(1)\n println(2)\n}\n","package main\nfunc popup(){\n println(2)\n}\n");next();return false;
        }
        if(step==12){dispatch(com.intellij.openapi.diff.LineStatusMarkerDrawUtil.getGutterArea(editor).first+1,2,true,true);next();return false;}
        if(step==13){
            if(!prepared())return false;
            check.accept(before().contains("println(1)") && !after().contains("println(1)"),"deletion boundary compares the actual deleted INDEX code without selecting the next row");
            button("暂存这处修改").doClick();next();return false;
        }
        if(step==14){
            if(!index().equals(expected))return false;check.accept(true,"deletion popup stages only the displayed deleted row");
            setup("native-marker-eof.go","package main\n","package main\n\n// tail\n");next();return false;
        }
        if(step==15){click(renderer,2);next();return false;}
        if(step==16){if(!prepared())return false;button("暂存这处修改").doClick();next();return false;}
        if(step==17){
            if(!index().equals(expected))return false;check.accept(true,"native EOF hunk Stage retains its separator blank line and final LF");
            setup("native-marker-stale.go","package main\nfunc popup(){ println(1) }\n","package main\nfunc popup(){ println(2) }\n");next();return false;
        }
        if(step==18){click(renderer,1);next();return false;}
        if(step==19){
            if(!prepared())return false;unchangedIndex=index();
            Files.writeString(Path.of(project.getBasePath(),"native-popup-unrelated.txt"),"external index\n");git("add","--","native-popup-unrelated.txt");
            button("暂存这处修改").doClick();next();return false;
        }
        if(step==20){
            check.accept(index().equals(unchangedIndex) && git("show",":native-popup-unrelated.txt").equals("external index\n"),"external INDEX update rejects the stale popup transaction and preserves unrelated staged content");
            vetoDocument=editor.getDocument();
            com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer.EP_NAME.getPoint().registerExtension(new com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer(){
                @Override public boolean maySaveDocument(Document document,boolean explicit){return document!=vetoDocument;}
            },saveVeto);
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project,()->editor.getDocument().setText(expected.replace("println(2)","println(3)")));next();return false;
        }
        if(step==21){click(renderer,1);next();return false;}
        if(step==22){
            if(!prepared())return false;
            check.accept(FileDocumentManager.getInstance().isDocumentUnsaved(editor.getDocument()) && button("暂存这处修改")==null,"unsaved native popup shows code but offers no executable Stage");
            check.accept(Files.readString(path()).equals(expected) && index().equals(unchangedIndex),"inspecting unsaved code neither saves the document nor modifies INDEX");
            closePopup();vetoDocument=null;com.intellij.openapi.util.Disposer.dispose(saveVeto);FileDocumentManager.getInstance().saveDocument(editor.getDocument());next();return false;
        }
        if(step==23){click(renderer,1);next();return false;}
        if(step==24){
            if(!prepared())return false;var popup=local();staleButton=button("暂存这处修改");
            io.github.youjiaxing.diffnav.NavigationSettings.get(project).getState().sourceStaging=false;
            project.getService(io.github.youjiaxing.diffnav.StageNavigationService.class).settingsChanged();
            check.accept(com.intellij.openapi.util.Disposer.isDisposed((com.intellij.openapi.Disposable)field(popup,"lifetime")),"disabling source enhancement disposes the native popup");
            staleButton.doClick();check.accept(index().equals(unchangedIndex),"disabling source enhancement cannot execute a prepared popup action");return true;
        }
        return false;
    }
    private void next(){step++;ticks=0;}
    private Path path(){return Path.of(project.getBasePath(),fixtureName);}
    private String index()throws Exception{return git("show",":"+fixtureName);}
    private void setup(String name,String base,String local)throws Exception{fixtureName=name;expected=local;Files.writeString(path(),base);git("add","--",name);Files.writeString(path(),local);
        var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path());editor=FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project,file),true);
        com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(file);GitStageTracker.getInstance(project).updateTrackerState();}
    private boolean prepared()throws Exception{var popup=local();return popup!=null && field(popup,"prepared")!=null && field(popup,"panel")!=null;}
    private String before()throws Exception{return ((com.intellij.ui.EditorTextField)field(local(),"before")).getText();}
    private String after()throws Exception{return ((com.intellij.ui.EditorTextField)field(local(),"after")).getText();}
    private javax.swing.JButton button(String text)throws Exception{return SelectionUxChecks.button((javax.swing.JComponent)field(local(),"panel"),text);}
    private void closePopup(){com.intellij.openapi.vcs.ex.LineStatusMarkerPopupService.getInstance().closeActivePopup();}
    private String git(String...args)throws Exception{var cmd=new ArrayList<>(List.of("git","-C",project.getBasePath()));cmd.addAll(List.of(args));var p=new ProcessBuilder(cmd).redirectErrorStream(true).start();var s=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);if(p.waitFor()!=0)throw new AssertionError(s);return s.replace("\r\n","\n");}
    private ActiveGutterRenderer renderer(){for(var h:DocumentMarkupModel.forDocument(editor.getDocument(),project,false).getAllHighlighters())if(Boolean.TRUE.equals(h.getUserData(LineStatusMarkerRenderer.Companion.getMAIN_KEY())) && h.getLineMarkerRenderer()!=null && h.getLineMarkerRenderer().getClass().getName().contains("SourceMarkerService$Proxy"))return(ActiveGutterRenderer)h.getLineMarkerRenderer();return null;}
    private void click(ActiveGutterRenderer r,int line){dispatch(com.intellij.openapi.diff.LineStatusMarkerDrawUtil.getGutterArea(editor).first+1,line,true);}
    private void dispatch(int x,int line,boolean marker){
        dispatch(x,line,marker,false);
    }
    private void dispatch(int x,int line,boolean marker,boolean point){
        var gutter=((com.intellij.openapi.editor.ex.EditorEx)editor).getGutterComponentEx();int y=editor.logicalPositionToXY(new LogicalPosition(line,0)).y+(point?0:editor.getLineHeight()/2);long t=System.currentTimeMillis();
        gutter.dispatchEvent(new MouseEvent(gutter,MouseEvent.MOUSE_MOVED,t,0,x,y,0,false,MouseEvent.NOBUTTON));
        gutter.dispatchEvent(new MouseEvent(gutter,MouseEvent.MOUSE_PRESSED,t+1,java.awt.event.InputEvent.BUTTON1_DOWN_MASK,x,y,1,false,MouseEvent.BUTTON1));
        var release=new MouseEvent(gutter,MouseEvent.MOUSE_RELEASED,t+2,0,x,y,1,false,MouseEvent.BUTTON1);gutter.dispatchEvent(release);
        if(marker)check.accept(release.isConsumed(),"real marker release is consumed before the breakpoint click listener");
    }
    private com.intellij.xdebugger.breakpoints.XBreakpointManager manager(){return com.intellij.xdebugger.XDebuggerManager.getInstance(project).getBreakpointManager();}
    private void unchangedBreakpoints(){check.accept(addedBreakpoints==baselineAdds && breakpoints.equals(new HashSet<>(List.of(manager().getAllBreakpoints()))),"marker clicks neither add a breakpoint nor toggle the existing breakpoint on that line");}
    private Object local()throws Exception{var type=Class.forName("io.github.youjiaxing.diffnav.SourceStagingService");var m=type.getDeclaredMethod("binding",Editor.class);m.setAccessible(true);return field(m.invoke(null,editor),"markerPopup");}
    private static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private void capture(javax.swing.JComponent c)throws Exception{var image=new java.awt.image.BufferedImage(c.getWidth(),c.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{c.paint(g);}finally{g.dispose();}javax.imageio.ImageIO.write(image,"png",Path.of(System.getProperty("diffnav.smoke.report")).getParent().resolve("native-marker-comparison.png").toFile());}
}
