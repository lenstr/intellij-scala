// This is a generated file. Not intended for manual editing.
package org.jetbrains.sbt.shell.grammar.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.sbt.shell.grammar.SbtShellConfig;
import org.jetbrains.sbt.shell.grammar.SbtShellVisitor;
import org.jetbrains.annotations.NotNull;

import static org.jetbrains.sbt.shell.grammar.SbtShellTypes.ID;

public class SbtShellConfigImpl extends ASTWrapperPsiElement implements SbtShellConfig {

  public SbtShellConfigImpl(ASTNode node) {
    super(node);
  }

  public void accept(@NotNull SbtShellVisitor visitor) {
    visitor.visitConfig(this);
  }

  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof SbtShellVisitor) accept((SbtShellVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  public PsiElement getId() {
    return findNotNullChildByType(ID);
  }

}
