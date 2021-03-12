/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.redefinition.plugins.impl;

import com.oracle.truffle.espresso.descriptors.Symbol;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.jdwp.api.KlassRef;
import com.oracle.truffle.espresso.redefinition.plugins.api.RedefineObject;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.StaticObject;

import java.lang.ref.WeakReference;

public abstract class RedefineObjectImpl implements RedefineObject {

    protected final WeakReference<StaticObject> instance;
    protected final Klass klass;

    protected RedefineObjectImpl(StaticObject object) {
        this.instance = new WeakReference<>(object);
        this.klass = object.getKlass();
    }

    public RedefineObjectImpl(KlassRef klass) {
        this.instance = new WeakReference<>(StaticObject.NULL);
        this.klass = (Klass) klass;
    }

    @Override
    public Klass getKlass() {
        return klass;
    }

    @Override
    public abstract RedefineObject invokeRaw(String name, RedefineObject... args) throws NoSuchMethodException;

    @SuppressWarnings("unused")
    public RedefineObject invokePrecise(String className, String methodName, RedefineObject... args) throws NoSuchMethodException {
        throw new NoSuchMethodException(className + "." + methodName);
    }

    @Override
    public abstract RedefineObject getInstanceField(String fieldName) throws NoSuchFieldException;

    protected Method lookupMethod(String name, RedefineObject[] args) throws NoSuchMethodException {
        StaticObject theInstance = instance.get();
        if (theInstance == null) {
            throw new IllegalStateException("cannot invoke method on garbage collection instance");
        }

        Klass currentKlass = klass;

        while (currentKlass != null) {
            Method method = lookupMethod(currentKlass, name, args);
            if (method != null) {
                return method;
            }
            ObjectKlass[] interfaces = currentKlass.getInterfaces();
            for (ObjectKlass itf : interfaces) {
                method = lookupMethod(itf, name, args);
                if (method != null && !method.isAbstract()) {
                    return method;
                }
            }
            currentKlass = currentKlass.getSuperKlass();
        }
        return null;
    }

    protected Method lookupMethod(Klass klassRef, String name, RedefineObject[] args) throws NoSuchMethodException {
        for (Method declaredMethod : klassRef.getDeclaredMethods()) {
            if (declaredMethod.getNameAsString().equals(name)) {
                // match arguments
                boolean match = true;
                if (declaredMethod.getParameterCount() == args.length) {
                    Klass[] parameters = declaredMethod.resolveParameterKlasses();
                    for (int i = 0; i < args.length; i++) {
                        if (!parameters[i].isAssignableFrom((Klass) args[i].getKlass())) {
                            match = false;
                            break;
                        }
                    }
                } else if (declaredMethod.isVarargs()) {
                    // TODO - not implemented yet
                    throw new NoSuchMethodException("varargs lookup not implemented");
                }
                if (match) {
                    return declaredMethod;
                }
            }
        }
        return null;
    }

    protected Object[] rawObjects(RedefineObjectImpl[] args) {
        Object[] result = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            result[i] = args[i].instance;
        }
        return result;
    }

    public boolean notNull() {
        return instance != null && instance.get() != StaticObject.NULL;
    }

    public RedefineObject fromType(String className) {
        EspressoContext context = instance.get().getKlass().getContext();
        Symbol<Symbol.Type> type = context.getTypes().fromClassGetName(className);
        Klass loadedClass = context.getRegistries().findLoadedClass(type, instance.get().getKlass().getDefiningClassLoader());
        if (loadedClass != null) {
            return new CachedRedefineObject(loadedClass.mirror());
        }
        return null;
    }
}
