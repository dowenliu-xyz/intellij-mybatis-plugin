package com.seventh7.mybatis.generate;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.HintUtil;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.source.PsiClassReferenceType;
import com.intellij.psi.xml.XmlTag;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.CommonProcessors;
import com.seventh7.mybatis.dom.model.GroupTwo;
import com.seventh7.mybatis.dom.model.Mapper;
import com.seventh7.mybatis.service.EditorService;
import com.seventh7.mybatis.service.JavaService;
import com.seventh7.mybatis.setting.MybatisSetting;
import com.seventh7.mybatis.ui.ListSelectionListener;
import com.seventh7.mybatis.ui.UiComponentFacade;
import com.seventh7.mybatis.util.CollectionUtils;
import com.seventh7.mybatis.util.JavaUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

import javax.swing.*;

/**
 * @author yanglin
 */
public abstract class StatementGenerator {

  public static final StatementGenerator UPDATE_GENERATOR = new UpdateGenerator("update", "modify", "set");

  public static final StatementGenerator SELECT_GENERATOR = new SelectGenerator("select", "get", "look", "find", "list", "search", "count", "query");

  public static final StatementGenerator DELETE_GENERATOR = new DeleteGenerator("del", "cancel");

  public static final StatementGenerator INSERT_GENERATOR = new InsertGenerator("insert", "add", "new");

  public static final Set<StatementGenerator> ALL = ImmutableSet.of(UPDATE_GENERATOR, SELECT_GENERATOR, DELETE_GENERATOR, INSERT_GENERATOR);

  private static final Function<Mapper, String> FUN = new Function<Mapper, String>() {
    @Override
    public String  apply(Mapper mapper) {
      VirtualFile vf = mapper.getXmlTag().getContainingFile().getVirtualFile();
      if (null == vf) {
        return "";
      }
      return vf.getCanonicalPath();
    }
  };

  public static Optional<PsiClass> getSelectResultType(@Nullable PsiMethod method) {
    if (null == method) {
      return Optional.absent();
    }
    PsiType returnType = method.getReturnType();
    if (returnType instanceof PsiPrimitiveType && returnType != PsiType.VOID) {
      return JavaUtils.findClazz(method.getProject(), ((PsiPrimitiveType) returnType).getBoxedTypeName());
    } else if (returnType instanceof PsiClassReferenceType) {
      PsiClassReferenceType type = (PsiClassReferenceType)returnType;
      if (type.hasParameters()) {
        PsiType[] parameters = type.getParameters();
        // Fix issue#35[https://github.com/seventh7/intellij-mybatis-plugin/issues/35]
        if (parameters.length == 1 && parameters[0] instanceof PsiClassReferenceType) {
          type = (PsiClassReferenceType)parameters[0];
        }
      }
      return Optional.fromNullable(type.resolve());
    }
    return Optional.absent();
  }

  public static void applyGenerate(@Nullable final PsiMethod method, final boolean scroll) {
    if (null == method) return;
    final StatementGenerator[] generators = getGenerators(method);
    if (1 == generators.length) {
        doGenerate(generators[0], method, scroll);
    } else {
      UiComponentFacade.getInstance(method.getProject()).showListPopup("[ Statement type for method: " + method.getName() + "]", new ListSelectionListener() {
        @Override
        public void selected(int index) {
            doGenerate(generators[index], method, scroll);
        }

        @Override
        public boolean isWriteAction() {
          return true;
        }

      }, generators);
    }
  }

    private static void doGenerate(@NotNull final StatementGenerator generator, @NotNull final PsiMethod method, final boolean scroll) {
        new WriteCommandAction.Simple(method.getProject(), method.getContainingFile()) {
            @Override protected void run() throws Throwable {
                generator.execute(method, scroll);
            }
        }.execute();
    }

  @NotNull
  public static StatementGenerator[] getGenerators(@NotNull PsiMethod method) {
    GenerateModel model = MybatisSetting.getInstance().getStatementGenerateModel();
    String target = method.getName();
    List<StatementGenerator> result = Lists.newArrayList();
    for (StatementGenerator generator : ALL) {
      if (model.matchesAny(generator.getPatterns(), target)) {
        result.add(generator);
      }
    }
    return CollectionUtils.isNotEmpty(result) ? result.toArray(new StatementGenerator[result.size()]) : ALL.toArray(new StatementGenerator[ALL.size()]);
  }

  private Set<String> patterns;

  public StatementGenerator(@NotNull String... patterns) {
    this.patterns = Sets.newHashSet(patterns);
  }

  public void execute(@NotNull final PsiMethod method, final boolean scroll) {
    PsiClass psiClass = method.getContainingClass();
    if (null == psiClass) {
      return;
    }
    CommonProcessors.CollectProcessor<Mapper> processor = new CommonProcessors.CollectProcessor<Mapper>();
    JavaService.getInstance(method.getProject()).processMapperInterfaces(psiClass, processor);
    final List<Mapper> mappers = Lists.newArrayList(processor.getResults());
    if (1 == mappers.size()) {
      setupTag(method, Iterables.getOnlyElement(mappers, null), scroll);
    } else {
      final HyperlinkLabel link = new HyperlinkLabel("");
      final JLabel label = new JLabel("Multi mapper with same namespace found");
      label.setBorder(HintUtil.createHintBorder());
      label.setBackground(HintUtil.INFORMATION_COLOR);
      label.setOpaque(true);
      HintManager.getInstance().showHint(label, RelativePoint.getCenterOf(link), HintManager.HIDE_BY_ANY_KEY | HintManager.HIDE_BY_TEXT_CHANGE, -1);
    }
  }

  private void setupTag(PsiMethod method, Mapper mapper, boolean scroll) {
    GroupTwo target = getComparableTarget(mapper, method);
    target.getId().setStringValue(method.getName());
    target.setValue(" ");
    XmlTag tag = target.getXmlTag();
    int offset = tag.getTextOffset() + tag.getTextLength() - tag.getName().length() + 1;
    if (scroll) {
      EditorService editorService = EditorService.getInstance(method.getProject());
      editorService.format(tag.getContainingFile(), tag);
      editorService.scrollTo(tag, offset);
    }
  }

  @Override
  public String toString() {
    return this.getDisplayText();
  }

  @NotNull
  protected abstract GroupTwo getComparableTarget(@NotNull Mapper mapper, @NotNull PsiMethod method);

  @NotNull
  public abstract String getId();

  @NotNull
  public abstract String getDisplayText();

  public Set<String> getPatterns() {
    return patterns;
  }

  public void setPatterns(Set<String> patterns) {
    this.patterns = patterns;
  }

}
