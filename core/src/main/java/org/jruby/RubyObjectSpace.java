/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v20.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2001 Alan Moore <alan_moore@gmx.net>
 * Copyright (C) 2001-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/

package org.jruby;

import java.util.ArrayList;
import static org.jruby.RubyEnumerator.enumeratorize;

import java.util.Iterator;

import org.jruby.anno.JRubyMethod;
import org.jruby.anno.JRubyModule;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import static org.jruby.runtime.Visibility.*;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.collections.WeakValuedIdentityMap;
import org.jruby.util.func.Function1;

@JRubyModule(name="ObjectSpace")
public class RubyObjectSpace {

    /** Create the ObjectSpace module and add it to the Ruby runtime.
     *
     */
    public static RubyModule createObjectSpaceModule(Ruby runtime) {
        RubyModule objectSpaceModule = runtime.defineModule("ObjectSpace");
        runtime.setObjectSpaceModule(objectSpaceModule);

        objectSpaceModule.defineAnnotatedMethods(RubyObjectSpace.class);

        WeakMap.createWeakMap(runtime);

        return objectSpaceModule;
    }

    @JRubyMethod(required = 1, optional = 1, module = true, visibility = PRIVATE)
    public static IRubyObject define_finalizer(IRubyObject recv, IRubyObject[] args, Block block) {
        Ruby runtime = recv.getRuntime();
        IRubyObject finalizer;
        if (args.length == 2) {
            finalizer = args[1];
            if (!finalizer.respondsTo("call")) {
                throw runtime.newArgumentError("wrong type argument " + finalizer.getType() + " (should be callable)");
            }
        } else {
            finalizer = runtime.newProc(Block.Type.PROC, block);
        }
        IRubyObject obj = args[0];
        runtime.getObjectSpace().addFinalizer(obj, finalizer);
        return runtime.newArray(RubyFixnum.zero(runtime), finalizer);
    }

    @JRubyMethod(required = 1, module = true, visibility = PRIVATE)
    public static IRubyObject undefine_finalizer(IRubyObject recv, IRubyObject obj, Block block) {
        recv.getRuntime().getObjectSpace().removeFinalizers(RubyNumeric.fix2long(obj.id()));
        return recv;
    }

    @JRubyMethod(name = "_id2ref", required = 1, module = true, visibility = PRIVATE)
    public static IRubyObject id2ref(IRubyObject recv, IRubyObject id) {
        final Ruby runtime = id.getRuntime();
        if (!(id instanceof RubyFixnum)) {
            throw runtime.newTypeError(id, runtime.getFixnum());
        }
        long longId = ((RubyFixnum) id).getLongValue();
        if (longId == 0) {
            return runtime.getFalse();
        } else if (longId == 20) {
            return runtime.getTrue();
        } else if (longId == 8) {
            return runtime.getNil();
        } else if (longId % 2 != 0) {
            // odd
            return runtime.newFixnum((longId - 1) / 2);
        } else {
            if (runtime.isObjectSpaceEnabled()) {
                IRubyObject object = runtime.getObjectSpace().id2ref(longId);
                if (object == null) {
                    return runtime.getNil();
                }
                return object;
            } else {
                runtime.getWarnings().warn("ObjectSpace is disabled; _id2ref only supports immediates, pass -X+O to enable");
                throw runtime.newRangeError(String.format("0x%016x is not id value", longId));
            }
        }
    }

    public static IRubyObject each_objectInternal(final ThreadContext context, IRubyObject recv, IRubyObject[] args, final Block block) {
        final Ruby runtime = context.runtime;
        final RubyModule rubyClass;
        if (args.length == 0) {
            rubyClass = runtime.getObject();
        } else {
            if (!(args[0] instanceof RubyModule)) throw runtime.newTypeError("class or module required");
            rubyClass = (RubyModule) args[0];
        }
        if (rubyClass == runtime.getClassClass() || rubyClass == runtime.getModule()) {

            final ArrayList<IRubyObject> modules = new ArrayList<>(96);
            runtime.eachModule(new Function1<Object, IRubyObject>() {
                public Object apply(IRubyObject arg1) {
                    if (rubyClass.isInstance(arg1)) {
                        if (arg1 instanceof IncludedModule) {
                            // do nothing for included wrappers or singleton classes
                        } else {
                            modules.add(arg1); // store the module to avoid concurrent modification exceptions
                        }
                    }
                    return null;
                }
            });

            final int count = modules.size();
            for (int i = 0; i<count; i++) {
                block.yield(context, modules.get(i));
            }
            return runtime.newFixnum(count);
        }
        if (rubyClass.getClass() == MetaClass.class) {
            // each_object(Cls.singleton_class) is basically a walk of Cls and all descendants of Cls.
            // In other words, this is walking all instances of Cls's singleton class and its subclasses.
            IRubyObject attached = ((MetaClass) args[0]).getAttached();
            block.yield(context, attached); int count = 1;
            if (attached instanceof RubyClass) {
                for (RubyClass child : ((RubyClass) attached).subclasses(true)) {
                    if (child instanceof IncludedModule) {
                        // do nothing for included wrappers or singleton classes
                    } else {
                        count++; block.yield(context, child);
                    }
                }
            }
            return runtime.newFixnum(count);
        }
        if ( ! runtime.isObjectSpaceEnabled() ) {
            throw runtime.newRuntimeError("ObjectSpace is disabled; each_object will only work with Class, pass -X+O to enable");
        }
        final Iterator iter = runtime.getObjectSpace().iterator(rubyClass);
        IRubyObject obj; int count = 0;
        while ((obj = (IRubyObject) iter.next()) != null) {
            count++; block.yield(context, obj);
        }
        return runtime.newFixnum(count);
    }

    @JRubyMethod(name = "each_object", optional = 1, module = true, visibility = PRIVATE)
    public static IRubyObject each_object(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        return block.isGiven() ? each_objectInternal(context, recv, args, block) : enumeratorize(context.runtime, recv, "each_object", args);
    }

    @JRubyMethod(name = "garbage_collect", module = true, visibility = PRIVATE)
    public static IRubyObject garbage_collect(ThreadContext context, IRubyObject recv) {
        return RubyGC.start(context, recv);
    }

    public static class WeakMap extends RubyObject {
        static void createWeakMap(Ruby runtime) {
            RubyClass weakMap = runtime.getObjectSpaceModule().defineClassUnder("WeakMap", runtime.getObject(), new ObjectAllocator() {
                public IRubyObject allocate(Ruby runtime, RubyClass klazz) {
                    return new WeakMap(runtime, klazz);
                }
            });

            weakMap.defineAnnotatedMethods(WeakMap.class);
        }

        public WeakMap(Ruby runtime, RubyClass cls) {
            super(runtime, cls);
        }

        @JRubyMethod(name = "[]")
        public IRubyObject op_aref(ThreadContext context, IRubyObject key) {
            IRubyObject value = map.get(key);
            if (value != null) return value;
            return context.nil;
        }

        @JRubyMethod(name = "[]=")
        public IRubyObject op_aref(ThreadContext context, IRubyObject key, IRubyObject value) {
            map.put(key, value);
            return context.runtime.newFixnum(System.identityHashCode(value));
        }

        @JRubyMethod(name = "key?")
        public IRubyObject key_p(ThreadContext context, IRubyObject key) {
            return context.runtime.newBoolean(map.get(key) != null);
        }

        private final WeakValuedIdentityMap<IRubyObject, IRubyObject> map = new WeakValuedIdentityMap<IRubyObject, IRubyObject>();
    }
}
