-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

--[[- MineOS: a small graphical desktop for advanced computers.

One step up from CraftOS: a desktop with clickable apps, draggable windows, a
file explorer, terminals, and a taskbar with the in-game date and time.

Run with `mineos`, or enable `mineos.autostart` to boot straight into it.
]]

if not term.isColour() then
    printError("MineOS requires an advanced (gold) computer.")
    return
end

-- On runtimes which support it, double the terminal resolution for a much
-- sharper desktop. Restored on exit.
local hires = false
if term.setResolution and term.current().setResolution then
    hires = pcall(term.current().setResolution, 2)
end

local root = term.current()
local W, H = root.getSize()

-- ------------------------------------------------------------------
-- Monitor mirroring: draw everything to the terminal AND any attached
-- monitors, so the desktop is visible from the computer UI and in-world.
-- ------------------------------------------------------------------
local function makeMirror(primary)
    local monitors = {}
    if settings.get("mineos.mirror") then
        for _, name in ipairs(peripheral.getNames()) do
            if peripheral.getType(name) == "monitor" then
                local mon = peripheral.wrap(name)
                pcall(mon.setTextScale, 0.5)
                monitors[#monitors + 1] = mon
            end
        end
    end
    if #monitors == 0 then return primary end

    local mirror = {}
    for key, fn in pairs(primary) do
        if type(fn) == "function" then
            mirror[key] = function(...)
                for _, mon in ipairs(monitors) do
                    local target = mon[key]
                    if target then pcall(target, ...) end
                end
                return fn(...)
            end
        end
    end
    return mirror
end

root = makeMirror(root)
local screen = window.create(root, 1, 1, W, H, true)

-- ------------------------------------------------------------------
-- Theme
-- ------------------------------------------------------------------
local theme = {
    desktop = colours.cyan,
    desktopText = colours.white,
    taskbar = colours.grey,
    taskbarText = colours.white,
    accent = colours.blue,
    titleBar = colours.blue,
    titleText = colours.white,
    titleBarInactive = colours.lightGrey,
    close = colours.red,
    body = colours.white,
    bodyText = colours.black,
}

-- ------------------------------------------------------------------
-- Window manager state
-- ------------------------------------------------------------------
local windows = {} -- Bottom to top. Each: { win, title, x, y, w, h, co, filter, kind }
local dragging = nil -- { window, dx, dy }
local running = true
local startClock = os.clock()

-- Forward declarations.
local resumeWindow, closeWindow, redrawAll, launchApp, APPS

local function focused()
    return windows[#windows]
end

local function findWindow(wnd)
    for i, w in ipairs(windows) do
        if w == wnd then return i end
    end
end

local function drawDesktop()
    screen.setVisible(false)
    screen.setBackgroundColour(theme.desktop)
    screen.clear()

    -- Icons.
    screen.setTextColour(theme.desktopText)
    for i, app in ipairs(APPS) do
        local y = 2 + (i - 1) * 3
        screen.setCursorPos(3, y)
        screen.setBackgroundColour(app.colour)
        screen.setTextColour(colours.white)
        screen.write(" " .. app.icon .. " ")
        screen.setCursorPos(2, y + 1)
        screen.setBackgroundColour(theme.desktop)
        screen.setTextColour(theme.desktopText)
        screen.write(app.name)
    end
    screen.setVisible(true)
end

local function drawTaskbar()
    screen.setVisible(false)
    screen.setCursorPos(1, H)
    screen.setBackgroundColour(theme.taskbar)
    screen.setTextColour(theme.taskbarText)
    screen.clearLine()

    screen.setCursorPos(1, H)
    screen.setBackgroundColour(theme.accent)
    screen.write(" MineOS ")

    -- Open windows.
    screen.setBackgroundColour(theme.taskbar)
    local x = 10
    for _, wnd in ipairs(windows) do
        local label = " " .. wnd.title:sub(1, 8) .. " "
        screen.setCursorPos(x, H)
        screen.setBackgroundColour(wnd == focused() and theme.accent or theme.taskbar)
        screen.write(label)
        wnd.taskbarX, wnd.taskbarW = x, #label
        x = x + #label + 1
    end

    -- Clock: in-game time, day, and how long you've been staring at this screen.
    local uptime = math.floor(os.clock() - startClock)
    local clock = ("%s Day %d  %s "):format(
        textutils.formatTime(os.time(), false),
        os.day(),
        ("wasted %d:%02d"):format(math.floor(uptime / 60), uptime % 60)
    )
    screen.setBackgroundColour(theme.taskbar)
    screen.setTextColour(colours.lightGrey)
    screen.setCursorPos(W - #clock + 1, H)
    screen.write(clock)
    screen.setVisible(true)
end

local function drawFrame(wnd)
    local active = wnd == focused()
    local win = wnd.win
    win.setVisible(false)
    win.setCursorPos(1, 1)
    win.setBackgroundColour(active and theme.titleBar or theme.titleBarInactive)
    win.setTextColour(theme.titleText)
    win.clearLine()
    win.setCursorPos(2, 1)
    win.write(wnd.title:sub(1, wnd.w - 4))
    win.setCursorPos(wnd.w - 1, 1)
    win.setBackgroundColour(theme.close)
    win.write("x")
    win.setVisible(true)
end

function redrawAll()
    drawDesktop()
    for _, wnd in ipairs(windows) do
        drawFrame(wnd)
        wnd.win.redraw()
    end
    drawTaskbar()
end

-- The inner terminal for a window's app: everything but the title bar.
local function contentWindow(wnd)
    return window.create(wnd.win, 1, 2, wnd.w, wnd.h - 1, true)
end

--- Open a new window running `fn` in its own coroutine.
local function openWindow(title, x, y, w, h, fn)
    local wnd = { title = title, x = x, y = y, w = w, h = h }
    wnd.win = window.create(screen, x, y, w, h, true)
    wnd.content = contentWindow(wnd)
    wnd.content.setBackgroundColour(theme.body)
    wnd.content.setTextColour(theme.bodyText)
    wnd.content.clear()
    wnd.term = wnd.content
    wnd.co = coroutine.create(function()
        local ok, err = pcall(fn, wnd)
        if not ok and err ~= nil and tostring(err) ~= "Terminated" then
            printError(tostring(err))
            sleep(2)
        end
    end)
    windows[#windows + 1] = wnd
    resumeWindow(wnd)
    redrawAll()
    return wnd
end

function closeWindow(wnd)
    local i = findWindow(wnd)
    if not i then return end
    table.remove(windows, i)
    if coroutine.status(wnd.co) ~= "dead" then
        -- Give the app a chance to clean up.
        resumeWindow(wnd, "terminate")
    end
    redrawAll()
end

local function focusWindow(wnd)
    local i = findWindow(wnd)
    if not i or i == #windows then
        redrawAll()
        return
    end
    table.remove(windows, i)
    windows[#windows + 1] = wnd
    redrawAll()
end

--- Resume a window's coroutine with an event, respecting its event filter.
--
-- Follows multishell's model: redirect to the window's last-known terminal
-- before resuming, and remember whatever terminal the app left current.
function resumeWindow(wnd, ...)
    if coroutine.status(wnd.co) == "dead" then return end
    local event = ...
    if wnd.filter and event and event ~= wnd.filter and event ~= "terminate" then return end

    local previous = term.redirect(wnd.term)
    local ok, filter = coroutine.resume(wnd.co, ...)
    wnd.term = term.current()
    term.redirect(previous)

    wnd.filter = ok and filter or nil
    if coroutine.status(wnd.co) == "dead" and findWindow(wnd) then
        closeWindow(wnd)
    end
end

-- ------------------------------------------------------------------
-- Apps
-- ------------------------------------------------------------------

local function appTerminal()
    -- A full nested shell, exactly as multishell would host it.
    shell.run("shell")
end

local function appFiles(wnd)
    local dir = ""
    local scroll = 0
    local tw, th = term.getSize()

    local function entries()
        local list = fs.list(dir)
        table.sort(list, function(a, b)
            local da, db = fs.isDir(fs.combine(dir, a)), fs.isDir(fs.combine(dir, b))
            if da ~= db then return da end
            return a < b
        end)
        if dir ~= "" then table.insert(list, 1, "..") end
        return list
    end

    local function draw(list)
        term.setBackgroundColour(theme.body)
        term.clear()
        term.setCursorPos(1, 1)
        term.setBackgroundColour(colours.lightGrey)
        term.setTextColour(colours.black)
        term.clearLine()
        term.write(" /" .. dir)

        for i = 1, th - 1 do
            local name = list[i + scroll]
            if not name then break end
            local path = fs.combine(dir, name)
            term.setCursorPos(2, i + 1)
            term.setBackgroundColour(theme.body)
            if name == ".." or fs.isDir(path) then
                term.setTextColour(colours.blue)
                term.write("[" .. name .. "]")
            elseif name:sub(-4) == ".lua" then
                term.setTextColour(colours.green)
                term.write(name)
            else
                term.setTextColour(colours.black)
                term.write(name)
            end
        end
    end

    while true do
        local list = entries()
        draw(list)
        local event, button, x, y = os.pullEvent()
        if event == "mouse_click" and button == 1 then
            local index = y - 1 + scroll
            local name = list[index]
            if name then
                local path = name == ".." and fs.getDir(dir) or fs.combine(dir, name)
                if name == ".." or fs.isDir(path) then
                    dir = path == ".." and "" or path
                    scroll = 0
                elseif path:sub(-4) == ".lua" then
                    -- Run programs in their own terminal window.
                    launchApp("Run: " .. fs.getName(path), function()
                        shell.run("/" .. path)
                        print()
                        term.setTextColour(colours.lightGrey)
                        print("(finished - click x to close)")
                        while true do os.pullEvent("never") end
                    end)
                elseif path:sub(-4) == ".txt" or path:sub(-3) == ".md" then
                    launchApp("Edit: " .. fs.getName(path), function()
                        shell.run("/rom/programs/edit.lua", "/" .. path)
                    end)
                end
            end
        elseif event == "mouse_scroll" then
            scroll = math.max(0, math.min(scroll + button, math.max(0, #list - (th - 1))))
        end
    end
end

local function appSettings()
    local options = {
        { key = "mineos.autostart", label = "Boot into MineOS by default" },
        { key = "mineos.mirror", label = "Mirror display to monitors" },
    }

    while true do
        term.setBackgroundColour(theme.body)
        term.clear()
        term.setCursorPos(2, 2)
        term.setTextColour(colours.black)
        term.write("Settings")

        for i, opt in ipairs(options) do
            local value = settings.get(opt.key)
            term.setCursorPos(2, 3 + i)
            term.setTextColour(value and colours.green or colours.red)
            term.write(value and "[on ] " or "[off] ")
            term.setTextColour(colours.black)
            term.write(opt.label)
        end

        term.setCursorPos(2, 7 + #options)
        term.setTextColour(colours.lightGrey)
        term.write("Click an option to toggle it.")
        if settings.get("mineos.mirror") then
            term.setCursorPos(2, 8 + #options)
            term.write("Mirroring applies after a restart of MineOS.")
        end

        local _, _, _, y = os.pullEvent("mouse_click")
        local index = y - 3
        local opt = options[index]
        if opt then
            settings.set(opt.key, not settings.get(opt.key))
            settings.save()
        end
    end
end

local function appAbout()
    term.setBackgroundColour(theme.body)
    term.clear()
    local lines = {
        { "MineOS", colours.blue },
        { "One step up from CraftOS.", colours.black },
        { "", colours.black },
        { "Runtime: " .. _VERSION, colours.black },
        { "Host:    " .. _HOST, colours.black },
        { "", colours.black },
        { "Drag windows by their title bars.", colours.lightGrey },
        { "Click x to close. Exit via taskbar", colours.lightGrey },
        { "MineOS button.", colours.lightGrey },
    }
    for i, line in ipairs(lines) do
        term.setCursorPos(2, 1 + i)
        term.setTextColour(line[2])
        term.write(line[1])
    end
    while true do os.pullEvent("never") end
end

APPS = {
    { name = "Files", icon = "=", colour = colours.orange, fn = appFiles },
    { name = "Terminal", icon = ">", colour = colours.black, fn = appTerminal },
    { name = "Settings", icon = "*", colour = colours.lightGrey, fn = appSettings },
    { name = "About", icon = "?", colour = colours.blue, fn = appAbout },
}

local openCount = 0
function launchApp(title, fn, w, h)
    openCount = openCount + 1
    w = math.min(w or math.floor(W * 0.7), W - 4)
    h = math.min(h or math.floor(H * 0.7), H - 3)
    local x = math.min(4 + (openCount % 5) * 2, W - w)
    local y = math.min(2 + (openCount % 4), H - h - 1)
    return openWindow(title, x, y, w, h, fn)
end

-- ------------------------------------------------------------------
-- Event loop
-- ------------------------------------------------------------------
local function hitTest(x, y)
    for i = #windows, 1, -1 do
        local wnd = windows[i]
        if x >= wnd.x and x < wnd.x + wnd.w and y >= wnd.y and y < wnd.y + wnd.h then
            return wnd, x - wnd.x + 1, y - wnd.y + 1
        end
    end
end

local function deliverMouse(event, button, x, y)
    local wnd, wx, wy = hitTest(x, y)

    if event == "mouse_click" then
        if y == H then
            -- Taskbar.
            if x <= 8 then running = false return end
            for _, w in ipairs(windows) do
                if w.taskbarX and x >= w.taskbarX and x < w.taskbarX + w.taskbarW then
                    focusWindow(w)
                    return
                end
            end
            return
        end

        if not wnd then
            -- Desktop icons.
            for i, app in ipairs(APPS) do
                local iy = 2 + (i - 1) * 3
                if y == iy and x >= 3 and x <= 5 or (y == iy + 1 and x >= 2 and x <= 1 + #app.name) then
                    launchApp(app.name, app.fn, app.w, app.h)
                    return
                end
            end
            return
        end

        if wnd ~= focused() then focusWindow(wnd) end

        if wy == 1 then
            if wx == wnd.w - 1 then
                closeWindow(wnd)
            else
                dragging = { wnd = wnd, dx = wx, dy = wy }
            end
            return
        end
    elseif event == "mouse_drag" and dragging then
        local wnd = dragging.wnd
        wnd.x = math.max(1, math.min(x - dragging.dx + 1, W - 1))
        wnd.y = math.max(1, math.min(y - dragging.dy + 1, H - 2))
        wnd.win.reposition(wnd.x, wnd.y)
        redrawAll()
        return
    elseif event == "mouse_up" then
        dragging = nil
    end

    -- Deliver to the window under the cursor (content area only).
    if wnd and wnd == focused() and wy > 1 then
        resumeWindow(wnd, event, button, wx, wy - 1)
    end
end

local function main()
    settings.define("mineos.autostart", {
        description = "Boot into the MineOS desktop instead of the CraftOS shell.",
        default = false,
        type = "boolean",
    })
    settings.define("mineos.mirror", {
        description = "Mirror the MineOS desktop onto attached monitors.",
        default = false,
        type = "boolean",
    })

    local previous = term.redirect(screen)
    redrawAll()

    local clockTimer = os.startTimer(1)
    while running do
        local event = table.pack(os.pullEventRaw())
        local name = event[1]

        if name == "terminate" then
            running = false
        elseif name == "timer" and event[2] == clockTimer then
            drawTaskbar()
            clockTimer = os.startTimer(1)
        elseif name == "mouse_click" or name == "mouse_drag" or name == "mouse_up" or name == "mouse_scroll" then
            if name == "mouse_scroll" then
                local wnd, wx, wy = hitTest(event[3], event[4])
                if wnd and wnd == focused() and wy > 1 then
                    resumeWindow(wnd, name, event[2], wx, wy - 1)
                end
            else
                deliverMouse(name, event[2], event[3], event[4])
            end
        elseif name == "key" or name == "key_up" or name == "char" or name == "paste" then
            local wnd = focused()
            if wnd then resumeWindow(wnd, table.unpack(event, 1, event.n)) end
        else
            -- Broadcast everything else (timers, rednet, modem, etc.).
            for i = #windows, 1, -1 do
                local wnd = windows[i]
                if wnd then resumeWindow(wnd, table.unpack(event, 1, event.n)) end
            end
        end
    end

    term.redirect(previous)
    if hires then pcall(root.setResolution, 1) end
    term.setBackgroundColour(colours.black)
    term.setTextColour(colours.white)
    term.clear()
    term.setCursorPos(1, 1)
    print("Thanks for using MineOS!")
end

main()
