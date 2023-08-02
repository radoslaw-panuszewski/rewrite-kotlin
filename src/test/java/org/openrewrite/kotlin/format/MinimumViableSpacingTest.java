/*
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.kotlin.format;

import org.junit.jupiter.api.Test;
import org.openrewrite.ExecutionContext;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Space;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.kotlin.Assertions.kotlin;
import static org.openrewrite.test.RewriteTest.toRecipe;

@SuppressWarnings("All")
class MinimumViableSpacingTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipes(
          toRecipe(() -> new JavaIsoVisitor<>() {
              @Override
              public Space visitSpace(Space space, Space.Location loc, ExecutionContext ctx) {
                  if (ctx.getMessage("cyclesThatResultedInChanges", 0) == 0) {
                      return space.withWhitespace("");
                  }
                  return space;
              }
          }),
          toRecipe(() -> new MinimumViableSpacingVisitor<>(null))
        );
    }

    @Test
    void classDeclaration() {
        rewriteRun(
          kotlin(
            """
              class A {
              }
              """,
            """
              class A{}
              """
          )
        );
    }


    @Test
    void classDeclarationWithFinalModifier() {
        rewriteRun(
          kotlin(
            """
              private    final    class A {
              }
              """,
            """
              private final class A{}
              """
          )
        );
    }

    @Test
    void classDeclarationWithModifier() {
        rewriteRun(
          kotlin(
            """
              private    class A {
              }
              """,
            """
              private class A{}
              """
          )
        );
    }

    @Test
    void method() {
        rewriteRun(
          kotlin(
            """
              class A {
                  fun <T> foo() {
                  }
              }
              """,
            """
              class A{fun <T> foo(){}}
              """
          )
        );
    }

    @Test
    void returnExpression() {
        rewriteRun(
          kotlin(
            """
              class A {
                  fun foo() :   String {
                      return "foo"
                  }
              }
              """,
            """
              class A{fun foo():String{return "foo"}}
              """
          )
        );
    }

    @Test
    void ifElse() {
        rewriteRun(
          kotlin(
            """
              fun method(a: Int, b: Int) {
                  val max = if (a > b) a else   b
              }
              """,
            """
              fun method(a:Int,b:Int){val max=if(a>b)a else b}
              """
          )
        );
    }

    @Test
    void variableDeclaration() {
        rewriteRun(
          kotlin(
            """
              val zero: Int = 0
              """,
            """
              val zero:Int=0
              """
          )
        );
    }

    @Test
    void variableDeclarations() {
        rewriteRun(
          kotlin(
            """
              val zero: Int = 0
                  var one: Int = 1
              """,
            """
              val zero:Int=0
              var one:Int=1
              """
          )
        );
    }

    @Test
    void variableDeclarationsInClass() {
        rewriteRun(
          kotlin(
            """
              class A {
                  val zero: Int = 0
                  var one: Int = 1
              }
              """,
            """
              class A{val zero:Int=0
              var one:Int=1}
              """
          )
        );
    }

    @Test
    void variableDeclarationsInMethod() {
        rewriteRun(
          kotlin(
            """
              class A {
                  fun foo(paramA : Int, paramB : Int) {
                      val unassigned:Int
                      var a = 1
                      val b = 2
                  }
              }
              """,
            """
              class A{fun foo(paramA:Int,paramB:Int){val unassigned:Int
              var a=1
              val b=2}}
              """
          )
        );
    }

    @Test
    void variableDeclarationsWithIn() {
        rewriteRun(
          kotlin(
            """
              fun foo(arr: IntArray) {
                  var x = 1 in arr
              }
              """,
            """
              fun foo(arr:IntArray){var x=1 in arr}
              """
          )
        );
    }

    @Test
    void forloop() {
        rewriteRun(
          kotlin(
            """
              fun foo(arr: IntArray) {
                  for (i in arr) {
                  }
              }
              """,
            """
              fun foo(arr:IntArray){for(i in arr){}}
              """
          )
        );
    }

    @Test
    void variableDeclarationsInForLoops() {
        rewriteRun(
          kotlin(
            """
              class Test {
                  fun foo(arr: IntArray) {
                      for (n in 0..arr.size) {
                      }

                      for (i in arr) {
                      }

                      for (i: Int in arr) {
                      }
                  }
              }
              """,
            """
              class Test{fun foo(arr:IntArray){for(n in 0..arr.size){}
              for(i in arr){}
              for(i:Int in arr){}}}
              """
          )
        );
    }
}
