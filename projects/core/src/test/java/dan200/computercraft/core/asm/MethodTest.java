// SPDX-FileCopyrightText: 2020 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.core.asm;

import dan200.computercraft.api.lua.*;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IDynamicPeripheral;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.core.computer.ComputerBootstrap;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.core.methods.ObjectSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class MethodTest {
    @Test
    public void testMainThread() {
        ComputerBootstrap.run("assert(main_thread.go() == 123)", x -> x.addApi(new MainThread()), 50);
    }

    @Test
    public void testMainThreadPeripheral() {
        ComputerBootstrap.run("assert(peripheral.call('top', 'go') == 123)",
            x -> x.getEnvironment().setPeripheral(ComputerSide.TOP, new MainThread()),
            50);
    }

    @Test
    public void testMainThreadReturnObject() {
        ComputerBootstrap.run("assert(type(main_thread.complex().go) == \"function\")", x -> x.addApi(new MainThread()), 50);
    }

    @Test
    public void testDynamic() {
        ComputerBootstrap.run(
            "assert(dynamic.foo() == 123, 'foo: ' .. tostring(dynamic.foo()))\n" +
                "assert(dynamic.bar() == 321, 'bar: ' .. tostring(dynamic.bar()))",
            x -> x.addApi(new Dynamic()), 50);
    }

    @Test
    public void testDynamicPeripheral() {
        ComputerBootstrap.run(
            """
                local dynamic = peripheral.wrap('top')
                assert(dynamic.foo() == 123, 'foo: ' .. tostring(dynamic.foo()))
                assert(dynamic.bar() == 321, 'bar: ' .. tostring(dynamic.bar()))""",
            x -> x.getEnvironment().setPeripheral(ComputerSide.TOP, new Dynamic()),
            50
        );
    }

    @Test
    public void testExtra() {
        ComputerBootstrap.run("assert(extra.go() == nil, 'go')\nassert(extra.go2() == 456, 'go2')",
            x -> x.addApi(new ExtraObject()),
            50);
    }

    /**
     * Errors thrown by a peripheral method are attributed to the code that called it.
     * <p>
     * The wrapped case behaves differently to Cobalt (and PUC Lua). {@code peripheral.wrap} builds a forwarding closure
     * which does {@code return peripheral.call(...)}; under a runtime with proper tail calls that closure's frame is
     * replaced, so the error lands on the caller. Luau has no tail calls at all — there is no {@code TAILCALL} opcode —
     * so the frame survives and the error is attributed to peripheral.lua instead. That is a property of the language,
     * not of the error levels: the direct calls below confirm the level machinery itself is correct.
     */
    @Test
    public void testPeripheralThrow() {
        ComputerBootstrap.run(
            """
                local _, err = pcall(function() peripheral.call('top', 'thisThread') end)
                assert(err == '/test.lua:1: !', ("thisThread: %q"):format(err))
                local _, err = pcall(function() peripheral.call('top', 'mainThread') end)
                assert(err == '/test.lua:3: !', ("mainThread: %q"):format(err))

                -- Wrapped calls are attributed to peripheral.lua's forwarding closure; see the Javadoc above.
                local throw = peripheral.wrap('top')
                local _, err = pcall(function() throw.thisThread() end)
                assert(err:match('^/rom/apis/peripheral%\\.lua:%d+: !$'), ("wrapped thisThread: %q"):format(err))
                local _, err = pcall(function() throw.mainThread() end)
                assert(err:match('^/rom/apis/peripheral%\\.lua:%d+: !$'), ("wrapped mainThread: %q"):format(err))""",
            x -> x.getEnvironment().setPeripheral(ComputerSide.TOP, new PeripheralThrow()),
            50
        );
    }

    @Test
    public void testMany() {
        ComputerBootstrap.run(
            """
                assert(many.method_0)
                assert(many.method_39)""",
            x -> x.addApi(new ManyMethods()), 50);
    }

    @Test
    public void testFunction() {
        ComputerBootstrap.run(
            "assert(func.call()(123) == 123)",
            x -> x.addApi(new ReturnFunction()), 50);
    }


    @Test
    public void testModule() {
        ComputerBootstrap.run(
            """
                assert(require "test.module".func() == 123)""",
            x -> x.addApi(new IsModule()), 50);
    }

    public static class MainThread implements ILuaAPI, IPeripheral {
        public final String thread = Thread.currentThread().getName();

        @Override
        public String[] getNames() {
            return new String[]{ "main_thread" };
        }

        @LuaFunction(mainThread = true)
        public final int go() {
            assertThat(Thread.currentThread().getName(), is(thread));
            return 123;
        }

        @LuaFunction(mainThread = true)
        public final Object complex() {
            return this;
        }

        @Override
        public String getType() {
            return "main_thread";
        }

        @Override
        public boolean equals(@Nullable IPeripheral other) {
            return this == other;
        }
    }

    public static class Dynamic implements IDynamicLuaObject, ILuaAPI, IDynamicPeripheral {
        @Override
        public String[] getMethodNames() {
            return new String[]{ "foo" };
        }

        @Override
        public MethodResult callMethod(ILuaContext context, int method, IArguments arguments) {
            return MethodResult.of(123);
        }

        @Override
        public MethodResult callMethod(IComputerAccess computer, ILuaContext context, int method, IArguments arguments) {
            return callMethod(context, method, arguments);
        }

        @LuaFunction
        public final int bar() {
            return 321;
        }

        @Override
        public String[] getNames() {
            return new String[]{ "dynamic" };
        }

        @Override
        public String getType() {
            return "dynamic";
        }

        @Override
        public boolean equals(@Nullable IPeripheral other) {
            return this == other;
        }
    }

    public static class ExtraObject implements ObjectSource, ILuaAPI {
        @Override
        public String[] getNames() {
            return new String[]{ "extra" };
        }

        @LuaFunction
        public final int go2() {
            return 456;
        }

        @Override
        public Iterable<Object> getExtra() {
            return List.of(new GeneratorTest.Basic());
        }
    }

    public static class PeripheralThrow implements IPeripheral {
        @LuaFunction
        @SuppressWarnings("DoNotCallSuggester")
        public final void thisThread() throws LuaException {
            throw new LuaException("!");
        }

        @LuaFunction(mainThread = true)
        @SuppressWarnings("DoNotCallSuggester")
        public final void mainThread() throws LuaException {
            throw new LuaException("!");
        }

        @Override
        public String getType() {
            return "throw";
        }

        @Override
        public boolean equals(@Nullable IPeripheral other) {
            return this == other;
        }
    }

    public static class ManyMethods implements IDynamicLuaObject, ILuaAPI {
        @Override
        public String[] getMethodNames() {
            var methods = new String[40];
            for (var i = 0; i < methods.length; i++) methods[i] = "method_" + i;
            return methods;
        }

        @Override
        public MethodResult callMethod(ILuaContext context, int method, IArguments arguments) {
            return MethodResult.of();
        }

        @Override
        public String[] getNames() {
            return new String[]{ "many" };
        }
    }

    public static class ReturnFunction implements ILuaAPI {
        @LuaFunction
        public final ILuaFunction call() {
            return args -> MethodResult.of(args.getAll());
        }

        @Override
        public String[] getNames() {
            return new String[]{ "func" };
        }
    }

    public static class IsModule implements ILuaAPI {
        @Override
        public String[] getNames() {
            return new String[0];
        }

        @Override
        public @Nullable String getModuleName() {
            return "test.module";
        }

        @LuaFunction
        public final int func() {
            return 123;
        }
    }
}
