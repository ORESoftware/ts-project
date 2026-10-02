package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.StringLiteral;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.Types.Unknown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static semantic pass run before Oreslang code is lowered/executed. */
public final class TypeChecker {
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.InterfaceDecl> interfaces = new HashMap<>();
    private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
    private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();

    private final IdentityHashMap<Ast.FunctionDecl, String> functionOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, String> classOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.InterfaceDecl, String> interfaceOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, Record> classShapeCache = new IdentityHashMap<>();

    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousInterfaces = new HashSet<>();
    private final Set<String> ambiguousTypeAliases = new HashSet<>();
    private final Set<String> importedValues = new HashSet<>();
    private final Set<Ast.TypeAliasDecl> resolvingAliases = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private static final Set<String> RESERVED_COLLECTION_TYPES = Set.of(
            "Object", "Array", "Arrays", "List", "LinkedList", "Map", "HashMap", "JsonMap");

    public static Ast.Program check(Ast.Program program) {
        TypeChecker checker = new TypeChecker();
        checker.validateImports(program);
        checker.collect(program);
        checker.validateRoutineRecursion();
        checker.validate(program);
        OwnershipChecker.check(program);
        return program;
    }

    private void validateImports(Ast.Program program) {
        Set<String> exposed = new HashSet<>();
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.path() == null || imported.path().isBlank()) throw new IllegalArgumentException("import path cannot be empty");
            if (imported.wildcard()) {
                if (imported.namespace() == null || imported.namespace().isBlank()) throw new IllegalArgumentException("wildcard imports require a namespace alias");
                if (!exposed.add(imported.namespace())) throw new IllegalArgumentException("duplicate imported name '" + imported.namespace() + "'");
                importedValues.add(imported.namespace());
            } else {
                if (imported.names().isEmpty()) throw new IllegalArgumentException("named import must select at least one name");
                for (String name : imported.names()) {
                    if (imported.kind() == Ast.ImportKind.CLASS && RESERVED_COLLECTION_TYPES.contains(name)) {
                        throw new IllegalArgumentException("imported class name '" + name + "' conflicts with an Oreslang core collection type");
                    }
                    if (!exposed.add(name)) throw new IllegalArgumentException("duplicate imported name '" + name + "'");
                    if (imported.kind() != Ast.ImportKind.CLASS) importedValues.add(name);
                }
            }
        }
    }

    private void collect(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            if (modules.putIfAbsent(module.name(), module) != null) throw new IllegalArgumentException("duplicate module '" + module.name() + "'");
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    putQualified(functions, ambiguousFunctions, module.name(), fn.name(), fn, fn.kind() == Ast.CallableKind.ROUTINE ? "routine" : "function");
                    functionOwners.put(fn, module.name());
                } else if (decl instanceof Ast.ClassDecl klass) {
                    rejectReservedCollectionType(klass.name(), "class");
                    putQualified(classes, ambiguousClasses, module.name(), klass.name(), klass, "class");
                    classOwners.put(klass, module.name());
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    rejectReservedCollectionType(iface.name(), "interface");
                    putQualified(interfaces, ambiguousInterfaces, module.name(), iface.name(), iface, "interface");
                    interfaceOwners.put(iface, module.name());
                } else if (decl instanceof Ast.TypeAliasDecl alias) {
                    rejectReservedCollectionType(alias.name(), "type alias");
                    putQualified(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias, "type alias");
                }
            }
        }
    }

    private static void rejectReservedCollectionType(String name, String kind) {
        if (RESERVED_COLLECTION_TYPES.contains(name)) {
            throw new IllegalArgumentException(kind + " name '" + name + "' is reserved by the Oreslang core collection prelude");
        }
    }

    private static <T> void putQualified(Map<String, T> map, Set<String> ambiguous, String module, String name, T value, String kind) {
        String qualified = module + "." + name;
        if (map.putIfAbsent(qualified, value) != null) {
            throw new IllegalArgumentException("duplicate " + kind + " '" + qualified + "'; fnc/routine declarations cannot overload");
        }
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            ambiguous.add(name);
            map.remove(name);
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ClassDecl klass : classOwners.keySet()) classShape(klass, new LinkedHashSet<>());
        for (Ast.InterfaceDecl iface : interfaceOwners.keySet()) interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());

        for (Ast.ModuleDecl module : program.modules()) {
            checkModuleAdherence(module);
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(module.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(module.name(), klass);
                else if (decl instanceof Ast.InterfaceDecl iface) checkInterface(iface);
                else if (decl instanceof Ast.TypeAliasDecl alias) resolve(alias.target(), Set.copyOf(alias.genericParameters()), null);
                else if (decl instanceof Ast.FieldDecl field) checkModuleBinding(field);
            }
        }
    }

    private void checkModuleAdherence(Ast.ModuleDecl module) {
        for (Ast.Annotation annotation : module.annotations()) {
            if (!annotation.name().equals("AdheresTo")) continue;
            if (annotation.arguments().isEmpty()) throw new IllegalArgumentException("@AdheresTo requires at least one interface");
            Record actual = moduleShape(module);
            for (Ast.TypeRef ref : annotation.arguments()) {
                Ast.InterfaceDecl iface = findInterface(ref.name());
                if (iface == null) throw new IllegalArgumentException("unknown module interface '" + ref.name() + "'");
                Record expected = interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
                if (!assignable(actual, expected)) {
                    throw new IllegalArgumentException("module '" + module.name() + "' does not adhere to interface '" + ref.name() + "': expected " + expected + " but got " + actual);
                }
            }
        }
    }

    private Record moduleShape(Ast.ModuleDecl module) {
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC) {
                Type signature = functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
                mergeMember(members, fn.name(), signature, "module " + module.name());
                mergeMember(members, methodKey(fn.name(), fn.parameters().size()), signature, "module " + module.name());
            } else if (decl instanceof Ast.FieldDecl field && field.visibility() == Ast.Visibility.PUBLIC) {
                Type type = field.type() == null ? typeOf(field.initializer(), new Env(null), Set.of(), null) : resolve(field.type(), Set.of(), null);
                mergeMember(members, field.name(), type, "module " + module.name());
            }
        }
        return new Record(members);
    }

    private void checkInterface(Ast.InterfaceDecl iface) {
        Set<String> generics = uniqueGenerics(iface.genericParameters(), "interface " + iface.name());
        Set<String> memberKeys = new HashSet<>();
        for (Ast.TypeRef parentRef : iface.parents()) {
            if (findInterface(parentRef.name()) == null) throw new IllegalArgumentException("unknown parent interface '" + parentRef.name() + "' for " + iface.name());
        }

        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                String key = methodKey(fn.name(), fn.parameters().size());
                if (!memberKeys.add(key)) throw new IllegalArgumentException("duplicate interface method '" + iface.name() + "." + fn.name() + "' with arity " + fn.parameters().size());
                Set<String> all = new HashSet<>(generics);
                for (String generic : fn.genericParameters()) {
                    if (!all.add(generic)) throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in interface " + iface.name() + "." + fn.name());
                }
                functionType(fn.parameters(), fn.returnType(), all, null);
            } else {
                Ast.InterfaceFieldDecl field = (Ast.InterfaceFieldDecl) member;
                if (!memberKeys.add(field.name())) throw new IllegalArgumentException("duplicate interface member '" + iface.name() + "." + field.name() + "'");
                resolve(field.type(), generics, null);
            }
        }
    }

    private void checkFunction(String module, Ast.FunctionDecl fn) {
        Set<String> generics = uniqueGenerics(fn.genericParameters(), (fn.kind() == Ast.CallableKind.ROUTINE ? "routine " : "function ") + fn.name());
        Env env = new Env(null);
        for (Ast.Param param : fn.parameters()) env.define(param.name(), resolveParam(param, generics, null), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        Type returns = resolve(fn.returnType(), generics, null);
        checkBlock(fn.body(), env, generics, returns, null);
        if (returns != Primitive.VOID && !definitelyReturns(fn.body())) {
            throw new IllegalArgumentException("non-void " + fn.kind().name().toLowerCase() + " '" + module + "." + fn.name() + "' must explicitly return on every path");
        }
        if (fn.visibility() == Ast.Visibility.PUBLIC && hasInferredArgs(fn.returnType())) {
            throw new IllegalArgumentException("public callable '" + fn.name() + "' cannot export unresolved <> type arguments");
        }
    }

    private void checkClass(String module, Ast.ClassDecl klass) {
        Set<String> classGenerics = uniqueGenerics(klass.genericParameters(), "class " + klass.name());
        Type self = nominalClassType(klass);

        Set<String> parentNames = new HashSet<>();
        for (Ast.TypeRef parent : klass.parents()) {
            if (!parentNames.add(parent.name())) throw new IllegalArgumentException("duplicate parent class '" + parent.name() + "' on " + klass.name());
            resolveClassParent(parent, klass);
        }

        Set<String> localMethodSignatures = new HashSet<>();
        for (Ast.MethodDecl method : klass.methods()) {
            String memberKind = method.isStatic() ? "static:" : "instance:";
            String signature = memberKind + methodKey(method.name(), method.arity());
            if (!localMethodSignatures.add(signature)) {
                String label = method.isStatic() ? "static function" : "method";
                throw new IllegalArgumentException(label + " '" + klass.name() + "." + method.name() + "' already has arity " + method.arity()
                        + "; class callables may overload only by arity within their own static/instance namespace");
            }
        }

        for (Ast.FieldDecl field : klass.fields()) {
            Type fieldType = resolve(field.type(), classGenerics, self);
            if (field.initializer() != null) {
                Type actual = typeOf(field.initializer(), new Env(null), classGenerics, self);
                requireAssignable(actual, fieldType, "field initializer " + klass.name() + "." + field.name());
            }
            if (field.bindingKind() == Ast.BindingKind.CONST && field.initializer() != null && !constant(field.initializer())) {
                throw new IllegalArgumentException("const field '" + field.name() + "' needs a compile-time constant initializer");
            }
        }

        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isAbstract() && !klass.isAbstract()) {
                throw new IllegalArgumentException("concrete class '" + klass.name() + "' cannot declare abstract method '" + method.name() + "'");
            }
            Ast.MethodDecl overridden = findMethodInParents(klass, method.name(), method.arity(), new LinkedHashSet<>());
            if (hasOverrideAnnotation(method)) {
                if (overridden == null) {
                    throw new IllegalArgumentException("@override method '" + klass.name() + "." + method.name() + "' has no inherited method with arity " + method.arity());
                }
                Set<String> overrideGenerics = new HashSet<>(classGenerics);
                overrideGenerics.addAll(method.genericParameters());
                Function inheritedType = functionType(overridden.parameters(), overridden.returnType(), overrideGenerics, self);
                Function overridingType = functionType(method.parameters(), method.returnType(), overrideGenerics, self);
                if (!assignable(overridingType, inheritedType) || !assignable(inheritedType, overridingType)) {
                    throw new IllegalArgumentException("@override method '" + klass.name() + "." + method.name() + "' is not signature-compatible with its inherited method");
                }
            }

            Set<String> generics = new HashSet<>(classGenerics);
            for (String generic : method.genericParameters()) {
                if (!generics.add(generic)) throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in " + klass.name() + "." + method.name());
            }

            if (method.explicitReceiverType() != null) {
                Ast.TypeRef receiverRef = method.explicitReceiverType();
                boolean namesEnclosingClass = receiverRef.name().equals(klass.name()) || receiverRef.name().equals(qualifiedClassName(klass)) || receiverRef.name().equals("self");
                if (!namesEnclosingClass) {
                    Type receiver = resolve(receiverRef, generics, self);
                    requireAssignable(self, receiver, "explicit self receiver in " + klass.name() + "." + method.name());
                }
            }

            Type callableSelf = method.isStatic() ? null : self;
            Env env = new Env(null);
            if (!method.isStatic()) env.define("self", self, Ast.BindingKind.VAL);
            for (Ast.Param param : method.parameters()) env.define(param.name(), resolveParam(param, generics, callableSelf), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            Type returns = resolve(method.returnType(), generics, callableSelf);
            checkBlock(method.body(), env, generics, returns, callableSelf);
            if (!method.isAbstract() && returns != Primitive.VOID && !definitelyReturns(method.body())) {
                String label = method.isStatic() ? "static function" : "method";
                throw new IllegalArgumentException("non-void " + label + " '" + module + "." + klass.name() + "." + method.name() + "' must explicitly return on every path");
            }
        }

        Map<String, Ast.MethodDecl> unresolvedAbstract = new LinkedHashMap<>();
        collectAbstractMethods(klass, unresolvedAbstract, new LinkedHashSet<>());
        if (!klass.isAbstract() && !unresolvedAbstract.isEmpty()) {
            Ast.MethodDecl missing = unresolvedAbstract.values().iterator().next();
            throw new IllegalArgumentException("concrete class '" + klass.name() + "' must implement abstract method '" + missing.name() + "' with arity " + missing.arity());
        }

        Set<String> implemented = new HashSet<>();
        for (Ast.TypeRef interfaceRef : klass.interfaces()) {
            if (!implemented.add(interfaceRef.name())) throw new IllegalArgumentException("duplicate implemented interface '" + interfaceRef.name() + "' on " + klass.name());
            Ast.InterfaceDecl iface = findInterface(interfaceRef.name());
            if (iface == null) throw new IllegalArgumentException("unknown interface '" + interfaceRef.name() + "' implemented by " + klass.name());
            Record expected = interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
            Record actual = publicClassShape(klass, new LinkedHashSet<>());
            if (!assignable(actual, expected)) {
                throw new IllegalArgumentException("class '" + klass.name() + "' does not implement interface '" + interfaceRef.name() + "': expected " + expected + " but got " + actual);
            }
        }
    }

    private void checkModuleBinding(Ast.FieldDecl field) {
        if (field.initializer() == null) throw new IllegalArgumentException("module binding '" + field.name() + "' requires an initializer");
        Type actual = typeOf(field.initializer(), new Env(null), Set.of(), null);
        if (field.type() != null) requireAssignable(actual, resolve(field.type(), Set.of(), null), "initializer for " + field.name());
        if (field.bindingKind() == Ast.BindingKind.CONST && !constant(field.initializer())) {
            throw new IllegalArgumentException("const '" + field.name() + "' needs a compile-time constant initializer");
        }
    }

    private void checkBlock(List<Ast.Stmt> body, Env parent, Set<String> generics, Type expectedReturn, Type self) {
        Env env = new Env(parent);
        for (Ast.Stmt stmt : body) checkStatement(stmt, env, generics, expectedReturn, self);
    }

    private void checkStatement(Ast.Stmt stmt, Env env, Set<String> generics, Type expectedReturn, Type self) {
        if (stmt instanceof Ast.BindingStmt binding) {
            Type declaredAhead = binding.declaredType() == null ? null : resolve(binding.declaredType(), generics, self);
            boolean recursiveLambda = binding.initializer() instanceof Ast.LambdaExpr;
            if (recursiveLambda && declaredAhead instanceof Function) {
                env.define(binding.name(), declaredAhead, binding.kind());
            }
            if (binding.initializer() instanceof Ast.LambdaExpr lambda && declaredAhead instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }
            Type actual = typeOf(binding.initializer(), env, generics, self);
            Type declared = declaredAhead == null ? actual : declaredAhead;
            requireAssignable(actual, declared, "initializer for " + binding.name());
            if (binding.kind() == Ast.BindingKind.CONST && !constant(binding.initializer())) {
                throw new IllegalArgumentException("const '" + binding.name() + "' needs a compile-time constant initializer");
            }
            if (recursiveLambda && declaredAhead instanceof Function) env.replace(binding.name(), declared, binding.kind());
            else env.define(binding.name(), declared, binding.kind());
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            Type source = typeOf(destructure.initializer(), env, generics, self);
            if (source instanceof Tuple tuple) {
                if (tuple.elements().size() != destructure.bindings().size()) throw new IllegalArgumentException("destructure arity mismatch");
                for (int i = 0; i < destructure.bindings().size(); i++) {
                    Ast.DestructureBinding binding = destructure.bindings().get(i);
                    env.define(binding.name(), tuple.elements().get(i), binding.kind());
                }
            } else if (source instanceof ListType list) {
                for (Ast.DestructureBinding binding : destructure.bindings()) env.define(binding.name(), list.element(), binding.kind());
            } else throw new IllegalArgumentException("destructuring requires a tuple or array/list value");
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() instanceof Ast.LambdaExpr lambda && expectedReturn instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }
            Type actual = ret.value() == null ? Primitive.VOID : typeOf(ret.value(), env, generics, self);
            requireAssignable(actual, expectedReturn, "return value");
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) { typeOf(expression.expression(), env, generics, self); return; }
        if (stmt instanceof Ast.DeferStmt defer) { typeOf(defer.expression(), env, generics, self); return; }
        if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                requireAssignable(typeOf(branch.condition(), env, generics, self), Primitive.BOOL, "if condition");
                checkBlock(branch.body(), env, generics, expectedReturn, self);
            }
            checkBlock(conditional.elseBody(), env, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.TryStmt attempted) {
            checkBlock(attempted.body(), env, generics, expectedReturn, self);
            Env caught = new Env(env);
            caught.define(attempted.errorName(), Unknown.INSTANCE, Ast.BindingKind.VAL);
            checkBlock(attempted.catchBody(), caught, generics, expectedReturn, self);
            checkBlock(attempted.finallyBody(), env, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.ForOfStmt loop) {
            Type iterable = typeOf(loop.iterable(), env, generics, self);
            Type element = iterableElementType(iterable);
            Env loopEnv = new Env(env);
            loopEnv.define(loop.bindingName(), element, loop.bindingKind());
            checkBlock(loop.body(), loopEnv, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.ForStmt loop) {
            Env loopEnv = new Env(env);
            if (loop.initializer() != null) checkStatement(loop.initializer(), loopEnv, generics, expectedReturn, self);
            if (loop.condition() != null) requireAssignable(typeOf(loop.condition(), loopEnv, generics, self), Primitive.BOOL, "for condition");
            if (loop.update() != null) typeOf(loop.update(), loopEnv, generics, self);
            checkBlock(loop.body(), loopEnv, generics, expectedReturn, self);
        }
    }

    private Type typeOf(Ast.Expr expr, Env env, Set<String> generics, Type self) {
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value == null) throw new IllegalArgumentException("standalone null values are forbidden; use Option<T>");
            if (value instanceof Long) return Primitive.INT;
            if (value instanceof Double) return Primitive.FLOAT;
            if (value instanceof Boolean) return Primitive.BOOL;
            if (value instanceof String s) return new StringLiteral(s);
            if (value instanceof Ast.Imaginary) return Primitive.COMPLEX;
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.NameExpr name) {
            Env.Binding local = env.lookup(name.name());
            if (local != null) return local.type();
            if (name.name().equals("stdio") || name.name().equals("process")) return new Named(name.name(), List.of());
            if (name.name().equals("print")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            if (name.name().equals("None")) return new Named("Option", List.of(Unknown.INSTANCE));
            Ast.ModuleDecl moduleNamespace = modules.get(name.name());
            if (moduleNamespace != null) return moduleShape(moduleNamespace);
            Ast.ClassDecl classNamespace = findClass(name.name());
            if (classNamespace != null) return new ClassNamespace(qualifiedClassName(classNamespace));
            if (importedValues.contains(name.name())) return Unknown.INSTANCE;
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) return functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
            throw new IllegalArgumentException("unknown name '" + name.name() + "'");
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            Type targetType;
            String where;
            if (assignment.target() instanceof Ast.NameExpr name) {
                Env.Binding binding = env.lookup(name.name());
                if (binding == null) throw new IllegalArgumentException("cannot assign unknown name '" + name.name() + "'");
                if (binding.kind() != Ast.BindingKind.LET) throw new IllegalArgumentException("cannot reassign " + binding.kind().name().toLowerCase() + " binding '" + name.name() + "'");
                targetType = binding.type();
                where = name.name();
            } else if (assignment.target() instanceof Ast.MemberExpr member) {
                targetType = memberType(member, env, generics, self);
                where = member.member();
            } else if (assignment.target() instanceof Ast.IndexExpr indexed) {
                Type receiver = deref(typeOf(indexed.receiver(), env, generics, self));
                Type index = typeOf(indexed.index(), env, generics, self);
                if (receiver instanceof ListType list) {
                    requireAssignable(index, Primitive.INT, "array/list index");
                    targetType = list.element();
                } else {
                    Type mapKey = builtinMapKey(receiver);
                    if (mapKey != null) {
                        requireAssignable(index, mapKey, "map index");
                        targetType = builtinMapValue(receiver);
                    } else if (receiver instanceof Tuple tuple) {
                        requireAssignable(index, Primitive.INT, "tuple index");
                        targetType = tuple.elements().stream().reduce(this::commonType).orElse(Unknown.INSTANCE);
                    } else throw new IllegalArgumentException("indexed assignment requires an array/list, map, or tuple");
                }
                where = "index";
            } else throw new IllegalArgumentException("unsupported assignment target");
            Type value = typeOf(assignment.value(), env, generics, self);
            requireAssignable(value, targetType, "assignment to " + where);
            return targetType;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            requireAssignable(typeOf(conditional.condition(), env, generics, self), Primitive.BOOL, "ternary condition");
            Type left = typeOf(conditional.whenTrue(), env, generics, self);
            Type right = typeOf(conditional.whenFalse(), env, generics, self);
            return commonType(left, right);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            Type operand = typeOf(unary.operand(), env, generics, self);
            if (unary.operator().equals("&")) return new Borrow(operand, false);
            if (unary.operator().equals("&mut")) return new Borrow(operand, true);
            if (unary.operator().equals("!")) {
                requireAssignable(operand, Primitive.BOOL, "! operand");
                return Primitive.BOOL;
            }
            if (!Types.isNumeric(operand)) throw new IllegalArgumentException("unary " + unary.operator() + " needs a numeric operand");
            return operand;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            Type left = typeOf(binary.left(), env, generics, self);
            Type right = typeOf(binary.right(), env, generics, self);
            return switch (binary.operator()) {
                case ",", "|" -> {
                    requireAssignable(left, Primitive.BOOL, "boolean operand");
                    requireAssignable(right, Primitive.BOOL, "boolean operand");
                    yield Primitive.BOOL;
                }
                case "==", "!=" -> Primitive.BOOL;
                case "<", "<=", ">", ">=" -> {
                    if (!(Types.isNumeric(left) && Types.isNumeric(right)) && !(isStringLike(left) && isStringLike(right))) {
                        throw new IllegalArgumentException("comparison operands must both be numeric or both strings");
                    }
                    yield Primitive.BOOL;
                }
                case "+" -> isStringLike(left) && isStringLike(right) ? Primitive.STRING : numericJoin(left, right, "+");
                case "-", "*", "/", "%" -> numericJoin(left, right, binary.operator());
                default -> Unknown.INSTANCE;
            };
        }
        if (expr instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.NameExpr name && name.name().equals("Some")) {
                if (call.arguments().size() != 1) throw new IllegalArgumentException("Some expects exactly one value");
                return new Named("Option", List.of(typeOf(call.arguments().getFirst(), env, generics, self)));
            }
            if (call.callee() instanceof Ast.MemberExpr member) {
                Type receiver = deref(typeOf(member.receiver(), env, generics, self));
                if (receiver instanceof ClassNamespace classNamespace) {
                    Ast.ClassDecl klass = findClass(classNamespace.className());
                    if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                    Ast.MethodDecl fn = findStaticFunction(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                    if (fn == null) throw new IllegalArgumentException("no static function '" + member.member() + "' with arity " + call.arguments().size() + " on " + klass.name());
                    Set<String> fnGenerics = new HashSet<>(klass.genericParameters());
                    fnGenerics.addAll(fn.genericParameters());
                    for (int i = 0; i < call.arguments().size(); i++) {
                        Type expected = resolveParam(fn.parameters().get(i), fnGenerics, null);
                        validateLambdaArgument(call.arguments().get(i), expected, env, generics, self);
                        requireAssignable(typeOf(call.arguments().get(i), env, generics, self), expected, "argument " + (i + 1));
                    }
                    return resolve(fn.returnType(), fnGenerics, null);
                }
                Function builtinMethod = builtinCollectionMethod(receiver, member.member(), call.arguments().size());
                if (builtinMethod != null) {
                    for (int i = 0; i < call.arguments().size(); i++) {
                        Type expected = builtinMethod.parameters().get(i);
                        validateLambdaArgument(call.arguments().get(i), expected, env, generics, self);
                        requireAssignable(typeOf(call.arguments().get(i), env, generics, self), expected, "argument " + (i + 1));
                    }
                    return builtinMethod.result();
                }
                if (receiver instanceof Named named) {
                    Ast.ClassDecl klass = findClass(named.name());
                    if (klass != null) {
                        Ast.MethodDecl method = findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                        if (method == null) throw new IllegalArgumentException("no method '" + member.member() + "' with arity " + call.arguments().size() + " on " + named.name());
                        Set<String> methodGenerics = new HashSet<>(klass.genericParameters());
                        methodGenerics.addAll(method.genericParameters());
                        for (int i = 0; i < call.arguments().size(); i++) {
                            Type expected = resolveParam(method.parameters().get(i), methodGenerics, named);
                            validateLambdaArgument(call.arguments().get(i), expected, env, generics, self);
                            requireAssignable(typeOf(call.arguments().get(i), env, generics, self), expected, "argument " + (i + 1));
                        }
                        return resolve(method.returnType(), methodGenerics, named);
                    }
                }
            }
            Type callee = typeOf(call.callee(), env, generics, self);
            if (!(callee instanceof Function fn)) return Unknown.INSTANCE;
            if (fn.parameters().size() != call.arguments().size()) throw new IllegalArgumentException("call arity mismatch");
            for (int i = 0; i < fn.parameters().size(); i++) {
                validateLambdaArgument(call.arguments().get(i), fn.parameters().get(i), env, generics, self);
                requireAssignable(typeOf(call.arguments().get(i), env, generics, self), fn.parameters().get(i), "argument " + (i + 1));
            }
            return fn.result();
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr namespace && modules.containsKey(namespace.name())) {
                Ast.ClassDecl memberClass = classes.get(namespace.name() + "." + member.member());
                if (memberClass != null) return new ClassNamespace(qualifiedClassName(memberClass));
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("stdio")) {
                if (member.member().equals("print") || member.member().equals("println")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
                if (member.member().equals("stdout")) return new Named("stdio.stdout", List.of());
            }
            Type receiver = typeOf(member.receiver(), env, generics, self);
            if (receiver instanceof Named named && named.name().equals("stdio.stdout") && member.member().equals("write")) {
                return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("process")) return Unknown.INSTANCE;
            if (member.receiver() instanceof Ast.NameExpr name && importedValues.contains(name.name())) return Unknown.INSTANCE;

            if (receiver instanceof ClassNamespace classNamespace) {
                Ast.ClassDecl klass = findClass(classNamespace.className());
                if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                List<Ast.MethodDecl> functions = findStaticFunctionsByName(klass, member.member(), new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    return functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
                }
                if (functions.size() > 1) throw new IllegalArgumentException("overloaded static function '" + member.member() + "' must be called so arity can select the overload");
                throw new IllegalArgumentException("unknown static member '" + member.member() + "' on " + klass.name());
            }

            if (receiver instanceof Record record) {
                Type result = record.members().get(member.member());
                if (result == null) throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
                return result;
            }
            Function builtinMember = builtinCollectionMethod(receiver, member.member(), -1);
            if (builtinMember != null) return builtinMember;
            if (receiver instanceof Named named) {
                Ast.ClassDecl klass = findClass(named.name());
                if (klass != null) {
                    Type field = findFieldType(klass, member.member(), new LinkedHashSet<>());
                    if (field != null) return field;
                    List<Ast.MethodDecl> methods = findMethodsByName(klass, member.member(), new LinkedHashSet<>());
                    if (methods.size() == 1) {
                        Ast.MethodDecl method = methods.getFirst();
                        return functionType(method.parameters(), method.returnType(), Set.copyOf(method.genericParameters()), named);
                    }
                    if (methods.size() > 1) throw new IllegalArgumentException("overloaded method '" + member.member() + "' must be called so arity can select the overload");
                }
            }
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            Type receiver = deref(typeOf(indexed.receiver(), env, generics, self));
            Type index = typeOf(indexed.index(), env, generics, self);
            if (receiver instanceof ListType list) {
                requireAssignable(index, Primitive.INT, "array/list index");
                return list.element();
            }
            Type mapKey = builtinMapKey(receiver);
            if (mapKey != null) {
                requireAssignable(index, mapKey, "map index");
                return builtinMapValue(receiver);
            }
            if (receiver instanceof Tuple tuple) {
                requireAssignable(index, Primitive.INT, "tuple index");
                return tuple.elements().stream().reduce(this::commonType).orElse(Unknown.INSTANCE);
            }
            throw new IllegalArgumentException("indexing requires an array/list, map, or tuple");
        }
        if (expr instanceof Ast.NewExpr created) {
            String typeName = created.type().name();

            if (isBuiltinListName(typeName)) {
                if (!created.entries().isEmpty()) throw new IllegalArgumentException(typeName + " does not accept keyed initializer entries; use constructor elements or arr[...]");
                if (created.type().arguments().size() > 1) throw new IllegalArgumentException(typeName + " accepts at most one type argument");
                boolean explicitElement = !created.type().arguments().isEmpty();
                Type element = explicitElement
                        ? resolve(created.type().arguments().getFirst(), generics, self)
                        : Unknown.INSTANCE;
                boolean sawInferredElement = false;
                for (Ast.Expr argument : created.arguments()) {
                    Type actual = typeOf(argument, env, generics, self);
                    if (explicitElement) requireAssignable(actual, element, "constructor element for " + typeName);
                    else if (!sawInferredElement) {
                        element = actual;
                        sawInferredElement = true;
                    } else element = commonType(element, actual);
                }
                Type collection = new ListType(element);
                validateAnonymousBuiltinMethods(created.methods(), collection, env, generics);
                return collection;
            }

            if (isBuiltinMapName(typeName)) {
                if (!created.arguments().isEmpty()) throw new IllegalArgumentException(typeName + " uses keyed initializer entries rather than positional constructor arguments");
                Type key;
                Type value;
                if (typeName.equals("JsonMap")) {
                    if (!created.type().arguments().isEmpty()) throw new IllegalArgumentException("JsonMap does not accept type arguments");
                    key = Primitive.STRING;
                    value = Unknown.INSTANCE;
                } else {
                    if (!created.type().arguments().isEmpty() && created.type().arguments().size() != 2) {
                        throw new IllegalArgumentException(typeName + " requires exactly two type arguments when explicit");
                    }
                    key = created.type().arguments().size() == 2 ? resolve(created.type().arguments().get(0), generics, self) : Unknown.INSTANCE;
                    value = created.type().arguments().size() == 2 ? resolve(created.type().arguments().get(1), generics, self) : Unknown.INSTANCE;
                }
                boolean inferredValue = !typeName.equals("JsonMap") && created.type().arguments().isEmpty();
                boolean sawInferredValue = false;
                Set<String> initializerKeys = new HashSet<>();
                for (Ast.ObjectField entry : created.entries()) {
                    if (!initializerKeys.add(entry.name())) throw new IllegalArgumentException("duplicate map initializer key '" + entry.name() + "'");
                    if (key == Unknown.INSTANCE) key = Primitive.STRING;
                    else requireAssignable(Primitive.STRING, key, "map initializer key '" + entry.name() + "'");
                    Type actual = typeOf(entry.value(), env, generics, self);
                    if (inferredValue) {
                        if (!sawInferredValue) {
                            value = actual;
                            sawInferredValue = true;
                        } else value = commonType(value, actual);
                    } else if (value != Unknown.INSTANCE) requireAssignable(actual, value, "map initializer value for '" + entry.name() + "'");
                }
                Type collection = new Named(typeName, List.of(key, value));
                validateAnonymousBuiltinMethods(created.methods(), collection, env, generics);
                return collection;
            }

            if (typeName.equals("Object")) {
                if (!created.arguments().isEmpty()) throw new IllegalArgumentException("Object does not accept positional constructor arguments");
                if (!created.methods().isEmpty()) throw new IllegalArgumentException("anonymous Object methods must implement a declared base method; Object declares none");
                Map<String, Type> members = new LinkedHashMap<>();
                for (Ast.ObjectField entry : created.entries()) {
                    if (members.putIfAbsent(entry.name(), typeOf(entry.value(), env, generics, self)) != null) {
                        throw new IllegalArgumentException("duplicate Object initializer field '" + entry.name() + "'");
                    }
                }
                return new Record(members);
            }

            Ast.ClassDecl klass = findClass(typeName);
            if (klass == null) {
                if (created.hasAnonymousBody()) throw new IllegalArgumentException("anonymous construction requires a known class or builtin collection type, got '" + typeName + "'");
                return new Named(typeName, created.type().arguments().stream().map(a -> resolve(a, generics, self)).toList());
            }
            if (!created.entries().isEmpty()) {
                throw new IllegalArgumentException("keyed initializer entries are supported by Object/Map/HashMap/JsonMap; class '" + klass.name() + "' uses declared fields");
            }
            List<Ast.FieldDecl> fields = effectiveFields(klass, new LinkedHashSet<>());
            if (created.arguments().size() > fields.size()) throw new IllegalArgumentException("constructor for " + klass.name() + " received too many positional fields");
            Type nominal = nominalClassType(klass);
            for (int i = 0; i < fields.size(); i++) {
                Ast.FieldDecl field = fields.get(i);
                if (i < created.arguments().size()) {
                    requireAssignable(typeOf(created.arguments().get(i), env, generics, self),
                            resolve(field.type(), Set.copyOf(klass.genericParameters()), nominal), "constructor field " + field.name());
                } else if (field.initializer() == null) {
                    throw new IllegalArgumentException("constructor for " + klass.name() + " is missing field '" + field.name() + "'");
                }
            }
            validateAnonymousClassMethods(klass, created.methods(), nominal, env);
            ensureInstantiable(klass, created.methods(), created.hasAnonymousBody());
            return nominal;
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            Type awaitedType = typeOf(awaited.expression(), env, generics, self);
            if (awaitedType instanceof Named named && named.name().equals("Future") && named.arguments().size() == 1) return named.arguments().getFirst();
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.ListExpr list) {
            if (list.elements().isEmpty()) return new ListType(Unknown.INSTANCE);
            Type element = typeOf(list.elements().getFirst(), env, generics, self);
            for (int i = 1; i < list.elements().size(); i++) element = commonType(element, typeOf(list.elements().get(i), env, generics, self));
            return new ListType(element);
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            return new Tuple(tuple.elements().stream().map(item -> typeOf(item, env, generics, self)).toList());
        }
        if (expr instanceof Ast.ObjectExpr object) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Ast.ObjectField field : object.fields()) {
                if (members.putIfAbsent(field.name(), typeOf(field.value(), env, generics, self)) != null) {
                    throw new IllegalArgumentException("duplicate obj field '" + field.name() + "'");
                }
            }
            return new Record(members);
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            Env lambdaEnv = new Env(env);
            List<Type> parameters = new ArrayList<>();
            for (Ast.Param param : lambda.parameters()) {
                Type type = resolveParam(param, generics, self);
                parameters.add(type);
                lambdaEnv.define(param.name(), type, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            if (lambda.expressionBody() != null) {
                throw new IllegalArgumentException("expression-body lambdas are not supported; lambdas require braces and explicit return");
            }
            checkBlock(lambda.blockBody(), lambdaEnv, generics, Unknown.INSTANCE, self);
            return new Function(parameters, Unknown.INSTANCE);
        }
        return Unknown.INSTANCE;
    }

    private void validateLambdaArgument(Ast.Expr argument, Type expected, Env env, Set<String> generics, Type self) {
        if (argument instanceof Ast.LambdaExpr lambda && expected instanceof Function fn) {
            validateLambdaAgainstExpected(lambda, fn, env, generics, self);
        }
    }

    private void validateLambdaAgainstExpected(Ast.LambdaExpr lambda, Function expected, Env parent, Set<String> generics, Type self) {
        if (lambda.expressionBody() != null) {
            throw new IllegalArgumentException("lambdas always require a block body and explicit return for non-void results");
        }
        if (lambda.parameters().size() != expected.parameters().size()) {
            throw new IllegalArgumentException("lambda arity " + lambda.parameters().size() + " does not match expected function arity " + expected.parameters().size());
        }
        Env lambdaEnv = new Env(parent);
        for (int i = 0; i < lambda.parameters().size(); i++) {
            Ast.Param param = lambda.parameters().get(i);
            Type expectedParam = expected.parameters().get(i);
            Type declared = param.type().name().equals("$infer$") ? expectedParam : resolveParam(param, generics, self);
            requireAssignable(expectedParam, declared, "lambda parameter " + param.name());
            requireAssignable(declared, expectedParam, "lambda parameter " + param.name());
            lambdaEnv.define(param.name(), declared, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        }
        checkBlock(lambda.blockBody(), lambdaEnv, generics, expected.result(), self);
        if (expected.result() != Primitive.VOID && !definitelyReturns(lambda.blockBody())) {
            throw new IllegalArgumentException("non-void lambda must explicitly return on every path");
        }
    }

    private Type deref(Type type) {
        return type instanceof Borrow borrow ? borrow.target() : type;
    }

    private Type memberType(Ast.MemberExpr member, Env env, Set<String> generics, Type self) {
        Type receiver = deref(typeOf(member.receiver(), env, generics, self));
        if (receiver instanceof Record record) {
            Type result = record.members().get(member.member());
            if (result == null) throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
            return result;
        }
        if (receiver instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                Type field = findFieldType(klass, member.member(), new LinkedHashSet<>());
                if (field != null) return field;
            }
        }
        throw new IllegalArgumentException("assignment target '" + member.member() + "' is not a mutable data field");
    }

    private Type iterableElementType(Type iterable) {
        if (iterable instanceof ListType list) return list.element();
        if (iterable instanceof Tuple tuple) {
            Type result = Unknown.INSTANCE;
            for (Type element : tuple.elements()) result = result == Unknown.INSTANCE ? element : commonType(result, element);
            return result;
        }
        if (iterable instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                Ast.MethodDecl iterator = findMethod(klass, "Symbol.iterator", 0, new LinkedHashSet<>());
                if (iterator != null) {
                    Type result = resolve(iterator.returnType(), Set.copyOf(iterator.genericParameters()), named);
                    if (result instanceof ListType list) return list.element();
                    if (result instanceof Tuple tuple) return iterableElementType(tuple);
                    throw new IllegalArgumentException("[Symbol.iterator]() must return Array<T>, List<T>, or a tuple in v0");
                }
            }
        }
        throw new IllegalArgumentException("for-of requires an array/list, tuple, or a class with [Symbol.iterator]()");
    }

    private Type commonType(Type a, Type b) {
        if (assignable(b, a) && assignable(a, b)) return a;
        if (Types.isNumeric(a) && Types.isNumeric(b)) return Types.numericJoin(a, b);
        if (isStringLike(a) && isStringLike(b)) return Primitive.STRING;
        return Unknown.INSTANCE;
    }

    private Type numericJoin(Type left, Type right, String op) {
        Type result = Types.numericJoin(left, right);
        if (result == Unknown.INSTANCE) throw new IllegalArgumentException("operator '" + op + "' needs numeric operands");
        return result;
    }

    private boolean isStringLike(Type type) {
        return type == Primitive.STRING || type instanceof StringLiteral;
    }

    private Record classShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        Record cached = classShapeCache.get(klass);
        if (cached != null) return cached;
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                for (Map.Entry<String, Type> inherited : classShape(parent, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), inherited.getValue(), "multiple inheritance of " + klass.name());
                }
            }
        }
        Set<String> generics = Set.copyOf(klass.genericParameters());
        Type self = nominalClassType(klass);
        for (Ast.FieldDecl field : klass.fields()) mergeMember(members, field.name(), resolve(field.type(), generics, self), "class " + klass.name());
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic()) continue;
            mergeMember(members, methodKey(method.name(), method.arity()),
                    functionType(method.parameters(), method.returnType(), generics, self), "class " + klass.name());
        }
        stack.remove(klass);
        Record result = new Record(members);
        classShapeCache.put(klass, result);
        return result;
    }

    private Record publicClassShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                for (Map.Entry<String, Type> inherited : publicClassShape(parent, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), inherited.getValue(), "public inheritance of " + klass.name());
                }
            }
        }
        Set<String> generics = Set.copyOf(klass.genericParameters());
        Type self = nominalClassType(klass);
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.visibility() == Ast.Visibility.PUBLIC) mergeMember(members, field.name(), resolve(field.type(), generics, self), "class " + klass.name());
        }
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.visibility() == Ast.Visibility.PUBLIC) {
                mergeMember(members, methodKey(method.name(), method.arity()),
                        functionType(method.parameters(), method.returnType(), generics, self), "class " + klass.name());
            }
        }
        stack.remove(klass);
        return new Record(members);
    }

    private Record interfaceShape(Ast.InterfaceDecl iface, Set<String> generics, Set<Ast.InterfaceDecl> stack) {
        if (!stack.add(iface)) throw new IllegalArgumentException("interface inheritance cycle involving '" + iface.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent == null) throw new IllegalArgumentException("unknown parent interface '" + parentRef.name() + "' for " + iface.name());
            for (Map.Entry<String, Type> inherited : interfaceShape(parent, Set.copyOf(parent.genericParameters()), stack).members().entrySet()) {
                mergeMember(members, inherited.getKey(), inherited.getValue(), "interface inheritance of " + iface.name());
            }
        }
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                Set<String> all = new HashSet<>(generics);
                all.addAll(fn.genericParameters());
                mergeMember(members, methodKey(fn.name(), fn.parameters().size()),
                        functionType(fn.parameters(), fn.returnType(), all, null), "interface " + iface.name());
            } else if (member instanceof Ast.InterfaceFieldDecl field) {
                mergeMember(members, field.name(), resolve(field.type(), generics, null), "interface " + iface.name());
            }
        }
        stack.remove(iface);
        return new Record(members);
    }

    private List<Ast.FieldDecl> effectiveFields(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        LinkedHashMap<String, Ast.FieldDecl> fields = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) for (Ast.FieldDecl field : effectiveFields(parent, stack)) fields.putIfAbsent(field.name(), field);
        }
        for (Ast.FieldDecl field : klass.fields()) fields.put(field.name(), field);
        stack.remove(klass);
        return List.copyOf(fields.values());
    }

    private Type findFieldType(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        Type self = nominalClassType(klass);
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                seen.remove(klass);
                return resolve(field.type(), Set.copyOf(klass.genericParameters()), self);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Type result = findFieldType(parent, name, seen);
            if (result != null) {
                seen.remove(klass);
                return result;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.MethodDecl findMethod(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name) && method.arity() == arity) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.MethodDecl candidate = findMethod(parent, name, arity, seen);
            if (candidate != null) {
                seen.remove(klass);
                return candidate;
            }
        }
        seen.remove(klass);
        return null;
    }

    private List<Ast.MethodDecl> findMethodsByName(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<Integer, Ast.MethodDecl> methods = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) if (!method.isStatic() && method.name().equals(name)) methods.put(method.arity(), method);
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl method : findMethodsByName(parent, name, seen)) methods.putIfAbsent(method.arity(), method);
        }
        seen.remove(klass);
        return List.copyOf(methods.values());
    }

    private Ast.MethodDecl findStaticFunction(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() && method.name().equals(name) && method.arity() == arity) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.MethodDecl candidate = findStaticFunction(parent, name, arity, seen);
            if (candidate != null) {
                seen.remove(klass);
                return candidate;
            }
        }
        seen.remove(klass);
        return null;
    }

    private List<Ast.MethodDecl> findStaticFunctionsByName(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<Integer, Ast.MethodDecl> functions = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) if (method.isStatic() && method.name().equals(name)) functions.put(method.arity(), method);
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl fn : findStaticFunctionsByName(parent, name, seen)) functions.putIfAbsent(fn.arity(), fn);
        }
        seen.remove(klass);
        return List.copyOf(functions.values());
    }

    private Ast.ClassDecl resolveClassParent(Ast.TypeRef parentRef, Ast.ClassDecl child) {
        if (parentRef.name().equals("Object") || parentRef.name().equals("List")) return null;
        if (parentRef.name().equals("obj") || parentRef.name().equals("arr")) throw new IllegalArgumentException("inline obj/arr values cannot be subclassed; extend Object or List instead");
        Ast.ClassDecl parent = findClass(parentRef.name());
        if (parent == null) throw new IllegalArgumentException("unknown parent class '" + parentRef.name() + "' for " + child.name());
        if (parent == child) throw new IllegalArgumentException("class '" + child.name() + "' cannot extend itself");
        return parent;
    }

    private void mergeMember(Map<String, Type> members, String name, Type type, String owner) {
        Type existing = members.get(name);
        if (existing != null && (!assignable(existing, type) || !assignable(type, existing))) {
            throw new IllegalArgumentException("conflicting member '" + name + "' in " + owner + ": " + existing + " vs " + type);
        }
        members.put(name, type);
    }

    private Function functionType(List<Ast.Param> params, Ast.TypeRef returns, Set<String> generics, Type self) {
        return new Function(params.stream().map(p -> resolveParam(p, generics, self)).toList(), resolve(returns, generics, self));
    }

    private Type resolveParam(Ast.Param param, Set<String> generics, Type self) {
        if (!param.structural()) return resolve(param.type(), generics, self);
        Ast.InterfaceDecl iface = findInterface(param.type().name());
        if (iface != null) return interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
        Ast.ClassDecl klass = findClass(param.type().name());
        if (klass != null) return publicClassShape(klass, new LinkedHashSet<>());
        throw new IllegalArgumentException("@Structural requires a known class or interface type, got '" + param.type().name() + "'");
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self) {
        return resolve(ref, generics, self, false);
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self, boolean allowNullMarker) {
        if (ref == null) return Unknown.INSTANCE;
        if (ref.isBorrow()) return new Borrow(resolve(ref.borrowedTarget(), generics, self, allowNullMarker), ref.mutableBorrow());
        if (ref.isStringLiteral()) return new StringLiteral(ref.stringLiteralValue());
        if (ref.name().equals("$infer$")) return Unknown.INSTANCE;
        if (ref.name().equals("self")) return self == null ? Unknown.INSTANCE : self;
        if (ref.name().equals("null")) {
            if (!allowNullMarker) throw new IllegalArgumentException("null is not a standalone type; it is only legal as Option<null>");
            return Primitive.NULL;
        }
        if (generics.contains(ref.name())) return new Generic(ref.name());

        Ast.TypeAliasDecl alias = findTypeAlias(ref.name());
        if (alias != null) {
            if (alias.genericParameters().size() != ref.arguments().size()) {
                throw new IllegalArgumentException("type alias '" + alias.name() + "' expects " + alias.genericParameters().size()
                        + " type argument(s), got " + ref.arguments().size());
            }
            if (!resolvingAliases.add(alias)) throw new IllegalArgumentException("type alias cycle involving '" + alias.name() + "'");
            try {
                Map<String, Ast.TypeRef> substitutions = new HashMap<>();
                for (int i = 0; i < alias.genericParameters().size(); i++) {
                    substitutions.put(alias.genericParameters().get(i), ref.arguments().get(i));
                }
                return resolve(substituteAliasType(alias.target(), substitutions), generics, self, allowNullMarker);
            } finally {
                resolvingAliases.remove(alias);
            }
        }

        return switch (ref.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "int", "uint", "bigint" -> Primitive.INT;
            case "f32", "f64", "float" -> Primitive.FLOAT;
            case "decimal" -> Primitive.DECIMAL;
            case "complex64", "complex128", "complex" -> Primitive.COMPLEX;
            case "bool", "Bool" -> Primitive.BOOL;
            case "string", "String" -> Primitive.STRING;
            case "void" -> Primitive.VOID;
            case "Array", "Arrays", "List", "LinkedList" -> {
                if (!ref.inferArguments() && ref.arguments().size() != 1) throw new IllegalArgumentException(ref.name() + " requires exactly one type argument");
                yield new ListType(ref.arguments().isEmpty() ? Unknown.INSTANCE : resolve(ref.arguments().getFirst(), generics, self));
            }
            case "Map", "HashMap" -> {
                if (!ref.arguments().isEmpty() && ref.arguments().size() != 2) throw new IllegalArgumentException(ref.name() + " requires exactly two type arguments");
                Type key = ref.arguments().isEmpty() ? Unknown.INSTANCE : resolve(ref.arguments().get(0), generics, self);
                Type value = ref.arguments().isEmpty() ? Unknown.INSTANCE : resolve(ref.arguments().get(1), generics, self);
                yield new Named(ref.name(), List.of(key, value));
            }
            case "JsonMap" -> {
                if (!ref.arguments().isEmpty()) throw new IllegalArgumentException("JsonMap does not accept type arguments");
                yield new Named("JsonMap", List.of(Primitive.STRING, Unknown.INSTANCE));
            }
            case "Object" -> new Named("Object", List.of());
            case "Option" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Option requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self, true);
                if (element == Primitive.VOID) throw new IllegalArgumentException("Option<void> is invalid; use void for no return value");
                yield new Named("Option", List.of(element));
            }
            case "Fnc" -> {
                List<Type> args = ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList();
                if (args.isEmpty()) throw new IllegalArgumentException("Fnc requires at least a result type");
                yield new Function(args.subList(0, args.size() - 1), args.getLast());
            }
            default -> new Named(ref.name(), ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
        };
    }

    private boolean isBuiltinListName(String name) {
        return name.equals("Array") || name.equals("Arrays") || name.equals("List") || name.equals("LinkedList");
    }

    private boolean isBuiltinMapName(String name) {
        return name.equals("Map") || name.equals("HashMap") || name.equals("JsonMap");
    }

    private Type builtinMapKey(Type receiver) {
        if (!(receiver instanceof Named named) || !isBuiltinMapName(named.name())) return null;
        return named.arguments().size() >= 1 ? named.arguments().get(0) : Unknown.INSTANCE;
    }

    private Type builtinMapValue(Type receiver) {
        if (!(receiver instanceof Named named) || !isBuiltinMapName(named.name())) return null;
        return named.arguments().size() >= 2 ? named.arguments().get(1) : Unknown.INSTANCE;
    }

    /**
     * Builtin collections are compiler-known classes: their storage is runtime
     * intrinsic, but their method surface is statically typed and overrideable.
     * arity < 0 asks for an unambiguous method value by name.
     */
    private Function builtinCollectionMethod(Type receiver, String name, int arity) {
        if (receiver instanceof ListType list) {
            Type element = list.element();
            return switch (name) {
                case "get" -> arity < 0 || arity == 1 ? new Function(List.of(Primitive.INT), element) : null;
                case "set" -> arity < 0 || arity == 2 ? new Function(List.of(Primitive.INT, element), Primitive.VOID) : null;
                case "add", "push" -> arity < 0 || arity == 1 ? new Function(List.of(element), Primitive.VOID) : null;
                case "remove_at" -> arity < 0 || arity == 1 ? new Function(List.of(Primitive.INT), element) : null;
                case "size", "length" -> arity < 0 || arity == 0 ? new Function(List.of(), Primitive.INT) : null;
                default -> null;
            };
        }
        Type key = builtinMapKey(receiver);
        if (key == null) return null;
        Type value = builtinMapValue(receiver);
        return switch (name) {
            case "get", "find", "remove" -> arity < 0 || arity == 1
                    ? new Function(List.of(key), new Named("Option", List.of(value))) : null;
            case "put" -> arity < 0 || arity == 2 ? new Function(List.of(key, value), Primitive.VOID) : null;
            case "contains_key" -> arity < 0 || arity == 1 ? new Function(List.of(key), Primitive.BOOL) : null;
            case "size" -> arity < 0 || arity == 0 ? new Function(List.of(), Primitive.INT) : null;
            case "keys" -> arity < 0 || arity == 0 ? new Function(List.of(), new ListType(key)) : null;
            case "values" -> arity < 0 || arity == 0 ? new Function(List.of(), new ListType(value)) : null;
            default -> null;
        };
    }

    private boolean hasOverrideAnnotation(Ast.MethodDecl method) {
        return method.annotations().stream().anyMatch(a -> a.name().equalsIgnoreCase("override"));
    }

    private void validateAnonymousBuiltinMethods(List<Ast.MethodDecl> methods, Type selfType, Env outer, Set<String> outerGenerics) {
        Set<String> signatures = new HashSet<>();
        for (Ast.MethodDecl method : methods) {
            if (!hasOverrideAnnotation(method)) {
                throw new IllegalArgumentException("anonymous method '" + method.name() + "' must be annotated @override");
            }
            String signature = methodKey(method.name(), method.arity());
            if (!signatures.add(signature)) throw new IllegalArgumentException("duplicate anonymous override '" + method.name() + "' with arity " + method.arity());
            Function expected = builtinCollectionMethod(selfType, method.name(), method.arity());
            if (expected == null) throw new IllegalArgumentException("no builtin method '" + method.name() + "' with arity " + method.arity() + " to override");

            Set<String> generics = new HashSet<>(outerGenerics);
            generics.addAll(method.genericParameters());
            if (method.parameters().size() != expected.parameters().size()) throw new IllegalArgumentException("override arity mismatch for " + method.name());
            for (int i = 0; i < method.parameters().size(); i++) {
                Type actualParam = resolveParam(method.parameters().get(i), generics, selfType);
                Type expectedParam = expected.parameters().get(i);
                if (!assignable(actualParam, expectedParam) || !assignable(expectedParam, actualParam)) {
                    throw new IllegalArgumentException("override parameter " + (i + 1) + " for " + method.name() + " has type " + actualParam + " but builtin method uses " + expectedParam);
                }
            }
            Type actualReturn = resolve(method.returnType(), generics, selfType);
            if (!assignable(actualReturn, expected.result()) || !assignable(expected.result(), actualReturn)) {
                throw new IllegalArgumentException("override return type for " + method.name() + " has type " + actualReturn + " but builtin method uses " + expected.result());
            }

            Env env = new Env(null);
            env.define("self", selfType, Ast.BindingKind.VAL);
            for (Ast.Param param : method.parameters()) env.define(param.name(), resolveParam(param, generics, selfType), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            checkBlock(method.body(), env, generics, actualReturn, selfType);
            if (actualReturn != Primitive.VOID && !definitelyReturns(method.body())) {
                throw new IllegalArgumentException("non-void anonymous override '" + method.name() + "' must explicitly return on every path");
            }
        }
    }

    private void validateAnonymousClassMethods(Ast.ClassDecl klass, List<Ast.MethodDecl> methods, Type selfType, Env outer) {
        Set<String> signatures = new HashSet<>();
        for (Ast.MethodDecl method : methods) {
            if (!hasOverrideAnnotation(method)) throw new IllegalArgumentException("anonymous method '" + method.name() + "' must be annotated @override");
            if (!signatures.add(methodKey(method.name(), method.arity()))) throw new IllegalArgumentException("duplicate anonymous override '" + method.name() + "' with arity " + method.arity());
            Ast.MethodDecl base = findMethodInParentsOrSelf(klass, method.name(), method.arity(), new LinkedHashSet<>());
            if (base == null) throw new IllegalArgumentException("no method '" + klass.name() + "." + method.name() + "' with arity " + method.arity() + " to override");

            Set<String> generics = new HashSet<>(klass.genericParameters());
            generics.addAll(method.genericParameters());
            Function expected = functionType(base.parameters(), base.returnType(), generics, selfType);
            Function actual = functionType(method.parameters(), method.returnType(), generics, selfType);
            if (!assignable(actual, expected) || !assignable(expected, actual)) {
                throw new IllegalArgumentException("anonymous override '" + method.name() + "' is not signature-compatible with " + klass.name());
            }
            Env env = new Env(null);
            env.define("self", selfType, Ast.BindingKind.VAL);
            for (Ast.Param param : method.parameters()) env.define(param.name(), resolveParam(param, generics, selfType), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            Type returns = resolve(method.returnType(), generics, selfType);
            checkBlock(method.body(), env, generics, returns, selfType);
            if (returns != Primitive.VOID && !definitelyReturns(method.body())) {
                throw new IllegalArgumentException("non-void anonymous override '" + method.name() + "' must explicitly return on every path");
            }
        }
    }

    private Ast.MethodDecl findMethodInParents(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl method : parent.methods()) {
                if (!method.isStatic() && method.name().equals(name) && method.arity() == arity) {
                    seen.remove(klass);
                    return method;
                }
            }
            Ast.MethodDecl nested = findMethodInParents(parent, name, arity, seen);
            if (nested != null) {
                seen.remove(klass);
                return nested;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.MethodDecl findMethodInParentsOrSelf(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name) && method.arity() == arity) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.MethodDecl method = findMethodInParentsOrSelf(parent, name, arity, seen);
            if (method != null) {
                seen.remove(klass);
                return method;
            }
        }
        seen.remove(klass);
        return null;
    }

    private void ensureInstantiable(Ast.ClassDecl klass, List<Ast.MethodDecl> anonymousMethods, boolean anonymousBody) {
        Map<String, Ast.MethodDecl> overrides = new HashMap<>();
        for (Ast.MethodDecl method : anonymousMethods) overrides.put(methodKey(method.name(), method.arity()), method);

        Map<String, Ast.MethodDecl> abstractMethods = new LinkedHashMap<>();
        collectAbstractMethods(klass, abstractMethods, new LinkedHashSet<>());
        for (Map.Entry<String, Ast.MethodDecl> required : abstractMethods.entrySet()) {
            Ast.MethodDecl resolved = findMethod(klass, required.getValue().name(), required.getValue().arity(), new LinkedHashSet<>());
            if (resolved != null && !resolved.isAbstract()) continue;
            if (!overrides.containsKey(required.getKey())) {
                throw new IllegalArgumentException("cannot instantiate abstract class '" + klass.name() + "'; missing override for " + required.getValue().name() + "/" + required.getValue().arity());
            }
        }
        if (klass.isAbstract() && abstractMethods.isEmpty() && !anonymousBody) {
            throw new IllegalArgumentException("cannot instantiate abstract class '" + klass.name() + "' directly; use an anonymous implementation body");
        }
    }

    private void collectAbstractMethods(Ast.ClassDecl klass, Map<String, Ast.MethodDecl> out, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return;
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) collectAbstractMethods(parent, out, seen);
        }
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic()) continue;
            String key = methodKey(method.name(), method.arity());
            if (method.isAbstract()) out.put(key, method);
            else out.remove(key);
        }
        seen.remove(klass);
    }

    private Ast.TypeRef substituteAliasType(Ast.TypeRef ref, Map<String, Ast.TypeRef> substitutions) {
        Ast.TypeRef replacement = substitutions.get(ref.name());
        if (replacement != null && ref.arguments().isEmpty() && !ref.inferArguments()) return replacement;
        return new Ast.TypeRef(
                ref.name(),
                ref.arguments().stream().map(arg -> substituteAliasType(arg, substitutions)).toList(),
                ref.inferArguments());
    }

    private Type nominalClassType(Ast.ClassDecl klass) {
        return new Named(qualifiedClassName(klass), klass.genericParameters().stream().map(Generic::new).map(Type.class::cast).toList());
    }

    private String qualifiedClassName(Ast.ClassDecl klass) {
        String owner = classOwners.get(klass);
        return owner == null || owner.equals("__root__") ? klass.name() : owner + "." + klass.name();
    }

    private String qualifiedInterfaceName(Ast.InterfaceDecl iface) {
        String owner = interfaceOwners.get(iface);
        return owner == null || owner.equals("__root__") ? iface.name() : owner + "." + iface.name();
    }

    private boolean assignable(Type actual, Type expected) {
        if (Types.isAssignable(actual, expected)) return true;

        if (actual instanceof Named actualNamed && expected instanceof Record targetShape) {
            Ast.ClassDecl klass = findClass(actualNamed.name());
            return klass != null && Types.isAssignable(publicClassShape(klass, new LinkedHashSet<>()), targetShape);
        }

        if (actual instanceof Named actualNamed && expected instanceof Named expectedNamed) {
            Ast.ClassDecl actualClass = findClass(actualNamed.name());
            if (actualClass != null) {
                Ast.ClassDecl expectedClass = findClass(expectedNamed.name());
                if (expectedClass != null && classExtends(actualClass, expectedClass, new LinkedHashSet<>())) return true;
                Ast.InterfaceDecl expectedInterface = findInterface(expectedNamed.name());
                if (expectedInterface != null && classImplements(actualClass, expectedInterface, new LinkedHashSet<>())) return true;
            }
        }
        return false;
    }

    private boolean classExtends(Ast.ClassDecl actual, Ast.ClassDecl expected, Set<Ast.ClassDecl> seen) {
        if (actual == expected) return true;
        if (!seen.add(actual)) return false;
        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, actual);
            if (parent != null && classExtends(parent, expected, seen)) return true;
        }
        return false;
    }

    private boolean classImplements(Ast.ClassDecl klass, Ast.InterfaceDecl expected, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return false;
        for (Ast.TypeRef ifaceRef : klass.interfaces()) {
            Ast.InterfaceDecl iface = findInterface(ifaceRef.name());
            if (iface != null && (iface == expected || interfaceExtends(iface, expected, new LinkedHashSet<>()))) return true;
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null && classImplements(parent, expected, seen)) return true;
        }
        return false;
    }

    private boolean interfaceExtends(Ast.InterfaceDecl actual, Ast.InterfaceDecl expected, Set<Ast.InterfaceDecl> seen) {
        if (actual == expected) return true;
        if (!seen.add(actual)) return false;
        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent != null && interfaceExtends(parent, expected, seen)) return true;
        }
        return false;
    }

    private Ast.FunctionDecl findFunction(String name) {
        if (ambiguousFunctions.contains(name)) throw new IllegalArgumentException("ambiguous function/routine name '" + name + "'; qualify it with its module");
        return functions.get(name);
    }

    private Ast.ClassDecl findClass(String name) {
        if (ambiguousClasses.contains(name)) throw new IllegalArgumentException("ambiguous class name '" + name + "'; qualify it with its module");
        return classes.get(name);
    }

    private Ast.InterfaceDecl findInterface(String name) {
        if (ambiguousInterfaces.contains(name)) throw new IllegalArgumentException("ambiguous interface name '" + name + "'; qualify it with its module");
        return interfaces.get(name);
    }

    private Ast.TypeAliasDecl findTypeAlias(String name) {
        if (ambiguousTypeAliases.contains(name)) throw new IllegalArgumentException("ambiguous type alias '" + name + "'; qualify it with its module");
        return typeAliases.get(name);
    }

    private Set<String> uniqueGenerics(List<String> names, String owner) {
        Set<String> result = new HashSet<>();
        for (String name : names) if (!result.add(name)) throw new IllegalArgumentException("duplicate generic '" + name + "' in " + owner);
        return result;
    }

    private boolean constant(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr) return true;
        if (expr instanceof Ast.UnaryExpr unary) return constant(unary.operand());
        if (expr instanceof Ast.BinaryExpr binary) return constant(binary.left()) && constant(binary.right());
        if (expr instanceof Ast.ConditionalExpr conditional) return constant(conditional.condition()) && constant(conditional.whenTrue()) && constant(conditional.whenFalse());
        if (expr instanceof Ast.ListExpr list) return list.elements().stream().allMatch(this::constant);
        if (expr instanceof Ast.TupleExpr tuple) return tuple.elements().stream().allMatch(this::constant);
        if (expr instanceof Ast.ObjectExpr object) return object.fields().stream().allMatch(field -> constant(field.value()));
        return false;
    }

    private boolean definitelyReturns(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt) return true;
            if (stmt instanceof Ast.IfStmt conditional) {
                boolean allBranches = !conditional.branches().isEmpty()
                        && conditional.branches().stream().allMatch(branch -> definitelyReturns(branch.body()))
                        && !conditional.elseBody().isEmpty()
                        && definitelyReturns(conditional.elseBody());
                if (allBranches) return true;
            }
            if (stmt instanceof Ast.TryStmt attempted) {
                if (definitelyReturns(attempted.finallyBody())) return true;
                if (definitelyReturns(attempted.body()) && definitelyReturns(attempted.catchBody())) return true;
            }
        }
        return false;
    }

    private boolean hasInferredArgs(Ast.TypeRef ref) {
        return ref.inferArguments() || ref.arguments().stream().anyMatch(this::hasInferredArgs);
    }

    private void requireAssignable(Type actual, Type expected, String where) {
        if (!assignable(actual, expected)) throw new IllegalArgumentException(where + " has type " + actual + " but expected " + expected);
    }

    private String methodKey(String name, int arity) {
        return name + "$arity" + arity;
    }

    private void validateRoutineRecursion() {
        for (Ast.FunctionDecl fn : functionOwners.keySet()) {
            if (fn.kind() != Ast.CallableKind.ROUTINE) continue;
            if (reaches(fn, fn, new LinkedHashSet<>())) {
                throw new IllegalArgumentException("routine '" + functionOwners.get(fn) + "." + fn.name()
                        + "' participates in recursion; routines are non-recursive by contract (use fnc or a lambda)");
            }
        }
    }

    private boolean reaches(Ast.FunctionDecl current, Ast.FunctionDecl target, Set<Ast.FunctionDecl> path) {
        if (!path.add(current)) return false;
        String module = functionOwners.get(current);
        for (Ast.FunctionDecl callee : directCallees(current.body(), module)) {
            if (callee == target) return true;
            if (reaches(callee, target, path)) return true;
        }
        path.remove(current);
        return false;
    }

    private Set<Ast.FunctionDecl> directCallees(List<Ast.Stmt> body, String module) {
        Set<Ast.FunctionDecl> result = new LinkedHashSet<>();
        for (Ast.Stmt stmt : body) collectCalls(stmt, module, result);
        return result;
    }

    private void collectCalls(Ast.Stmt stmt, String module, Set<Ast.FunctionDecl> out) {
        if (stmt instanceof Ast.BindingStmt s) collectCalls(s.initializer(), module, out);
        else if (stmt instanceof Ast.DestructureStmt s) collectCalls(s.initializer(), module, out);
        else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) collectCalls(s.value(), module, out);
        else if (stmt instanceof Ast.ExprStmt s) collectCalls(s.expression(), module, out);
        else if (stmt instanceof Ast.DeferStmt s) collectCalls(s.expression(), module, out);
        else if (stmt instanceof Ast.IfStmt s) {
            for (Ast.IfBranch b : s.branches()) {
                collectCalls(b.condition(), module, out);
                for (Ast.Stmt nested : b.body()) collectCalls(nested, module, out);
            }
            for (Ast.Stmt nested : s.elseBody()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.TryStmt s) {
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
            for (Ast.Stmt nested : s.catchBody()) collectCalls(nested, module, out);
            for (Ast.Stmt nested : s.finallyBody()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.ForOfStmt s) {
            collectCalls(s.iterable(), module, out);
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.ForStmt s) {
            if (s.initializer() != null) collectCalls(s.initializer(), module, out);
            if (s.condition() != null) collectCalls(s.condition(), module, out);
            if (s.update() != null) collectCalls(s.update(), module, out);
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
        }
    }

    private void collectCalls(Ast.Expr expr, String module, Set<Ast.FunctionDecl> out) {
        if (expr instanceof Ast.CallExpr call) {
            Ast.FunctionDecl callee = resolveStaticCall(call.callee(), module);
            if (callee != null) out.add(callee);
            collectCalls(call.callee(), module, out);
            for (Ast.Expr arg : call.arguments()) collectCalls(arg, module, out);
        } else if (expr instanceof Ast.BinaryExpr e) {
            collectCalls(e.left(), module, out); collectCalls(e.right(), module, out);
        } else if (expr instanceof Ast.UnaryExpr e) collectCalls(e.operand(), module, out);
        else if (expr instanceof Ast.AssignExpr e) collectCalls(e.value(), module, out);
        else if (expr instanceof Ast.ConditionalExpr e) {
            collectCalls(e.condition(), module, out); collectCalls(e.whenTrue(), module, out); collectCalls(e.whenFalse(), module, out);
        } else if (expr instanceof Ast.MemberExpr e) collectCalls(e.receiver(), module, out);
        else if (expr instanceof Ast.IndexExpr e) { collectCalls(e.receiver(), module, out); collectCalls(e.index(), module, out); }
        else if (expr instanceof Ast.NewExpr e) for (Ast.Expr arg : e.arguments()) collectCalls(arg, module, out);
        else if (expr instanceof Ast.AwaitExpr e) collectCalls(e.expression(), module, out);
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr item : e.elements()) collectCalls(item, module, out);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr item : e.elements()) collectCalls(item, module, out);
        else if (expr instanceof Ast.ObjectExpr e) for (Ast.ObjectField f : e.fields()) collectCalls(f.value(), module, out);
        else if (expr instanceof Ast.LambdaExpr e) {
            if (e.expressionBody() != null) collectCalls(e.expressionBody(), module, out);
            if (e.blockBody() != null) for (Ast.Stmt nested : e.blockBody()) collectCalls(nested, module, out);
        }
    }

    private Ast.FunctionDecl resolveStaticCall(Ast.Expr callee, String module) {
        if (callee instanceof Ast.NameExpr name) {
            Ast.FunctionDecl local = functions.get(module + "." + name.name());
            if (local != null) return local;
            return ambiguousFunctions.contains(name.name()) ? null : functions.get(name.name());
        }
        if (callee instanceof Ast.MemberExpr member && member.receiver() instanceof Ast.NameExpr namespace) {
            return functions.get(namespace.name() + "." + member.member());
        }
        return null;
    }

    private static final class Env {
        private final Env parent;
        private final Map<String, Binding> bindings = new HashMap<>();
        private Env(Env parent) { this.parent = parent; }
        private void define(String name, Type type, Ast.BindingKind kind) {
            if (bindings.putIfAbsent(name, new Binding(type, kind)) != null) throw new IllegalArgumentException("duplicate binding '" + name + "'");
        }
        private Binding lookup(String name) {
            Binding binding = bindings.get(name);
            return binding != null ? binding : parent == null ? null : parent.lookup(name);
        }
        private void replace(String name, Type type, Ast.BindingKind kind) {
            if (!bindings.containsKey(name)) throw new IllegalArgumentException("unknown binding '" + name + "'");
            bindings.put(name, new Binding(type, kind));
        }
        private record Binding(Type type, Ast.BindingKind kind) { }
    }
}
