package org.evolizer.changedistiller.ast.java;

import java.io.File;
import java.util.List;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope;
import org.evolizer.changedistiller.ast.ASTHelper;
import org.evolizer.changedistiller.model.classifiers.ChangeModifier;
import org.evolizer.changedistiller.model.classifiers.EntityType;
import org.evolizer.changedistiller.model.classifiers.SourceRange;
import org.evolizer.changedistiller.model.entities.AttributeHistory;
import org.evolizer.changedistiller.model.entities.ClassHistory;
import org.evolizer.changedistiller.model.entities.MethodHistory;
import org.evolizer.changedistiller.model.entities.SourceCodeEntity;
import org.evolizer.changedistiller.model.entities.StructureEntityVersion;
import org.evolizer.changedistiller.structuredifferencing.java.JavaStructureNode;
import org.evolizer.changedistiller.structuredifferencing.java.JavaStructureNode.Type;
import org.evolizer.changedistiller.structuredifferencing.java.JavaStructureTreeBuilder;
import org.evolizer.changedistiller.treedifferencing.Node;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

/**
 * Implementation of {@link ASTHelper} for the Java programming language.
 * 
 * @author Beat Fluri,
 * @author Giacomo Ghezzi
 */
public class JavaASTHelper implements ASTHelper<JavaStructureNode> {

    private JavaDeclarationConverter fDeclarationConverter;
    private JavaMethodBodyConverter fBodyConverter;
    private JavaASTNodeTypeConverter fASTHelper;
    private JavaCompilation fCompilation;
    private List<Comment> fComments;

    @Inject
    JavaASTHelper(
            @Assisted File file,
            JavaASTNodeTypeConverter astHelper,
            JavaDeclarationConverter declarationConverter,
            JavaMethodBodyConverter bodyConverter) {
        fCompilation = JavaCompilationUtils.compile(file);
        prepareComments();
        fASTHelper = astHelper;
        fDeclarationConverter = declarationConverter;
        fBodyConverter = bodyConverter;
    }

    private void prepareComments() {
        cleanComments(collectComments());
    }

    private void cleanComments(List<Comment> comments) {
        CommentCleaner visitor = new CommentCleaner(fCompilation.getSource());
        for (Comment comment : comments) {
            visitor.process(comment);
        }
        fComments = visitor.getComments();
    }

    private List<Comment> collectComments() {
        CommentCollector collector = new CommentCollector(fCompilation.getCompilationUnit(), fCompilation.getSource());
        collector.collect();
        return collector.getComments();
    }

    @Override
    public Node createDeclarationTree(JavaStructureNode node) {
        ASTNode astNode = node.getASTNode();
        Node root = createRootNode(node, astNode);
        return createDeclarationTree(astNode, root);
    }

    private Node createDeclarationTree(ASTNode astNode, Node root) {
        fDeclarationConverter.initialize(root, fCompilation.getScanner());
        if (astNode instanceof TypeDeclaration) {
            ((TypeDeclaration) astNode).traverse(fDeclarationConverter, (ClassScope) null);
        } else if (astNode instanceof AbstractMethodDeclaration) {
            ((AbstractMethodDeclaration) astNode).traverse(fDeclarationConverter, (ClassScope) null);
        } else if (astNode instanceof FieldDeclaration) {
            ((FieldDeclaration) astNode).traverse(fDeclarationConverter, null);
        }
        return root;
    }

    @Override
    public Node createDeclarationTree(JavaStructureNode node, String qualifiedName) {
        ASTNode astNode = node.getASTNode();
        Node root = createRootNode(node, astNode);
        root.setValue(qualifiedName);
        return createDeclarationTree(astNode, root);
    }

    private Node createRootNode(JavaStructureNode node, ASTNode astNode) {
        Node root = new Node(fASTHelper.convertNode(astNode), node.getFullyQualifiedName());
        root.setEntity(createSourceCodeEntity(node));
        return root;
    }

    @Override
    public Node createMethodBodyTree(JavaStructureNode node) {
        ASTNode astNode = node.getASTNode();
        if (astNode instanceof AbstractMethodDeclaration) {
            Node root = createRootNode(node, astNode);
            fBodyConverter.initialize(root, astNode, fComments, fCompilation.getScanner());
            ((AbstractMethodDeclaration) astNode).traverse(fBodyConverter, (ClassScope) null);
            return root;
        }
        return null;
    }

    @Override
    public JavaStructureNode createStructureTree() {
        CompilationUnitDeclaration cu = fCompilation.getCompilationUnit();
        JavaStructureNode node = new JavaStructureNode(Type.CU, null, null, cu);
        cu.traverse(new JavaStructureTreeBuilder(node), (CompilationUnitScope) null);
        return node;
    }

    @Override
    public EntityType convertType(JavaStructureNode node) {
        return fASTHelper.convertNode(node.getASTNode());
    }

    @Override
    public SourceCodeEntity createSourceCodeEntity(JavaStructureNode node) {
        return new SourceCodeEntity(
                node.getFullyQualifiedName(),
                convertType(node),
                extractModifier(node.getASTNode()),
                createSourceRange(node.getASTNode()));
    }

    private SourceRange createSourceRange(ASTNode astNode) {
        if (astNode instanceof TypeDeclaration) {
            TypeDeclaration type = (TypeDeclaration) astNode;
            return new SourceRange(type.declarationSourceStart, type.declarationSourceEnd);
        }
        if (astNode instanceof AbstractMethodDeclaration) {
            AbstractMethodDeclaration method = (AbstractMethodDeclaration) astNode;
            return new SourceRange(method.declarationSourceStart, method.declarationSourceEnd);
        }
        if (astNode instanceof FieldDeclaration) {
            FieldDeclaration field = (FieldDeclaration) astNode;
            return new SourceRange(field.declarationSourceStart, field.declarationSourceEnd);
        }
        return new SourceRange(astNode.sourceStart(), astNode.sourceEnd());
    }

    @Override
    public StructureEntityVersion createStructureEntityVersion(JavaStructureNode node, String versionNum) {
        return new StructureEntityVersion(
                convertType(node),
                node.getFullyQualifiedName(),
                extractModifier(node.getASTNode()),
                versionNum);
    }
    
    @Override
    public StructureEntityVersion createStructureEntityVersion(JavaStructureNode node) {
        return new StructureEntityVersion(
                convertType(node),
                node.getFullyQualifiedName(),
                extractModifier(node.getASTNode()));
    }

    private int extractModifier(ASTNode node) {
        int ecjModifer = -1;
        if (node instanceof AbstractMethodDeclaration) {
            ecjModifer = ((AbstractMethodDeclaration) node).modifiers;
        } else if (node instanceof FieldDeclaration) {
            ecjModifer = ((FieldDeclaration) node).modifiers;
        } else if (node instanceof TypeDeclaration) {
            ecjModifer = ((TypeDeclaration) node).modifiers;
        }
        if (ecjModifer > -1) {
            return convertECJModifier(ecjModifer);
        }
        return 0;
    }

    private int convertECJModifier(int ecjModifer) {
        int modifier = 0x0;
        if (isFinal(ecjModifer)) {
            modifier |= ChangeModifier.FINAL;
        }
        if (isPublic(ecjModifer)) {
            modifier |= ChangeModifier.PUBLIC;
        }
        if (isProtected(ecjModifer)) {
            modifier |= ChangeModifier.PROTECTED;
        }
        if (isPrivate(ecjModifer)) {
            modifier |= ChangeModifier.PRIVATE;
        }
        return modifier;
    }

    private boolean isPrivate(int ecjModifier) {
        return (ecjModifier & ClassFileConstants.AccPrivate) != 0;
    }

    private boolean isProtected(int ecjModifier) {
        return (ecjModifier & ClassFileConstants.AccProtected) != 0;
    }

    private boolean isPublic(int ecjModifier) {
        return (ecjModifier & ClassFileConstants.AccPublic) != 0;
    }

    private boolean isFinal(int ecjModifier) {
        return (ecjModifier & ClassFileConstants.AccFinal) != 0;
    }

    @Override
    public StructureEntityVersion createMethodInClassHistory(ClassHistory classHistory, JavaStructureNode node, String versionNum) {
        MethodHistory mh;
        StructureEntityVersion method = createStructureEntityVersion(node, versionNum);
        if (classHistory.getMethodHistories().containsKey(method.getUniqueName())) {
            mh = classHistory.getMethodHistories().get(method.getUniqueName());
            mh.addVersion(method);
        } else {
            mh = new MethodHistory(method);
            classHistory.getMethodHistories().put(method.getUniqueName(), mh);
        }
        return method;
    }

    @Override
    public StructureEntityVersion createMethodInClassHistory(ClassHistory classHistory, JavaStructureNode node) {
        MethodHistory mh;
        StructureEntityVersion method = createStructureEntityVersion(node);
        if (classHistory.getMethodHistories().containsKey(method.getUniqueName())) {
            mh = classHistory.getMethodHistories().get(method.getUniqueName());
            mh.addVersion(method);
        } else {
            mh = new MethodHistory(method);
            classHistory.getMethodHistories().put(method.getUniqueName(), mh);
        }
        return method;
    }
    
    @Override
    public StructureEntityVersion createFieldInClassHistory(ClassHistory classHistory, JavaStructureNode node, String versionNum) {
        AttributeHistory ah = null;
        StructureEntityVersion attribute = createStructureEntityVersion(node, versionNum);
        if (classHistory.getAttributeHistories().containsKey(attribute.getUniqueName())) {
            ah = classHistory.getAttributeHistories().get(attribute.getUniqueName());
            ah.addVersion(attribute);
        } else {
            ah = new AttributeHistory(attribute);
            classHistory.getAttributeHistories().put(attribute.getUniqueName(), ah);
        }
        return attribute;

    }
    
    @Override
    public StructureEntityVersion createFieldInClassHistory(ClassHistory classHistory, JavaStructureNode node) {
        AttributeHistory ah = null;
        StructureEntityVersion attribute = createStructureEntityVersion(node);
        if (classHistory.getAttributeHistories().containsKey(attribute.getUniqueName())) {
            ah = classHistory.getAttributeHistories().get(attribute.getUniqueName());
            ah.addVersion(attribute);
        } else {
            ah = new AttributeHistory(attribute);
            classHistory.getAttributeHistories().put(attribute.getUniqueName(), ah);
        }
        return attribute;

    }

    @Override
    public StructureEntityVersion createInnerClassInClassHistory(ClassHistory classHistory, JavaStructureNode node, String versionNum) {
        ClassHistory ch = null;
        StructureEntityVersion clazz = createStructureEntityVersion(node, versionNum);
        if (classHistory.getInnerClassHistories().containsKey(clazz.getUniqueName())) {
            ch = classHistory.getInnerClassHistories().get(clazz.getUniqueName());
            ch.addVersion(clazz);
        } else {
            ch = new ClassHistory(clazz);
            classHistory.getInnerClassHistories().put(clazz.getUniqueName(), ch);
        }
        return clazz;

    }
    
    @Override
    public StructureEntityVersion createInnerClassInClassHistory(ClassHistory classHistory, JavaStructureNode node) {
        ClassHistory ch = null;
        StructureEntityVersion clazz = createStructureEntityVersion(node);
        if (classHistory.getInnerClassHistories().containsKey(clazz.getUniqueName())) {
            ch = classHistory.getInnerClassHistories().get(clazz.getUniqueName());
            ch.addVersion(clazz);
        } else {
            ch = new ClassHistory(clazz);
            classHistory.getInnerClassHistories().put(clazz.getUniqueName(), ch);
        }
        return clazz;

    }

}
