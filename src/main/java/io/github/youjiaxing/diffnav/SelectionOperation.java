package io.github.youjiaxing.diffnav;

/** The write target is part of the operation, never inferred from the displayed side. */
enum SelectionOperation {
    STAGE("Stage 选中行"), UNSTAGE("Unstage 选中行"), DISCARD("Discard 选中修改");
    final String text;
    SelectionOperation(String text) { this.text = text; }
}
