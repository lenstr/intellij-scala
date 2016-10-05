// This is a generated file. Not intended for manual editing.
package org.jetbrains.sbt.shell.grammar;

import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiElement;

public class SbtShellVisitor extends PsiElementVisitor {

  public void visitCommand(@NotNull SbtShellCommand o) {
    visitPsiElement(o);
  }

  public void visitConfig(@NotNull SbtShellConfig o) {
    visitPsiElement(o);
  }

  public void visitIntask(@NotNull SbtShellIntask o) {
    visitPsiElement(o);
  }

  public void visitKey(@NotNull SbtShellKey o) {
    visitPsiElement(o);
  }

  public void visitProjectId(@NotNull SbtShellProjectId o) {
    visitPsiElement(o);
  }

  public void visitScopedKey(@NotNull SbtShellScopedKey o) {
    visitPsiElement(o);
  }

  public void visitUri(@NotNull SbtShellUri o) {
    visitPsiElement(o);
  }

  public void visitPsiElement(@NotNull PsiElement o) {
    visitElement(o);
  }

}
