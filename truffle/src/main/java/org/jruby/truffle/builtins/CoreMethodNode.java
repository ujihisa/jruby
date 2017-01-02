/*
 * Copyright (c) 2013, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.builtins;

import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import org.jruby.truffle.language.RubyNode;
import org.jruby.truffle.language.SourceIndexLength;

@GenerateNodeFactory
public abstract class CoreMethodNode extends RubyNode {

    public CoreMethodNode() {
    }

    public CoreMethodNode(SourceIndexLength sourceSection) {
        super(sourceSection);
    }

}
