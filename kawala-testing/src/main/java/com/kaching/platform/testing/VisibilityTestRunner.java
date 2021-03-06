/**
 * Copyright 2010 Wealthfront Inc. Licensed under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.kaching.platform.testing;

import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Lists.transform;
import static com.google.common.collect.Sets.newHashSet;
import static com.google.common.io.Closeables.closeQuietly;
import static com.kaching.platform.testing.VisibilityTestRunner.Intent.PRIVATE;
import static java.lang.String.format;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassReader.SKIP_FRAMES;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.EmptyVisitor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.kaching.platform.testing.ParsedElements.ParsedClass;
import com.kaching.platform.testing.ParsedElements.ParsedConstructor;
import com.kaching.platform.testing.ParsedElements.ParsedField;
import com.kaching.platform.testing.ParsedElements.ParsedMethod;

/**
 * Example:
 * <pre>
 *   {@literal @}Visibilities(
 *     {@literal @}Check(paths = "bin", visibilities = {
 *       {@literal @}Visibility(value = Inject.class, intent = PRIVATE),
 *       {@literal @}Visibility(value = VisibleForTesting.class, intent = PRIVATE)
 *     }))
 *   {@literal @}RunWith(VisibilityTestRunner.class)
 *   public class VisibilityTest {
 *   }
 * </pre>
 */
public class VisibilityTestRunner
    extends AbstractDeclarativeTestRunner<VisibilityTestRunner.Visibilities>  {

  /**
   * Top level annotation describing a visibility test.
   */
  @Target(TYPE)
  @Retention(RUNTIME)
  public @interface Visibilities {

    /**
     * Lists all the checks performed by this visibility test.
     */
    public Check[] value();

  }

  @Retention(RUNTIME)
  @Target({})
  public @interface Check {

    public String[] paths();

    public Visibility[] visibilities();

  }

  @Retention(RUNTIME)
  @Target({})
  public @interface Visibility {

    /**
     * An annotation used to describe a visibility, such as {@code @VisibleForTesting}.
     */
    public Class<? extends Annotation> value();

    /**
     * The intent of the annotation. Currently, {@code PRIVATE} is the only supported intent.
     */
    public Intent intent() default PRIVATE;

    public String[] exceptions() default {};

  }

  public enum Intent {
    PRIVATE, DEFAULT, PROTECTED
  }

  /**
   * Internal use only.
   */
  public VisibilityTestRunner(Class<?> clazz) {
    super(clazz, Visibilities.class);
  }

  @Override
  protected void runTest(Visibilities annotation) throws IOException {
    CombinedAssertionFailedError error = new CombinedAssertionFailedError("visibility violations");
    for (Check check : annotation.value()) {
      List<String> paths = ImmutableList.<String> copyOf(check.paths());
      for (Visibility visibility : check.visibilities()) {
        checkVisibility(paths, visibility, error);
      }
    }
    error.throwIfHasErrors();
  }

  private void checkVisibility(
      List<String> paths,
      Visibility visibility,
      CombinedAssertionFailedError error) throws IOException {

    new Tester(visibility, error).analyze(
        concat(
            transform(
                transform(
                    paths,
                    new Function<String, ClassTree>() {
                      @Override
                      public ClassTree apply(String path) {
                        return new ClassTree(new File(path));
                      }
                    }),
                new Function<ClassTree, List<File>>() {
                  @Override
                  public List<File> apply(ClassTree from) {
                    return from.getClassFiles();
                  }
                })));
  }

  /**
   * Is {@code element} visible by {@code currentClass}?
   */
  @VisibleForTesting
  boolean isVisible(ParsedElement element, final ParsedClass currentClass, Intent intent) {
    switch (intent) {
      case PRIVATE:
        return element.visit(new DefaultParsedElementVisitor<Boolean>(true) {
          @Override
          public Boolean caseMethod(ParsedMethod element) {
            String name = element.getOwner().getOwner();
            String className = currentClass.getOwner();
            return name.equals(className);
          }
          @Override
          public Boolean caseField(ParsedField element) {
            String name = element.getOwner().getOwner();
            String className = currentClass.getOwner();
            return name.equals(className);
          }
          @Override
          public Boolean caseClass(ParsedClass element) {
            String name = element.getOwner();
            String className = currentClass.getOwner();
            return name.equals(className);
          }
        });
      default:
        throw new UnsupportedOperationException("PRIVATE is the only supported intent");
    }
  }

  private class Tester {

    private final Class<? extends Annotation> annotationClass;
    private final Intent intent;
    private final Set<String> exceptions;
    private final CombinedAssertionFailedError error;

    private final Set<String> spuriousExceptions;
    private final String annotationDescription;
    private final Set<ParsedElement> annotatedElements;

    Tester(Visibility visibility, CombinedAssertionFailedError error) {
      this.annotationClass = visibility.value();
      this.intent = visibility.intent();
      this.exceptions = newHashSet(visibility.exceptions());
      this.error = error;

      this.spuriousExceptions = newHashSet(exceptions);
      this.annotatedElements = newHashSet();
      this.annotationDescription = format("L%s;", annotationClass.getName().replace(".", "/"));
    }

    void analyze(Iterable<File> files) throws IOException {
      // find all the annotated elements
      for (File file : files) {
        FileInputStream in = null;
        try {
          in = new FileInputStream(file);
          new ClassReader(in).accept(new FindAnnotatedElements(), SKIP_FRAMES | SKIP_DEBUG);
        } finally {
          closeQuietly(in);
        }
      }

      // find all the references of the annotated elements and record violations
      for (File file : files) {
        FileInputStream in = null;
        try {
          in = new FileInputStream(file);
          new ClassReader(in).accept(new FindIllegalCalls(), SKIP_FRAMES | SKIP_DEBUG);
        } finally {
          closeQuietly(in);
        }
      }

      for (String spuriousException : spuriousExceptions) {
        error.addError(format(
            "%s marked as an exception for @%s but didn't occur",
            spuriousException, annotationClass.getSimpleName()));
      }
    }

    private class FindAnnotatedElements extends EmptyVisitor {

      private ParsedClass currentClass;
      private ParsedMethod currentMethod;
      private ParsedField currentField;
      private ParsedConstructor currentConstructor;

      @Override
      public void visit(int version, int access, String name, String signature,
          String superName, String[] interfaces) {
        currentClass = new ParsedClass(name);
      }

      @Override
      public MethodVisitor visitMethod(int access, String name, String descriptor,
          String signature, String[] exceptions) {

        currentConstructor = null;
        currentMethod = null;
        currentField = null;

        if (name.equals("<clinit>")) {
          // interface initialization method
        } else if (name.equals("<init>")) {
          currentConstructor = new ParsedConstructor();
        } else {
          currentMethod = new ParsedMethod(currentClass, name, descriptor);
        }
        return this;
      }

      @Override
      public FieldVisitor visitField(int access, String name, String descriptor,
          String signature, Object value) {
        currentField = new ParsedField(currentClass, name);
        currentMethod = null;
        currentConstructor = null;
        return this;
      }

      @Override
      public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        if (annotationDescription.equals(descriptor)) {
          if (currentField != null) {
            // annotated field
            annotatedElements.add(currentField);
          } else if (currentMethod != null) {
            // annotated method
            annotatedElements.add(currentMethod);
          } else if (currentConstructor != null) {
            // TODO annotated constructors are not supported
          } else if (currentClass != null) {
            // annotated class
            annotatedElements.add(currentClass);
          }
        }
        return this;
      }

    }

    private class FindIllegalCalls extends EmptyVisitor {

      private String currentMethodName;
      private ParsedClass currentClass;

      @Override
      public void visit(int version, int access, String name, String signature,
          String superName, String[] interfaces) {
        currentClass = new ParsedClass(name);
      }

      @Override
      public MethodVisitor visitMethod(int access, String name, String descriptor,
          String signature, String[] exceptions) {
        currentMethodName = name;
        return this;
      }

      @Override
      public void visitMethodInsn(int opcode, String owner, String name, String descriptor) {
        check(new ParsedClass(owner));
        if (name.equals("<clinit>")) {
          // interface initialization method
        } else if (name.equals("<init>")) {
          // TODO handle constructors
        } else {
          check(new ParsedMethod(new ParsedClass(owner), name, descriptor));
        }
      }

      @Override
      public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        check(new ParsedClass(owner));
        check(new ParsedField(new ParsedClass(owner), name));
      }

      private void check(ParsedElement element) {
        if (annotatedElements.contains(element)) {
          if (!isVisible(element, currentClass, intent)) {
            if (exceptions.contains(currentClass.toString())) {
              spuriousExceptions.remove(currentClass.toString());
            } else {
              error.addError(format("%s.%s uses %s", currentClass, currentMethodName, element));
            }
          }
        }
      }

    }

  }

}
