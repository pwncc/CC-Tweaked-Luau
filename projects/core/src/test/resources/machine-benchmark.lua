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

-- Window compositing: a child window writing through a parent window onto
-- the terminal, with periodic scrolls and full redraws - the multishell /
-- MineOS hot path.
local function windowWorkload(create)
    return function()
        local t = term.native and term.native() or term
        local root = create(t, 1, 1, 51, 19)
        local child = create(root, 2, 2, 40, 10)
        for i = 1, 2e4 do
            child.setCursorPos(1, i % 10 + 1)
            child.write("The quick brown fox jumps over the lazy")
            if i % 100 == 0 then
                child.scroll(1)
                root.redraw()
            end
        end
    end
end

bench("window_api", windowWorkload(window.create))

bench("fs_lines", function()
    -- Line-by-line file IO: write then re-read a 20,000 line log.
    local handle = fs.open("bench.txt", "w")
    for i = 1, 2e4 do
        handle.writeLine("[12:34:56] a log line with some content #" .. i)
    end
    handle.close()

    local count = 0
    for _ = 1, 5 do
        local read = fs.open("bench.txt", "r")
        while true do
            local line = read.readLine()
            if not line then break end
            count = count + #line
        end
        read.close()
    end
    fs.delete("bench.txt")
    return count
end)

-- On runtimes with a native window API, also benchmark the reference Lua
-- implementation for comparison.
if _CC_NATIVE_WINDOW then
    local env = setmetatable({ _CC_NATIVE_WINDOW = false }, { __index = _ENV })
    local fn = loadfile("rom/apis/window.lua", nil, env)
    if fn then
        fn()
        bench("window_lua", windowWorkload(env.create))
    end
end

-- Pixel text rasterisation (the MineOS UI hot path): draw onto a large
-- offscreen window via mineos.gfx, natively and via the Lua fallback.
local function gfxWorkload(gfx)
    return function()
        local t = term.native and term.native() or term
        local canvas = gfx.new(window.create(t, 1, 1, 300, 60, false))
        for i = 1, 2e3 do
            canvas:rect(1, 1, 300, 60, 2 ^ (i % 16))
            canvas:text(1, 1, "The quick brown fox jumps over it", 2 ^ (i % 16), colours.black, 2, 1)
            canvas:text(1, 20, "MineOS pixel user interface text", colours.white, 2 ^ (i % 16), 3, 2)
        end
    end
end

local haveGfx, gfxModule = pcall(require, "mineos.gfx")
if haveGfx then
    bench("gfx_text", gfxWorkload(gfxModule))

    if _CC_NATIVE_GFX then
        local env = setmetatable({ _CC_NATIVE_GFX = false }, { __index = _ENV })
        local fn = loadfile("rom/modules/main/mineos/gfx.lua", nil, env)
        if fn then
            bench("gfx_text_lua", gfxWorkload(fn()))
        end
    end
end

benchmark.finish()
