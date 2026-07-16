// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.core;

import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.core.computer.mainthread.NoWorkMainThreadScheduler;
import dan200.computercraft.core.filesystem.MemoryMount;
import dan200.computercraft.core.lua.CobaltLuaMachine;
import dan200.computercraft.core.lua.ILuaMachine;
import dan200.computercraft.core.lua.luau.LuauMachine;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.test.core.computer.BasicEnvironment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A simple benchmark comparing the Cobalt and Luau runtimes on CPU-bound Lua workloads.
 * <p>
 * This is skipped by default: run with {@code -Dcc.benchmark=true} (and optionally {@code -Dcc.lua.machine=luau}).
 * Results are printed and written to {@code build/machine-benchmark-<machine>.txt}.
 *
 * @see LuauMachine
 */
public class LuaMachineBenchmark {
    private final Map<String, Long> results = new LinkedHashMap<>();
    private final CountDownLatch finished = new CountDownLatch(1);

    @Test
    public void benchmark() throws Exception {
        Assumptions.assumeTrue(System.getProperty("cc.benchmark") != null, "Benchmarks are disabled (set -Dcc.benchmark=true)");

        var machine = System.getProperty("cc.lua.machine", "cobalt");
        ILuaMachine.Factory factory = switch (machine) {
            case "luau" -> LuauMachine::new;
            default -> CobaltLuaMachine::new;
        };

        String program;
        try (var stream = Objects.requireNonNull(LuaMachineBenchmark.class.getClassLoader().getResourceAsStream("machine-benchmark.lua"))) {
            program = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        var mount = new MemoryMount().addFile("startup.lua", program);
        var environment = new BasicEnvironment(mount);
        var context = ComputerContext.builder(environment)
            .luaFactory(factory)
            .mainThreadScheduler(new NoWorkMainThreadScheduler())
            .build();

        var computer = new Computer(context, environment, new Terminal(80, 30, true), 0);
        computer.addApi(new BenchmarkApi());
        computer.turnOn();

        try {
            var deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            while (finished.getCount() > 0 && System.nanoTime() < deadline) {
                computer.tick();
                if (finished.await(50, TimeUnit.MILLISECONDS)) break;
            }
            assertTrue(finished.getCount() == 0, "Benchmark did not finish. Terminal:\n" + dumpTerminal(computer));
        } finally {
            computer.shutdown();
            context.ensureClosed(10, TimeUnit.SECONDS);
        }

        var report = new StringBuilder();
        report.append("machine=").append(machine).append('\n');
        for (var entry : results.entrySet()) {
            report.append(String.format("%-16s %6d ms%n", entry.getKey(), entry.getValue()));
        }

        System.out.println(report);
        Files.writeString(Path.of("build", "machine-benchmark-" + machine + ".txt"), report.toString());
    }

    private static String dumpTerminal(Computer computer) {
        // We don't have access to the terminal contents here without plumbing; just report liveness.
        return "computer on=" + computer.isOn();
    }

    private class BenchmarkApi implements ILuaAPI {
        @Override
        public String[] getNames() {
            return new String[]{ "benchmark" };
        }

        @LuaFunction
        public final void submit(String name, long duration) {
            results.put(name, duration);
        }

        @LuaFunction
        public final void finish() {
            finished.countDown();
        }
    }

    static {
        // Ensure the build directory exists for the report.
        try {
            Files.createDirectories(Path.of("build"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
