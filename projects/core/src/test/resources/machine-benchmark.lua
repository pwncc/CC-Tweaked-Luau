-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

-- Runtime benchmark suite, run by LuaMachineBenchmark.java on both the Cobalt
-- and Luau machines. Each workload must stay comfortably under the 7 second
-- computer timeout on both runtimes; we yield between workloads to reset the
-- timer.

local function bench(name, f)
    os.queueEvent("bench") os.pullEvent("bench")
    local start = os.epoch("utc")
    f()
    local duration = os.epoch("utc") - start
    benchmark.submit(name, duration)
end

bench("numeric_loop", function()
    local s = 0
    for i = 1, 5e7 do s = s + i * 0.5 end
    return s
end)

bench("table_array", function()
    local t = {}
    for i = 1, 2e6 do t[i] = i end
    local s = 0
    for i = 1, 2e6 do s = s + t[i] end
    return s
end)

bench("table_hash", function()
    local t = {}
    for i = 1, 1e6 do t["key" .. (i % 4096)] = i end
    return t
end)

bench("string_ops", function()
    local s
    for i = 1, 1e5 do
        s = ("value %d"):format(i):upper():gsub("E", "e")
    end
    return s
end)

bench("string_concat", function()
    local parts = {}
    for i = 1, 2e5 do parts[i] = "chunk" .. i end
    return table.concat(parts)
end)

bench("function_calls", function()
    local function fib(n)
        if n < 2 then return n end
        return fib(n - 1) + fib(n - 2)
    end
    return fib(30)
end)

bench("coroutines", function()
    local co = coroutine.create(function()
        while true do coroutine.yield() end
    end)
    for _ = 1, 5e5 do coroutine.resume(co) end
end)

bench("java_calls", function()
    local epoch = os.epoch
    for _ = 1, 2e5 do epoch("utc") end
end)

bench("term_redraw", function()
    -- A realistic API-heavy workload: redrawing 10 lines of a terminal,
    -- 5,000 times (100,000 Java calls with real method bodies).
    local t = term.native and term.native() or term
    local setCursorPos, write = t.setCursorPos, t.write
    for _ = 1, 5e3 do
        for y = 1, 10 do
            setCursorPos(1, y)
            write("The quick brown fox jumps over the lazy dog")
        end
    end
end)

bench("pattern_match", function()
    local text = ("the quick brown fox jumps over the lazy dog "):rep(50)
    local count = 0
    for _ = 1, 2e4 do
        for _ in text:gmatch("%a+") do count = count + 1 end
    end
    return count
end)

benchmark.finish()
