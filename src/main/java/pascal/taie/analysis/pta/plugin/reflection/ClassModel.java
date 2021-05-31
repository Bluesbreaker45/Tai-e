/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.analysis.pta.plugin.reflection;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.ir.exp.ClassLiteral;
import pascal.taie.ir.exp.InvokeVirtual;
import pascal.taie.ir.exp.StringLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.ClassMember;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.StringReps;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.TypeManager;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static pascal.taie.util.collection.MapUtils.addToMapSet;
import static pascal.taie.util.collection.MapUtils.newHybridMap;
import static pascal.taie.util.collection.MapUtils.newMap;

/**
 * Models APIs of java.lang.Class.
 */
class ClassModel {

    private final static String GET_CONSTRUCTOR = "getConstructor";

    private final static String GET_DECLARED_CONSTRUCTOR = "getDeclaredConstructor";

    private final static String GET_METHOD = "getMethod";

    private final static String GET_DECLARED_METHOD = "getDeclaredMethod";

    /**
     * Description for reflection meta objects.
     */
    private final String META_DESC = "ReflectionMetaObj";

    private final Solver solver;

    private final ClassHierarchy hierarchy;

    private final CSManager csManager;

    /**
     * Default heap context for MethodType objects.
     */
    private final Context defaultHctx;

    private final JClass klass;

    private final ClassType constructor;

    private final ClassType method;

    private final ClassType field;

    private final Map<Var, Set<Invoke>> relevantVars = newHybridMap();

    private final Map<ClassMember, MockObj> refObjs = newMap();

    ClassModel(Solver solver) {
        this.solver = solver;
        hierarchy = solver.getHierarchy();
        csManager = solver.getCSManager();
        defaultHctx = solver.getContextSelector().getDefaultContext();
        TypeManager typeManager = solver.getTypeManager();
        klass = hierarchy.getJREClass(StringReps.CLASS);
        constructor = typeManager.getClassType(StringReps.CONSTRUCTOR);
        method = typeManager.getClassType(StringReps.METHOD);
        field = typeManager.getClassType(StringReps.FIELD);
    }

    void handleNewInvoke(Invoke invoke) {
        MethodRef ref = invoke.getMethodRef();
        if (ref.getDeclaringClass().equals(klass)) {
            switch (ref.getName()) {
                case GET_CONSTRUCTOR:
                case GET_DECLARED_CONSTRUCTOR: {
                    InvokeVirtual invokeExp = (InvokeVirtual) invoke.getInvokeExp();
                    addToMapSet(relevantVars, invokeExp.getBase(), invoke);
                    break;
                }
                case GET_METHOD:
                case GET_DECLARED_METHOD: {
                    InvokeVirtual invokeExp = (InvokeVirtual) invoke.getInvokeExp();
                    addToMapSet(relevantVars, invokeExp.getBase(), invoke);
                    addToMapSet(relevantVars, invokeExp.getArg(0), invoke);
                    break;
                }
            }
        }
    }

    boolean isRelevantVar(Var var) {
        return relevantVars.containsKey(var);
    }

    void handleNewPointsToSet(CSVar csVar, PointsToSet pts) {
        relevantVars.get(csVar.getVar()).forEach(invoke -> {
            switch (invoke.getMethodRef().getName()) {
                case GET_CONSTRUCTOR: {
                    handleGetConstructor(csVar, pts, invoke);
                    break;
                }
                case GET_DECLARED_CONSTRUCTOR: {
                    handleGetDeclaredConstructor(csVar, pts, invoke);
                    break;
                }
                case GET_METHOD: {
                    handleGetMethod(csVar, pts, invoke);
                    break;
                }
                case GET_DECLARED_METHOD: {
                    handleGetDeclaredMethod(csVar, pts, invoke);
                    break;
                }
            }
        });
    }

    private void handleGetConstructor(CSVar csVar, PointsToSet pts, Invoke invoke) {
        Var result = invoke.getResult();
        if (result != null) {
            PointsToSet ctorObjs = PointsToSetFactory.make();
            pts.forEach(obj -> {
                JClass jclass = toClass(obj);
                if (jclass != null) {
                    ReflectionUtils.getConstructors(jclass)
                            .map(ctor -> {
                                Obj ctorObj = getReflectionObj(ctor);
                                return csManager.getCSObj(defaultHctx, ctorObj);
                            })
                            .forEach(ctorObjs::addObject);
                }
            });
            if (!ctorObjs.isEmpty()) {
                solver.addVarPointsTo(csVar.getContext(), result, ctorObjs);
            }
        }
    }

    private void handleGetDeclaredConstructor(CSVar csVar, PointsToSet pts, Invoke invoke) {
        Var result = invoke.getResult();
        if (result != null) {
            PointsToSet ctorObjs = PointsToSetFactory.make();
            pts.forEach(obj -> {
                JClass jclass = toClass(obj);
                if (jclass != null) {
                    ReflectionUtils.getDeclaredConstructors(jclass)
                            .map(ctor -> {
                                Obj ctorObj = getReflectionObj(ctor);
                                return csManager.getCSObj(defaultHctx, ctorObj);
                            })
                            .forEach(ctorObjs::addObject);
                }
            });
            if (!ctorObjs.isEmpty()) {
                solver.addVarPointsTo(csVar.getContext(), result, ctorObjs);
            }
        }
    }

    private void handleGetMethod(CSVar csVar, PointsToSet pts, Invoke invoke) {
        Var result = invoke.getResult();
        if (result != null) {
            List<PointsToSet> args = getArgs(csVar, pts,
                    (InvokeVirtual) invoke.getInvokeExp());
            PointsToSet clsObjs = args.get(0);
            PointsToSet nameObjs = args.get(1);
            PointsToSet mtdObjs = PointsToSetFactory.make();
            clsObjs.forEach(clsObj -> {
                JClass cls = toClass(clsObj);
                if (cls != null) {
                    nameObjs.forEach(nameObj -> {
                        String name = toString(nameObj);
                        if (name != null) {
                            ReflectionUtils.getMethods(cls, name)
                                    .map(mtd -> {
                                        Obj mtdObj = getReflectionObj(mtd);
                                        return csManager.getCSObj(defaultHctx, mtdObj);
                                    })
                                    .forEach(mtdObjs::addObject);
                        }
                    });
                }
            });
            if (!mtdObjs.isEmpty()) {
                solver.addVarPointsTo(csVar.getContext(), result, mtdObjs);
            }
        }
    }

    private void handleGetDeclaredMethod(CSVar csVar, PointsToSet pts, Invoke invoke) {
        Var result = invoke.getResult();
        if (result != null) {
            List<PointsToSet> args = getArgs(csVar, pts,
                    (InvokeVirtual) invoke.getInvokeExp());
            PointsToSet clsObjs = args.get(0);
            PointsToSet nameObjs = args.get(1);
            PointsToSet mtdObjs = PointsToSetFactory.make();
            clsObjs.forEach(clsObj -> {
                JClass cls = toClass(clsObj);
                if (cls != null) {
                    nameObjs.forEach(nameObj -> {
                        String name = toString(nameObj);
                        if (name != null) {
                            ReflectionUtils.getDeclaredMethods(cls, name)
                                    .map(mtd -> {
                                        Obj mtdObj = getReflectionObj(mtd);
                                        return csManager.getCSObj(defaultHctx, mtdObj);
                                    })
                                    .forEach(mtdObjs::addObject);
                        }
                    });
                }
            });
            if (!mtdObjs.isEmpty()) {
                solver.addVarPointsTo(csVar.getContext(), result, mtdObjs);
            }
        }
    }

    /**
     * For invocation m = c.getMethod(n, ...);
     * when points-to set of c or n changes,
     * this convenient method returns points-to sets of c and n.
     * For variable csVar.getVar(), this method returns pts,
     * otherwise, it just returns current points-to set of the variable.
     * @param csVar may be c or n.
     * @param pts changed part of csVar
     * @param invokeExp the call site which contain csVar
     */
    private List<PointsToSet> getArgs(CSVar csVar, PointsToSet pts,
                                      InvokeVirtual invokeExp) {
        PointsToSet basePts, arg0Pts;
        if (csVar.getVar().equals(invokeExp.getBase())) {
            basePts = pts;
            CSVar arg0 = csManager.getCSVar(csVar.getContext(),
                    invokeExp.getArg(0));
            arg0Pts = solver.getPointsToSetOf(arg0);
        } else {
            CSVar base = csManager.getCSVar(csVar.getContext(),
                    invokeExp.getBase());
            basePts = solver.getPointsToSetOf(base);
            arg0Pts = pts;
        }
        return List.of(basePts, arg0Pts);
    }

    /**
     * Converts a CSObj of class to corresponding JClass. If the object is
     * not a class constant, then return null.
     */
    private @Nullable JClass toClass(CSObj csObj) {
        Object alloc = csObj.getObject().getAllocation();
        if (alloc instanceof ClassLiteral) {
            ClassLiteral klass = (ClassLiteral) alloc;
            Type type = klass.getTypeValue();
            if (type instanceof ClassType) {
                return ((ClassType) type).getJClass();
            } else if (type instanceof ArrayType) {
                return hierarchy.getJREClass(StringReps.OBJECT);
            }
        }
        return null;
    }

    /**
     * Converts a CSObj of string constant to corresponding String.
     * If the object is not a string constant, then return null.
     */
    private static @Nullable String toString(CSObj csObj) {
        Object alloc = csObj.getObject().getAllocation();
        return alloc instanceof StringLiteral ?
                ((StringLiteral) alloc).getString() : null;
    }

    private MockObj getReflectionObj(ClassMember member) {
        return refObjs.computeIfAbsent(member, mbr -> {
            if (mbr instanceof JMethod) {
                if (((JMethod) mbr).isConstructor()) {
                    return new MockObj(META_DESC, mbr, constructor);
                } else {
                    return new MockObj(META_DESC, mbr, method);
                }
            } else {
                return new MockObj(META_DESC, mbr, field);
            }
        });
    }
}
