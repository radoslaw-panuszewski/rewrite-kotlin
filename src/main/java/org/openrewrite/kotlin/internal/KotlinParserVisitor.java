/*
 * Copyright 2022 the original author or authors.
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
package org.openrewrite.kotlin.internal;

import org.jetbrains.kotlin.KtRealPsiSourceElement;
import org.jetbrains.kotlin.com.intellij.lang.ASTNode;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.*;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.descriptors.ClassKind;
import org.jetbrains.kotlin.fir.FirElement;
import org.jetbrains.kotlin.fir.FirPackageDirective;
import org.jetbrains.kotlin.fir.declarations.*;
import org.jetbrains.kotlin.fir.declarations.impl.FirPrimaryConstructor;
import org.jetbrains.kotlin.fir.expressions.FirBlock;
import org.jetbrains.kotlin.fir.expressions.FirConstExpression;
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall;
import org.jetbrains.kotlin.fir.expressions.FirStatement;
import org.jetbrains.kotlin.fir.types.*;
import org.jetbrains.kotlin.fir.types.impl.FirImplicitNullableAnyTypeRef;
import org.jetbrains.kotlin.fir.visitors.FirDefaultVisitor;
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.psi.psiUtil.PsiChildRange;
import org.jetbrains.kotlin.psi.psiUtil.PsiUtilsKt;
import org.jetbrains.kotlin.psi.stubs.elements.KtAnnotationEntryElementType;
import org.openrewrite.ExecutionContext;
import org.openrewrite.FileAttributes;
import org.openrewrite.internal.EncodingDetectingInputStream;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.KotlinTypeMapping;
import org.openrewrite.kotlin.marker.EmptyBody;
import org.openrewrite.kotlin.marker.MethodClassifier;
import org.openrewrite.kotlin.marker.PropertyClassifier;
import org.openrewrite.kotlin.marker.Semicolon;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.Math.max;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.internal.StringUtils.indexOfNextNonWhitespace;
import static org.openrewrite.java.tree.Space.EMPTY;
import static org.openrewrite.java.tree.Space.format;
import static org.openrewrite.kotlin.marker.PropertyClassifier.ClassifierType.VAL;
import static org.openrewrite.kotlin.marker.PropertyClassifier.ClassifierType.VAR;

public class KotlinParserVisitor extends FirDefaultVisitor<J, ExecutionContext> {
    private final Path sourcePath;

    @Nullable
    private final FileAttributes fileAttributes;
    private final String source;
    private final Charset charset;
    private final boolean charsetBomMarked;
    private final KotlinTypeMapping typeMapping;
    private final ExecutionContext ctx;

    private int cursor = 0;

    private static final Pattern whitespaceSuffixPattern = Pattern.compile("\\s*[^\\s]+(\\s*)");

    public KotlinParserVisitor(Path sourcePath, @Nullable FileAttributes fileAttributes, EncodingDetectingInputStream source, JavaTypeCache typeCache, ExecutionContext ctx) {
        this.sourcePath = sourcePath;
        this.fileAttributes = fileAttributes;
        this.source = source.readFully();
        this.charset = source.getCharset();
        this.charsetBomMarked = source.isCharsetBomMarked();
        this.typeMapping = new KotlinTypeMapping(typeCache);
        this.ctx = ctx;
    }

    @Override
    public J visitFile(FirFile file, ExecutionContext ctx) {
        JRightPadded<J.Package> pkg = null;
        if (!file.getPackageDirective().getPackageFqName().isRoot()) {
            pkg = maybeSemicolon((J.Package) visitPackageDirective(file.getPackageDirective(), ctx));
        }

        List<JRightPadded<J.Import>> imports = file.getImports().stream()
                .map(it -> maybeSemicolon((J.Import) visitImport(it, ctx)))
                .collect(Collectors.toList());

        List<JRightPadded<Statement>> statements = new ArrayList<>(file.getDeclarations().size());
        for (FirDeclaration declaration : file.getDeclarations()) {
            Statement statement = (Statement) visitElement(declaration, ctx);
            statements.add(JRightPadded.build(statement));
        }

        return new K.CompilationUnit(
                randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                sourcePath,
                fileAttributes,
                charset.name(),
                charsetBomMarked,
                null,
                pkg,
                imports,
                statements,
                Space.EMPTY
        );
    }

    @Override
    public J visitBlock(FirBlock block, ExecutionContext ctx) {
        Space prefix = whitespace();

        skip("{");
        JRightPadded<Boolean> stat = new JRightPadded<>(false, EMPTY, Markers.EMPTY);

        List<FirStatement> statements = new ArrayList<>(block.getStatements().size());
        for (FirStatement s : block.getStatements()) {
            // TODO: filter out synthetic statements.
            statements.add(s);
        }

        return new J.Block(
                randomId(),
                prefix,
                Markers.EMPTY,
                stat,
                emptyList(), // TODO
                sourceBefore("}"));
    }

    @Override
    public J visitClass(FirClass klass, ExecutionContext ctx) {
        if (!(klass instanceof FirRegularClass)) {
            throw new IllegalStateException("Implement me.");
        }

        FirRegularClass firRegularClass = (FirRegularClass) klass;
        Space prefix = whitespace();

        // Not used until it's possible to handle K.Modifiers.
        List<J> modifiers = emptyList();
        if (firRegularClass.getSource() != null) {
            PsiChildRange psiChildRange = PsiUtilsKt.getAllChildren(((KtRealPsiSourceElement) firRegularClass.getSource()).getPsi());
            if (psiChildRange.getFirst() instanceof KtDeclarationModifierList) {
                KtDeclarationModifierList modifierList = (KtDeclarationModifierList) psiChildRange.getFirst();
                modifiers = getModifiers(modifierList);
            }
        }

        List<J.Annotation> kindAnnotations = emptyList(); // TODO: the last annotations in modifiersAndAnnotations should be added to the class.

        J.ClassDeclaration.Kind kind;
        ClassKind classKind = klass.getClassKind();
        if (ClassKind.INTERFACE == classKind) {
            kind = new J.ClassDeclaration.Kind(randomId(), sourceBefore("interface"), Markers.EMPTY, kindAnnotations, J.ClassDeclaration.Kind.Type.Interface);
        } else if (ClassKind.CLASS == classKind || ClassKind.ENUM_CLASS == classKind || ClassKind.ANNOTATION_CLASS == classKind) {
            // Enums and Interfaces are modifiers in kotlin and require the modifier prefix to preserve source code.
            kind = new J.ClassDeclaration.Kind(randomId(), sourceBefore("class"), Markers.EMPTY, kindAnnotations, J.ClassDeclaration.Kind.Type.Class);
        } else {
            throw new IllegalStateException("Implement me.");
        }

        // TODO: add type mapping
        J.Identifier name = new J.Identifier(randomId(), sourceBefore(firRegularClass.getName().asString()),
                Markers.EMPTY, firRegularClass.getName().asString(), null, null);

        JContainer<J.TypeParameter> typeParams = firRegularClass.getTypeParameters().isEmpty() ? null : JContainer.build(
                sourceBefore("<"),
                convertAll(firRegularClass.getTypeParameters(), commaDelim, t -> sourceBefore(">"), ctx),
                Markers.EMPTY);

        // TODO: fix: super type references are resolved as error kind.
        JLeftPadded<TypeTree> extendings = null;

        // TODO: fix: super type references are resolved as error kind.
        JContainer<TypeTree> implementings = null;

        int saveCursor = cursor;
        Space bodyPrefix = whitespace();
        EmptyBody emptyBody = null;
        if (source.substring(cursor).isEmpty() || !source.substring(cursor).startsWith("{")) {
            emptyBody = new EmptyBody(randomId());
        } else {
            cursor++; // Increment past the `{`
        }

        if (emptyBody != null) {
            cursor = saveCursor;
        }

        List<FirElement> membersMultiVariablesSeparated = new ArrayList<>(firRegularClass.getDeclarations().size());
        for (FirDeclaration declaration : firRegularClass.getDeclarations()) {
            if (declaration instanceof FirPrimaryConstructor) {
                FirPrimaryConstructor primaryConstructor = (FirPrimaryConstructor) declaration;
                // Note: the generated constructor contain flags generated = false and from source = true ...
                continue;
            }
            membersMultiVariablesSeparated.add(declaration);
        }


        List<JRightPadded<Statement>> members = new ArrayList<>(membersMultiVariablesSeparated.size());
        for (FirElement firElement : membersMultiVariablesSeparated) {
            members.add(maybeSemicolon((Statement) visitElement(firElement, ctx)));
        }

        J.Block body = new J.Block(randomId(), bodyPrefix, Markers.EMPTY, new JRightPadded<>(false, EMPTY, Markers.EMPTY),
                members, emptyBody != null ? Space.EMPTY : sourceBefore("}"));

        if (emptyBody != null) {
            body = body.withMarkers(body.getMarkers().addIfAbsent(emptyBody));
        }

        return new J.ClassDeclaration(
                randomId(),
                prefix,
                Markers.EMPTY,
                emptyList(), // TODO
                emptyList(), // TODO: requires updates to handle kotlin specific modifiers.
                kind,
                name, // TODO
                null, // TODO
                null, // TODO
                extendings, // TODO
                implementings,
                body, // TODO
                null // TODO
        );
    }

    @Override
    public <T> J visitConstExpression(FirConstExpression<T> constExpression, ExecutionContext ctx) {
        Space prefix = whitespace();
        Object value = constExpression.getValue();
        String valueSource = source.substring(constExpression.getSource().getStartOffset(), constExpression.getSource().getEndOffset());
        JavaType.Primitive type = null; // TODO: add type mapping.

        return new J.Literal(
                randomId(),
                prefix,
                Markers.EMPTY,
                value,
                valueSource,
                null,
                type);
    }

    /**
     * TODO: function declarations.
     * @param function
     * @param ctx
     * @return
     */
    @Override
    public J visitFunction(FirFunction function, ExecutionContext ctx) {
        if (function instanceof FirSimpleFunction) {
            return visitSimpleFunction((FirSimpleFunction) function, ctx);
        } else {
            throw new IllegalStateException("Implement me.");
        }
    }

    @Override
    public J visitFunctionCall(FirFunctionCall functionCall, ExecutionContext ctx) {
        throw new IllegalStateException("how is a new class identified?");
    }

    @Override
    public J visitImport(FirImport firImport, ExecutionContext ctx) {
        Space prefix = sourceBefore("import");
        JLeftPadded<Boolean> statik = padLeft(EMPTY, false);

        J.FieldAccess qualid;
        if (firImport.getImportedFqName() == null) {
            throw new IllegalStateException("implement me.");
        } else {
            Space space = whitespace();
            String packageName = firImport.isAllUnder() ?
                    firImport.getImportedFqName().asString() + ".*" :
                    firImport.getImportedFqName().asString();
            qualid = TypeTree.build(packageName).withPrefix(space);
        }
        return new J.Import(randomId(), prefix, Markers.EMPTY, statik, qualid);
    }

    @Override
    public J visitPackageDirective(FirPackageDirective packageDirective, ExecutionContext ctx) {
        Space pkgPrefix = whitespace();
        cursor += "package".length();
        Space space = whitespace();

        return new J.Package(
                randomId(),
                pkgPrefix,
                Markers.EMPTY,
                TypeTree.build(packageDirective.getPackageFqName().asString())
                        .withPrefix(space),
                emptyList());
    }

    @Override
    public J visitProperty(FirProperty property, ExecutionContext ctx) {
        Space prefix = whitespace();

        List<J> modifiers = emptyList();
        if (property.getSource() != null) {
            PsiChildRange psiChildRange = PsiUtilsKt.getAllChildren(((KtRealPsiSourceElement) property.getSource()).getPsi());
            if (psiChildRange.getFirst() instanceof KtDeclarationModifierList) {
                KtDeclarationModifierList modifierList = (KtDeclarationModifierList) psiChildRange.getFirst();
                modifiers = getModifiers(modifierList);
            }
        }

        List<J.Annotation> annotations = emptyList(); // TODO: the last annotations in modifiers should be added.

        boolean isVal = property.isVal();
        PropertyClassifier propertyClassifier = new PropertyClassifier(randomId(), isVal ? sourceBefore("val") : sourceBefore("var"), isVal ? VAL : VAR);

        List<JRightPadded<J.VariableDeclarations.NamedVariable>> vars = new ArrayList<>(1); // adjust size if necessary
        Space namePrefix = sourceBefore(property.getName().asString());

        J.Identifier name = new J.Identifier(
                randomId(),
                EMPTY,
                Markers.EMPTY,
                property.getName().asString(),
                null, // TODO: add type mapping and set type
                null); // TODO: add type mapping and set variable type

        TypeTree typeExpression = null;
        if (property.getReturnTypeRef() instanceof FirResolvedTypeRef) {
            FirResolvedTypeRef typeRef = (FirResolvedTypeRef) property.getReturnTypeRef();
            if (typeRef.getDelegatedTypeRef() != null) {
                typeExpression = (TypeTree) visitElement(typeRef.getDelegatedTypeRef(), ctx);
            }
        } else {
            throw new IllegalStateException("Implement me.");
        }

        // Dimensions do not exist in Kotlin, and array is declared based on the type. I.E., IntArray
        List<JLeftPadded<Space>> dimensionsAfterName = emptyList();

        JRightPadded<J.VariableDeclarations.NamedVariable> namedVariable = maybeSemicolon(
                new J.VariableDeclarations.NamedVariable(
                        randomId(),
                        namePrefix,
                        Markers.EMPTY,
                        name,
                        dimensionsAfterName,
                        property.getInitializer() != null ? padLeft(sourceBefore("="), (Expression) visitExpression(property.getInitializer(), ctx)) : null,
                        null // TODO: add type mapping
                )
        );
        vars.add(namedVariable);

        return new J.VariableDeclarations(
                randomId(),
                prefix,
                Markers.EMPTY.addIfAbsent(propertyClassifier),
                emptyList(),
                emptyList(), // TODO: requires updates to handle kotlin specific modifiers.
                typeExpression,
                null,
                dimensionsAfterName,
                vars);
    }

    @Override
    public J visitResolvedTypeRef(FirResolvedTypeRef resolvedTypeRef, ExecutionContext ctx) {
        String name = ((KtRealPsiSourceElement) resolvedTypeRef.getSource()).getPsi().getText();
        Space prefix = sourceBefore(name);

        JavaType type = null; // TODO: add type mapping. Note: typeRef does not contain a reference to the symbol. The symbol exists on the FIR element.
        return new J.Identifier(randomId(),
                prefix,
                Markers.EMPTY,
                name,
                type,
                null);
    }

    @Override
    public J visitSimpleFunction(FirSimpleFunction simpleFunction, ExecutionContext ctx) {
        Space prefix = whitespace();
        // Not used until it's possible to handle K.Modifiers.
        List<J> modifiers = emptyList();
        if (simpleFunction.getSource() != null) {
            PsiChildRange psiChildRange = PsiUtilsKt.getAllChildren(((KtRealPsiSourceElement) simpleFunction.getSource()).getPsi());
            if (psiChildRange.getFirst() instanceof KtDeclarationModifierList) {
                KtDeclarationModifierList modifierList = (KtDeclarationModifierList) psiChildRange.getFirst();
                modifiers = getModifiers(modifierList);
            }
        }

        List<J.Annotation> kindAnnotations = emptyList(); // TODO: the last annotations in modifiersAndAnnotations should be added to the fun.

        MethodClassifier methodClassifier = new MethodClassifier(randomId(), sourceBefore("fun"));

        J.Identifier name = new J.Identifier(
                randomId(),
                sourceBefore(simpleFunction.getName().asString()),
                Markers.EMPTY,
                simpleFunction.getName().asString(),
                null,
                null);

        JContainer<Statement> params = JContainer.empty();
        Space paramFmt = sourceBefore("(");
        params = !simpleFunction.getValueParameters().isEmpty() ?
                JContainer.build(paramFmt, convertAll(simpleFunction.getValueParameters(), commaDelim, t -> sourceBefore(")"), ctx), Markers.EMPTY) :
                JContainer.build(paramFmt, singletonList(padRight(new J.Empty(randomId(), sourceBefore(")"), Markers.EMPTY), EMPTY)), Markers.EMPTY);

        J.Block body = convertOrNull(simpleFunction.getBody(), ctx);

        return new J.MethodDeclaration(
                randomId(),
                prefix,
                Markers.EMPTY.addIfAbsent(methodClassifier),
                emptyList(), // TODO
                emptyList(), // TODO
                null, // TODO
                null, // TODO
                new J.MethodDeclaration.IdentifierWithAnnotations(name, emptyList()),
                params, // TODO
                null, // TODO
                body,
                null,
                null); // TODO
    }

    @Override
    public J visitTypeParameter(FirTypeParameter typeParameter, ExecutionContext ctx) {
        if (!typeParameter.getAnnotations().isEmpty()) {
            throw new IllegalStateException("Implement me.");
        }

        Space prefix = whitespace();
        List<J.Annotation> annotations = emptyList();

        Expression name = buildName(typeParameter.getName().asString())
                .withPrefix(sourceBefore(typeParameter.getName().asString()));

        // TODO: add support for bounds. Bounds often exist regardless of if bounds are specified.
        JContainer<TypeTree> bounds = JContainer.empty();
//        JContainer<TypeTree> bounds = typeParameter.getBounds().isEmpty() ? null :
//                JContainer.build(whitespace(),
//                        convertAll(typeParameter.getBounds(), t -> sourceBefore(","), noDelim, ctx), Markers.EMPTY);

        return new J.TypeParameter(randomId(), prefix, Markers.EMPTY, annotations, name, bounds);
    }

    @Override
    public J visitTypeProjectionWithVariance(FirTypeProjectionWithVariance typeProjectionWithVariance, ExecutionContext ctx) {
        // TODO: Temp. sort out how type references work and why FirTypeProjectionWithVariance contain variance even when not specified in code. I.E., Int.
        return visitResolvedTypeRef((FirResolvedTypeRef) typeProjectionWithVariance.getTypeRef(), ctx);
    }

    @Override
    public J visitUserTypeRef(FirUserTypeRef userTypeRef, ExecutionContext data) {
        sourceBefore(":"); // increment passed the ":"

        JavaType type = null; // TODO: add type mapping. Note: typeRef does not contain a reference to the symbol. The symbol exists on the FIR element.
        if (userTypeRef.getQualifier().size() == 1) {
            FirQualifierPart part = userTypeRef.getQualifier().get(0);
            Space prefix = sourceBefore(part.getName().asString());
            J.Identifier ident = new J.Identifier(randomId(),
                    EMPTY,
                    Markers.EMPTY,
                    part.getName().asString(),
                    type,
                    null);
            if (!part.getTypeArgumentList().getTypeArguments().isEmpty()) {
                List<JRightPadded<Expression>> parameters = new ArrayList<>(part.getTypeArgumentList().getTypeArguments().size());
                for (FirTypeProjection typeArgument : part.getTypeArgumentList().getTypeArguments()) {
                    parameters.add(JRightPadded.build((Expression) visitElement(typeArgument, ctx)));
                }

                return new J.ParameterizedType(
                        randomId(),
                        prefix,
                        Markers.EMPTY,
                        ident,
                        JContainer.build(parameters)
                );
            } else {
                return ident.withPrefix(prefix);
            }
        } else {
            throw new IllegalStateException("Implement me.");
        }
    }

    @Override
    public J visitValueParameter(FirValueParameter valueParameter, ExecutionContext ctx) {
        throw new IllegalStateException("Implement me.");
    }

    /**
     *
     * @param firElement
     * @param ctx
     * @return
     */
    @Override
    public J visitElement(FirElement firElement, ExecutionContext ctx) {
        if (firElement instanceof FirBlock) {
            return visitBlock((FirBlock) firElement, ctx);
        }  else if (firElement instanceof FirClass) {
            return visitClass((FirClass) firElement, ctx);
        } else if (firElement instanceof FirConstExpression) {
            return visitConstExpression((FirConstExpression<? extends Object>) firElement, ctx);
        } else if (firElement instanceof FirFunctionCall) {
            return visitFunctionCall((FirFunctionCall) firElement, ctx);
        } else if (firElement instanceof FirImplicitNullableAnyTypeRef) {
            return visitResolvedTypeRef((FirResolvedTypeRef) firElement, ctx);
        } else if (firElement instanceof FirSimpleFunction) {
            return visitSimpleFunction((FirSimpleFunction) firElement, ctx);
        } else if (firElement instanceof FirTypeParameter) {
            return visitTypeParameter((FirTypeParameter) firElement, ctx);
        } else if (firElement instanceof FirTypeProjectionWithVariance) {
            return visitTypeProjectionWithVariance((FirTypeProjectionWithVariance) firElement, ctx);
        } else if (firElement instanceof FirUserTypeRef) {
            return visitUserTypeRef((FirUserTypeRef) firElement, ctx);
        } else if (firElement instanceof FirValueParameter) {
            return visitValueParameter((FirValueParameter) firElement, ctx);
        } else {
            throw new IllegalStateException("Implement me.");
        }
    }

    private final Function<FirElement, Space> commaDelim = ignored -> sourceBefore(",");
    private final Function<FirElement, Space> noDelim = ignored -> EMPTY;

    private String skip(@Nullable String token) {
        if (token == null) {
            //noinspection ConstantConditions
            return null;
        }
        if (source.startsWith(token, cursor)) {
            cursor += token.length();
        }
        return token;
    }

    private void cursor(int n) {
        cursor = n;
    }

    private int endPos(FirElement t) {
        if (t.getSource() == null) {
            throw new IllegalStateException("Unexpected null source ... fix me.");
        }
        return t.getSource().getEndOffset();
    }

    private <T extends TypeTree & Expression> T buildName(String fullyQualifiedName) {
        String[] parts = fullyQualifiedName.split("\\.");

        String fullName = "";
        Expression expr = null;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == 0) {
                fullName = part;
                expr = new J.Identifier(randomId(), EMPTY, Markers.EMPTY, part, null, null);
            } else {
                fullName += "." + part;

                int endOfPrefix = indexOfNextNonWhitespace(0, part);
                Space identFmt = endOfPrefix > 0 ? format(part.substring(0, endOfPrefix)) : EMPTY;

                Matcher whitespaceSuffix = whitespaceSuffixPattern.matcher(part);
                //noinspection ResultOfMethodCallIgnored
                whitespaceSuffix.matches();
                Space namePrefix = i == parts.length - 1 ? Space.EMPTY : format(whitespaceSuffix.group(1));

                expr = new J.FieldAccess(
                        randomId(),
                        EMPTY,
                        Markers.EMPTY,
                        expr,
                        padLeft(namePrefix, new J.Identifier(randomId(), identFmt, Markers.EMPTY, part.trim(), null, null)),
                        (Character.isUpperCase(part.charAt(0)) || i == parts.length - 1) ?
                                JavaType.ShallowClass.build(fullName) :
                                null
                );
            }
        }

        //noinspection unchecked,ConstantConditions
        return (T) expr;
    }

    private <J2 extends J> J2 convert(FirElement t, ExecutionContext ctx) {
        return (J2) visitElement(t, ctx);
    }

    private <J2 extends J> JRightPadded<J2> convert(FirElement t, Function<FirElement, Space> suffix, ExecutionContext ctx) {
        J2 j = (J2) visitElement(t, ctx);
        @SuppressWarnings("ConstantConditions") JRightPadded<J2> rightPadded = j == null ? null :
                new JRightPadded<>(j, suffix.apply(t), Markers.EMPTY);
        cursor(max(endPos(t), cursor)); // if there is a non-empty suffix, the cursor may have already moved past it
        return rightPadded;
    }

    @Nullable
    private <T extends J> T convertOrNull(@Nullable FirElement t, ExecutionContext ctx) {
        return t == null ? null : convert(t, ctx);
    }

    @Nullable
    private <J2 extends J> JRightPadded<J2> convertOrNull(@Nullable FirElement t, Function<FirElement, Space> suffix, ExecutionContext ctx) {
        return t == null ? null : convert(t, suffix, ctx);
    }

    private <J2 extends J> List<JRightPadded<J2>> convertAll(List<? extends FirElement> elements,
                                                             Function<FirElement, Space> innerSuffix,
                                                             Function<FirElement, Space> suffix,
                                                             ExecutionContext ctx) {
        if (elements.isEmpty()) {
            return emptyList();
        }
        List<JRightPadded<J2>> converted = new ArrayList<>(elements.size());
        for (int i = 0; i < elements.size(); i++) {
            converted.add(convert(elements.get(i), i == elements.size() - 1 ? suffix : innerSuffix, ctx));
        }
        return converted;
    }

    private <K2 extends J> JRightPadded<K2> maybeSemicolon(K2 k) {
        int saveCursor = cursor;
        Space beforeSemi = whitespace();
        Semicolon semicolon = null;
        if (cursor < source.length() && source.charAt(cursor) == ';') {
            semicolon = new Semicolon(randomId());
            cursor++;
        } else {
            beforeSemi = EMPTY;
            cursor = saveCursor;
        }

        JRightPadded<K2> padded = JRightPadded.build(k).withAfter(beforeSemi);
        if (semicolon != null) {
            padded = padded.withMarkers(padded.getMarkers().add(semicolon));
        }

        return padded;
    }

    private <T> JLeftPadded<T> padLeft(Space left, T tree) {
        return new JLeftPadded<>(left, tree, Markers.EMPTY);
    }

    private <T> JRightPadded<T> padRight(T tree, Space right) {
        return new JRightPadded<>(tree, right, Markers.EMPTY);
    }

    // TODO: parse comments.
    private List<J> getModifiers(KtDeclarationModifierList modifierList) {
        List<J> modifiers = new ArrayList<>();
        PsiElement current = modifierList.getFirstChild();
        List<J.Annotation> annotations = new ArrayList<>();
        while (current != null) {
            IElementType elementType = current.getNode().getElementType();
            if (elementType instanceof KtModifierKeywordToken) {
                KtModifierKeywordToken token = (KtModifierKeywordToken) elementType;
                K.Modifier modifier = mapModifier(token, annotations);
                annotations = new ArrayList<>();
                modifiers.add(modifier);
            } else if (elementType instanceof KtAnnotationEntryElementType) {
                ASTNode astNode = current.getNode();
                if (astNode instanceof CompositeElement) {
                    J.Annotation annotation = mapAnnotation((CompositeElement) astNode);
                    annotations.add(annotation);
                } else {
                    throw new IllegalStateException("Implement me.");
                }
            }
            current = current.getNextSibling();
        }
        modifiers.addAll(annotations);
        return modifiers;
    }

    // TODO: parse annotation composite and create J.Annotation.
    private J.Annotation mapAnnotation(CompositeElement compositeElement) {
        Space prefix = whitespace();
        return new J.Annotation(randomId(), prefix, Markers.EMPTY, null, JContainer.empty());
    }

    // TODO: confirm this works for all types of kotlin modifiers.
    private K.Modifier mapModifier(KtModifierKeywordToken mod, List<J.Annotation> annotations) {
        Space modFormat = whitespace();
        cursor += mod.getValue().length();
        K.Modifier.Type type;
        // Ordered based on kotlin requirements.
        switch (mod.getValue()) {
            case "public":
                type = K.Modifier.Type.Public;
                break;
            case "protected":
                type = K.Modifier.Type.Protected;
                break;
            case "private":
                type = K.Modifier.Type.Private;
                break;
            case "internal":
                type = K.Modifier.Type.Internal;
                break;
            case "expect":
                type = K.Modifier.Type.Expect;
                break;
            case "actual":
                type = K.Modifier.Type.Actual;
                break;
            case "final":
                type = K.Modifier.Type.Final;
                break;
            case "open":
                type = K.Modifier.Type.Open;
                break;
            case "abstract":
                type = K.Modifier.Type.Abstract;
                break;
            case "sealed":
                type = K.Modifier.Type.Sealed;
                break;
            case "const":
                type = K.Modifier.Type.Const;
                break;
            case "external":
                type = K.Modifier.Type.External;
                break;
            case "override":
                type = K.Modifier.Type.Override;
                break;
            case "lateinit":
                type = K.Modifier.Type.LateInit;
                break;
            case "tailrec":
                type = K.Modifier.Type.TailRec;
                break;
            case "vararg":
                type = K.Modifier.Type.Vararg;
                break;
            case "suspend":
                type = K.Modifier.Type.Suspend;
                break;
            case "inner":
                type = K.Modifier.Type.Inner;
                break;
            case "enum":
                type = K.Modifier.Type.Enum;
                break;
            case "annotation":
                type = K.Modifier.Type.Annotation;
                break;
            case "fun":
                type = K.Modifier.Type.Fun;
                break;
            case "companion":
                type = K.Modifier.Type.Companion;
                break;
            case "inline":
                type = K.Modifier.Type.Inline;
                break;
            case "value":
                type = K.Modifier.Type.Value;
                break;
            case "infix":
                type = K.Modifier.Type.Infix;
                break;
            case "operator":
                type = K.Modifier.Type.Operator;
                break;
            case "data":
                type = K.Modifier.Type.Data;
                break;
            default:
                throw new IllegalArgumentException("Unexpected modifier " + mod);
        }
        return new K.Modifier(randomId(), modFormat, Markers.EMPTY, type, annotations);
    }

    private int positionOfNext(String untilDelim) {
        boolean inMultiLineComment = false;
        boolean inSingleLineComment = false;

        int delimIndex = cursor;
        for (; delimIndex < source.length() - untilDelim.length() + 1; delimIndex++) {
            if (inSingleLineComment) {
                if (source.charAt(delimIndex) == '\n') {
                    inSingleLineComment = false;
                }
            } else {
                if (source.length() - untilDelim.length() > delimIndex + 1) {
                    switch (source.substring(delimIndex, delimIndex + 2)) {
                        case "//":
                            inSingleLineComment = true;
                            delimIndex++;
                            break;
                        case "/*":
                            inMultiLineComment = true;
                            delimIndex++;
                            break;
                        case "*/":
                            inMultiLineComment = false;
                            delimIndex = delimIndex + 2;
                            break;
                    }
                }

                if (!inMultiLineComment && !inSingleLineComment) {
                    if (source.startsWith(untilDelim, delimIndex)) {
                        break; // found it!
                    }
                }
            }
        }

        return delimIndex > source.length() - untilDelim.length() ? -1 : delimIndex;
    }

    private Space sourceBefore(String untilDelim) {
        int delimIndex = positionOfNext(untilDelim);
        if (delimIndex < 0) {
            return EMPTY; // unable to find this delimiter
        }

        String prefix = source.substring(cursor, delimIndex);
        cursor += prefix.length() + untilDelim.length(); // advance past the delimiter
        return Space.format(prefix);
    }

    private Space whitespace() {
        String prefix = source.substring(cursor, indexOfNextNonWhitespace(cursor, source));
        cursor += prefix.length();
        return format(prefix);
    }
}
