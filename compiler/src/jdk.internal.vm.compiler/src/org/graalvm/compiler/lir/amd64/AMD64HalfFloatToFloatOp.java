/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package org.graalvm.compiler.lir.amd64;

import static jdk.vm.ci.code.ValueUtil.asRegister;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;

import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.SyncPort;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Value;

// @formatter:off
@SyncPort(from = "https://github.com/openjdk/jdk/blob/83d92672d4c2637fc37ddd873533c85a9b083904/src/hotspot/cpu/x86/macroAssembler_x86.hpp#L207-L210",
          sha1 = "2f4ca87d86c3b184d2b7826e51990a3a3682433f")
// @formatter:on
public final class AMD64HalfFloatToFloatOp extends AMD64LIRInstruction {
    public static final LIRInstructionClass<AMD64HalfFloatToFloatOp> TYPE = LIRInstructionClass.create(AMD64HalfFloatToFloatOp.class);

    @Def({REG}) protected Value dstValue;
    @Use({REG}) protected Value srcValue;

    public AMD64HalfFloatToFloatOp(Value dstValue, Value srcValue) {
        super(TYPE);
        this.dstValue = dstValue;
        this.srcValue = srcValue;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
        Register dst = asRegister(dstValue);
        Register src = asRegister(srcValue);

        masm.movdl(dst, src);
        masm.vcvtph2ps(dst, dst);
    }
}
