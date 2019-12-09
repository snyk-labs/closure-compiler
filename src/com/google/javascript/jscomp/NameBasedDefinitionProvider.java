/*
 * Copyright 2016 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.common.collect.*;
import com.google.javascript.jscomp.DefinitionsRemover.Definition;
import com.google.javascript.jscomp.DefinitionsRemover.ExternalNameOnlyDefinition;
import com.google.javascript.jscomp.DefinitionsRemover.UnknownDefinition;
import com.google.javascript.jscomp.NodeTraversal.Callback;
import com.google.javascript.jscomp.NodeTraversal.ChangeScopeRootCallback;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;

import javax.annotation.Nullable;
import java.util.*;

import static com.google.common.base.Preconditions.*;

/**
 * Simple name-based definition gatherer.
 *
 * <p>It treats all variable writes as happening in the global scope and treats all objects as
 * capable of having the same set of properties. The current implementation only handles definitions
 * whose right hand side is an immutable value or function expression. All complex definitions are
 * treated as unknowns.
 *
 * <p>This definition simply uses the variable name to determine a new definition site so
 * potentially it could return multiple definition sites for a single variable. Although we could
 * use the type system to make this more accurate, in practice after disambiguate properties has
 * run, names are unique enough that this works well enough to accept the performance gain.
 */
public class NameBasedDefinitionProvider implements CompilerPass {

  protected final AbstractCompiler compiler;

  protected final Multimap<String, Definition> definitionsByName;
  protected final Map<Node, DefinitionSite> definitionSitesByDefinitionSiteNode;
  protected final Multimap<Node, DefinitionSite> definitionSitesByScopeNode;
  protected final Set<Node> definitionNodes;

  protected final boolean allowComplexFunctionDefs;
  protected boolean hasProcessBeenRun = false;

  public NameBasedDefinitionProvider(AbstractCompiler compiler, boolean allowComplexFunctionDefs) {
    this.compiler = compiler;
    this.allowComplexFunctionDefs = allowComplexFunctionDefs;
    int numInputs = compiler.getNumberOfInputs();
    // Estimates below were generated by experimentation with large Google projects.
    this.definitionsByName = LinkedHashMultimap.create(numInputs * 15, 1);
    int estimatedDefinitionSites = numInputs * 22;
    this.definitionSitesByDefinitionSiteNode =
        Maps.newLinkedHashMapWithExpectedSize(estimatedDefinitionSites);
    this.definitionSitesByScopeNode = HashMultimap.create(estimatedDefinitionSites, 1);
    this.definitionNodes = Sets.newHashSetWithExpectedSize(estimatedDefinitionSites);
  }

  @Override
  public void process(Node externs, Node source) {
    checkState(!hasProcessBeenRun, "The definition provider is already initialized.");

    this.hasProcessBeenRun = true;

    NodeTraversal.traverse(compiler, externs, new DefinitionGatheringCallback(true));
    dropUntypedExterns();

    NodeTraversal.traverse(compiler, source, new DefinitionGatheringCallback(false));
  }

  public void rebuildScopeRoots(List<Node> changedScopeRoots, List<Node> deletedScopeRoots) {
    for (Node scopeRoot : Iterables.concat(deletedScopeRoots, changedScopeRoots)) {
      for (DefinitionSite definitionSite : definitionSitesByScopeNode.removeAll(scopeRoot)) {
        Definition definition = definitionSite.definition;
        definitionNodes.remove(definitionSite.node);
        definitionsByName.remove(definition.getSimplifiedName(), definition);
        definitionSitesByDefinitionSiteNode.remove(definitionSite.node);
      }
    }

    DefinitionGatheringCallback cb = new DefinitionGatheringCallback();
    NodeTraversal.traverseScopeRoots(compiler, null, changedScopeRoots, cb, cb, false);
  }

  /** @return Whether the node has a JSDoc that actually declares something. */
  private boolean jsdocContainsDeclarations(Node node) {
    JSDocInfo info = node.getJSDocInfo();
    return (info != null && info.containsDeclaration());
  }

  /**
   * Drop untyped stub definitions (ExternalNameOnlyDefinition) in externs if a typed extern of the
   * same qualified name also exists and has type annotations.
   *
   * <p>TODO: This hack is mostly for the purpose of preventing untyped stubs from showing up in the
   * {@link PureFunctionIdentifier} and causing unknown side effects from propagating everywhere.
   * This should probably be solved in one of the following ways instead:
   *
   * <p>a) Have a pass earlier in the compiler that goes in and removes these stub definitions.
   *
   * <p>b) Fix all extern files so that there are no untyped stubs mixed with typed ones and add a
   * restriction to the compiler to prevent this.
   *
   * <p>c) Drop these stubs in the {@link PureFunctionIdentifier} instead. This "DefinitionProvider"
   * should not have to drop definitions itself.
   */
  private void dropUntypedExterns() {
    for (String name : definitionsByName.keySet()) {
      for (Definition definition : new ArrayList<>(definitionsByName.get(name))) {
        if (!(definition instanceof ExternalNameOnlyDefinition)) {
          continue;
        }
        Node definitionNode = definition.getLValue();
        if (jsdocContainsDeclarations(definitionNode)) {
          continue;
        }

        for (Definition previousDefinition : definitionsByName.get(name)) {
          if (previousDefinition != definition
              && definitionNode.matchesQualifiedName(previousDefinition.getLValue())) {
            // *DON'T* remove from definitionNodes since it is desired to retain references to
            // stub definitions.
            definitionsByName.remove(name, definition);
            DefinitionSite definitionSite =
                definitionSitesByDefinitionSiteNode.remove(definitionNode);
            Node scopeNode = NodeUtil.getEnclosingChangeScopeRoot(definitionNode);
            definitionSitesByScopeNode.remove(scopeNode, definitionSite);

            // Since it's a stub we know its keyed by the name/getProp node.
            checkNotNull(definitionSite);
            break;
          }
        }
      }
    }
  }

  /**
   * Returns a collection of definitions that characterize the possible values of a variable or
   * property.
   */
  public Collection<Definition> getDefinitionsReferencedAt(Node useSiteNode) {
    checkState(hasProcessBeenRun, "Hasn't been initialized with process() yet.");
    checkArgument(useSiteNode.isGetProp() || useSiteNode.isName(), useSiteNode);

    if (definitionNodes.contains(useSiteNode)) {
      return ImmutableList.of();
    }

    if (useSiteNode.isGetProp()) {
      String propName = useSiteNode.getLastChild().getString();
      if (propName.equals("apply") || propName.equals("call")) {
        useSiteNode = useSiteNode.getFirstChild();
      }
    }

    String name = getSimplifiedName(useSiteNode);
    if (name != null) {
      return definitionsByName.get(name);
    }
    return ImmutableList.of();
  }

  private class DefinitionGatheringCallback implements Callback, ChangeScopeRootCallback {

    DefinitionGatheringCallback() {}

    DefinitionGatheringCallback(boolean inExterns) {
      this.inExterns = inExterns;
    }

    @Override
    public void enterChangeScopeRoot(AbstractCompiler compiler, Node root) {
      this.inExterns = root.isFromExterns();
    }

    private boolean inExterns;

    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
      if (inExterns) {
        if (n.isFunction() && !n.getFirstChild().isName()) {
          // No need to crawl functions in JSDoc
          return false;
        }
        if (parent != null && parent.isFunction() && n != parent.getFirstChild()) {
          // Arguments of external functions should not count as name
          // definitions.  They are placeholder names for documentation
          // purposes only which are not reachable from anywhere.
          return false;
        }
      }
      return true;
    }

    @Override
    public void visit(NodeTraversal traversal, Node node, Node parent) {
      if (inExterns) {
        visitExterns(traversal, node);
      } else {
        visitCode(traversal, node);
      }
    }

    private void visitExterns(NodeTraversal traversal, Node node) {
      if (node.getJSDocInfo() != null) {
        for (Node typeRoot : node.getJSDocInfo().getTypeNodes()) {
          traversal.traverse(typeRoot);
        }
      }

      Definition definition = DefinitionsRemover.getDefinition(node, true);
      if (definition != null) {
        String name = definition.getSimplifiedName();
        if (name != null) {
          Node rValue = definition.getRValue();
          if ((rValue != null) && !NodeUtil.isImmutableValue(rValue) && !rValue.isFunction()) {
            // Unhandled complex expression
            Definition unknownDefinition = new UnknownDefinition(definition.getLValue(), true);
            definition = unknownDefinition;
          }
          addDefinition(name, definition, node, traversal);
        }
      }
    }

    private void visitCode(NodeTraversal traversal, Node node) {
      Definition definition = DefinitionsRemover.getDefinition(node, false);

      if (definition != null) {
        String name = definition.getSimplifiedName();
        if (name != null) {
          Node rValue = definition.getRValue();
          if (rValue != null
              && !NodeUtil.isImmutableValue(rValue)
              && !isKnownFunctionDefinition(rValue)) {
            // Unhandled complex expression
            definition = new UnknownDefinition(definition.getLValue(), false);
          }
          addDefinition(name, definition, node, traversal);
        }
      }
    }

    boolean isKnownFunctionDefinition(Node n) {
      switch (n.getToken()) {
        case FUNCTION:
          return true;
        case HOOK:
          return allowComplexFunctionDefs
              && isKnownFunctionDefinition(n.getSecondChild())
              && isKnownFunctionDefinition(n.getLastChild());
        default:
          return false;
      }
    }
  }

  private void addDefinition(
          String name, Definition definition, Node definitionSiteNode, NodeTraversal traversal) {
    Node definitionNode = definition.getLValue();

    definitionNodes.add(definitionNode);
    definitionsByName.put(name, definition);
    DefinitionSite definitionSite =
        new DefinitionSite(
            definitionSiteNode,
            definition,
            traversal.getModule(),
            traversal.inGlobalScope(),
            definition.isExtern());
    definitionSitesByDefinitionSiteNode.put(definitionSiteNode, definitionSite);
    Node scopeNode = NodeUtil.getEnclosingChangeScopeRoot(definitionSiteNode);
    definitionSitesByScopeNode.put(scopeNode, definitionSite);
  }

  /**
   * Extract a name from a node. In the case of GETPROP nodes, replace the namespace or object
   * expression with "this" for simplicity and correctness at the expense of inefficiencies due to
   * higher chances of name collisions.
   *
   * <p>TODO(user) revisit. it would be helpful to at least use fully qualified names in the case of
   * namespaces. Might not matter as much if this pass runs after {@link CollapseProperties}.
   */
  @Nullable
  public static String getSimplifiedName(Node node) {
    if (node.isName()) {
      String name = node.getString();
      if (name != null && !name.isEmpty()) {
        return name;
      } else {
        return null;
      }
    } else if (node.isGetProp()) {
      return "this." + node.getLastChild().getString();
    } else if (node.isMemberFunctionDef()) {
      return "this." + node.getString();
    }
    return null;
  }

  /**
   * Returns the collection of definition sites found during traversal.
   *
   * @return definition site collection.
   */
  public Collection<DefinitionSite> getDefinitionSites() {
    checkState(hasProcessBeenRun, "Hasn't been initialized with process() yet.");
    return definitionSitesByDefinitionSiteNode.values();
  }

  public DefinitionSite getDefinitionForFunction(Node function) {
    checkState(hasProcessBeenRun, "Hasn't been initialized with process() yet.");
    checkState(function.isFunction());
    return definitionSitesByDefinitionSiteNode.get(NodeUtil.getNameNode(function));
  }
}
