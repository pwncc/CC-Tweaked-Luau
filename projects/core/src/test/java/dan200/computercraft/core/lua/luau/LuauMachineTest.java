// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.core.lua.luau;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.core.asm.LuaMethodSupplier;
import dan200.computercraft.core.computer.TimeoutState;
import dan200.computercraft.core.lua.MachineEnvironment;
import dan200.computercraft.core.lua.MachineException;
import dan200.computercraft.core.metrics.MetricsObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests the {@link LuauMachine}'s bridging of Java methods, in particular {@link MethodResult#pullEvent} yields as
 * used by turtle commands and main-thread tasks.
 */
public class LuauMachineTest {
    /**
     * A bios which calls {@code pull.await} once directly on the machine's root coroutine, and once inside a child
     * coroutine (as CraftOS runs programs), checking the returned values each time.
     */
    private static final String BIOS = """
        local function check(what, got, ...)
            local expected = table.pack(...)
            if got.n ~= expected.n then
                error(what .. ": expected " .. expected.n .. " results, got " .. got.n, 0)
            end
            for i = 1, expected.n do
                if got[i] ~= expected[i] then
                    error(what .. " result " .. i .. ": expected " .. tostring(expected[i]) .. ", got " .. tostring(got[i]), 0)
                end
            end
        end

        local ok, err = pcall(function()
            check("direct", table.pack(pull.await("direct", 1)), "done", 123)

            local result
            local co = coroutine.create(function()
                result = table.pack(pull.await("nested", 2))
            end)
            assert(coroutine.resume(co))
            while coroutine.status(co) ~= "dead" do
                assert(coroutine.resume(co, coroutine.yield()))
            end
            check("nested", result, "done", 123)
        end)

        pull.report(ok, err or "ok")
        while true do coroutine.yield() end
        """;

    @Test
    public void pullEventCallbacksReceiveExactEventArguments() throws Exception {
        Assumptions.assumeTrue(LuauMachine.isAvailable(), "Luau runtime is not available");

        var api = new PullApi();
        var machine = create(BIOS, api);
        try {
            // Boot the machine: it should suspend inside the direct pull.await call.
            assertFalse(machine.handleEvent(null, null).isError());
            assertEquals(0, api.resumes.size());

            // An unrelated event is filtered out and must not resume the callback.
            assertFalse(machine.handleEvent("unrelated", new Object[]{ "x" }).isError());
            assertEquals(0, api.resumes.size());

            // Deliver the matching event: the callback sees exactly the event name and its arguments, not the
            // arguments of the original call.
            assertFalse(machine.handleEvent("pull_me", new Object[]{ "hello", 42.0 }).isError());
            assertEquals(1, api.resumes.size());
            assertArrayEquals(new Object[]{ "pull_me", "hello", 42.0 }, api.resumes.get(0));

            // And likewise for the call routed through a child coroutine.
            assertFalse(machine.handleEvent("pull_me", new Object[]{ "hello", 42.0 }).isError());
            assertEquals(2, api.resumes.size());
            assertArrayEquals(new Object[]{ "pull_me", "hello", 42.0 }, api.resumes.get(1));

            assertNotNull(api.report, "Bios should have reported a result");
            assertArrayEquals(new Object[]{ true, "ok" }, api.report);
        } finally {
            machine.close();
        }
    }

    private static LuauMachine create(String bios, ILuaAPI... apis) throws MachineException, IOException {
        var timeout = new TimeoutState() {
            @Override
            public void refresh() {
            }
        };
        ILuaContext context = task -> {
            throw new LuaException("Main thread tasks are not supported");
        };
        var environment = new MachineEnvironment(
            context, MetricsObserver.discard(), timeout,
            List.of(apis), LuaMethodSupplier.create(List.of()),
            "ComputerCraft (test)"
        );
        return new LuauMachine(environment, new ByteArrayInputStream(bios.getBytes(StandardCharsets.UTF_8)));
    }

    public static class PullApi implements ILuaAPI {
        final List<Object[]> resumes = new ArrayList<>();
        Object @Nullable [] report;

        @Override
        public String[] getNames() {
            return new String[]{ "pull" };
        }

        @LuaFunction
        public final MethodResult await(IArguments args) {
            return MethodResult.pullEvent("pull_me", results -> {
                resumes.add(results);
                return MethodResult.of("done", 123);
            });
        }

        @LuaFunction
        public final void report(IArguments args) throws LuaException {
            report = args.getAll();
        }
    }
}
