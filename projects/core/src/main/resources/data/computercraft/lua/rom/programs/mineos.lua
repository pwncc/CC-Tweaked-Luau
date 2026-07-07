-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

--[[- MineOS 2: a graphical desktop for advanced computers.

A windowed desktop one step up from CraftOS: draggable, resizable windows,
a start menu, right-click context menus, double-clickable icons, a file
explorer, terminals, a paint program and a task manager.

Third-party apps live in /apps: any Lua file with a metadata header appears
on the desktop and receives a `mineos` API for windows, menus and dialogs.
See the About app for the format.

Run with `mineos`, or enable `mineos.autostart` to boot straight into it.
]]

local VERSION = "2.0"

if not term.isColour() then
    printError("MineOS requires an advanced (gold) computer.")
    return
end

-- ------------------------------------------------------------------
-- Settings
-- ------------------------------------------------------------------
settings.define("mineos.autostart", {
    description = "Boot into the MineOS desktop instead of the CraftOS shell.",
    default = false, type = "boolean",
})
settings.define("mineos.mirror", {
    description = "Mirror the MineOS desktop onto attached monitors.",
    default = false, type = "boolean",
})
settings.define("mineos.density", {
    description = "MineOS pixel density (terminal resolution multiplier, 1-10).",
    default = 3, type = "number",
})
settings.define("mineos.wallpaper", {
    description = "MineOS wallpaper: a colour name, or the path of an .nfp image.",
    default = "cyan", type = "string",
})
settings.define("mineos.pointer", {
    description = "Draw the MineOS mouse pointer (requires mouse_move support).",
    default = true, type = "boolean",
})

-- ------------------------------------------------------------------
-- Resolution: MineOS runs the physical terminal at a higher density and
-- restores it on exit. We resize the *native* terminal - multishell then
-- resizes our own window in response to term_resize.
-- ------------------------------------------------------------------
local nativeTerm = term.native and term.native()
local ownsResolution = false

local function applyDensity(scale)
    if not nativeTerm or not nativeTerm.setResolution then return false end
    if not pcall(nativeTerm.setResolution, scale) then return false end
    -- Wait for multishell to process term_resize and resize our window.
    local timer = os.startTimer(0.25)
    while true do
        local event, id = os.pullEventRaw()
        if event == "term_resize" or (event == "timer" and id == timer) or event == "terminate" then break end
    end
    return true
end

local density = settings.get("mineos.density")
if type(density) ~= "number" then density = 3 end
density = math.max(1, math.min(10, math.floor(density)))
if density > 1 and applyDensity(density) then
    ownsResolution = true
else
    density = 1
end

-- Ask the host to swap in the MineOS arrow cursor while it is over us.
local capturedMouse = false
if settings.get("mineos.pointer") and nativeTerm and nativeTerm.setMouseCapture then
    capturedMouse = pcall(nativeTerm.setMouseCapture, true)
end

-- ------------------------------------------------------------------
-- Monitor mirroring
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

local root = makeMirror(term.current())
local W, H = root.getSize()
local screen = window.create(root, 1, 1, W, H, true)

-- ------------------------------------------------------------------
-- Theme
-- ------------------------------------------------------------------
local theme = {
    desktopText = colours.white,
    taskbar = colours.grey,
    taskbarText = colours.white,
    accent = colours.blue,
    titleBar = colours.blue,
    titleText = colours.white,
    titleBarInactive = colours.lightGrey,
    close = colours.red,
    minimise = colours.yellow,
    maximise = colours.lime,
    body = colours.white,
    bodyText = colours.black,
    menuBg = colours.white,
    menuText = colours.black,
    menuHover = colours.blue,
    menuHoverText = colours.white,
    selection = colours.lightBlue,
}

local WALLPAPER_COLOURS = { "cyan", "blue", "black", "grey", "green", "purple", "brown", "red" }

local function wallpaperColour()
    local value = settings.get("mineos.wallpaper")
    local colour = colours[value]
    if type(colour) == "number" then return colour end
    return colours.cyan
end

-- ------------------------------------------------------------------
-- State
-- ------------------------------------------------------------------
local windows = {} -- Bottom to top. See openWindow for the fields.
local running = true
local startClock = os.clock()
local dragging = nil -- { wnd, dx, dy } while moving a window
local resizing = nil -- { wnd } while resizing a window
local menu = nil     -- open overlay menu (start / context menus)
local pointer = { x = -1, y = -1, inside = false, seen = false, under = nil }
local lastClick = { time = 0, target = nil }
local clockTimer = nil

-- Forward declarations.
local resumeWindow, closeWindow, focusWindow, redrawAll, drawTaskbar, launchApp, openStartMenu, mineosAPI
local APPS, refreshApps

local function focused()
    for i = #windows, 1, -1 do
        if not windows[i].minimised then return windows[i] end
    end
end

local function findWindow(wnd)
    for i, w in ipairs(windows) do
        if w == wnd then return i end
    end
end

local function isDoubleClick(target)
    local now = os.clock()
    local double = lastClick.target == target and now - lastClick.time < 0.45
    lastClick.time, lastClick.target = now, target
    return double
end

-- ------------------------------------------------------------------
-- Pointer: a small arrow built from teletext subpixels over a 2x2 cell
-- block, tracking mouse_move. It lives in the same pixel grid as the
-- rest of the desktop, so it scales with the density. The cells under
-- it are saved and restored around every event, so apps never see it.
-- ------------------------------------------------------------------
local POINTER_SHAPE = {
    "X...",
    "XX..",
    "XXX.",
    "XXXX",
    "XX..",
    "X...",
}

-- Convert the shape into per-cell drawing characters, upscaled to match the
-- pixel density (more pixels means a sharper arrow, not a smaller one) and
-- offset by the pointer's subpixel position within its cell, so the arrow
-- moves at subpixel rather than cell granularity. Each cell encodes 2x3
-- subpixels; the bottom-right subpixel cannot be set directly, so cells which
-- need it use the complemented character with swapped colours.
local pointerCellCache = {}

local function pointerCells(scale, subX, subY)
    local key = scale * 6 + subY * 2 + subX
    local cached = pointerCellCache[key]
    if cached then return cached end

    local width, height = 4 * scale + subX, 6 * scale + subY
    local cells = {}
    for cellY = 0, math.ceil(height / 3) - 1 do
        for cellX = 0, math.ceil(width / 2) - 1 do
            local mask = 0
            for py = 1, 3 do
                local pixelY = cellY * 3 + py - 1 - subY
                local row = pixelY >= 0 and POINTER_SHAPE[math.floor(pixelY / scale) + 1]
                for px = 1, 2 do
                    local pixelX = cellX * 2 + px - 1 - subX
                    if row and pixelX >= 0 then
                        local shapeX = math.floor(pixelX / scale) + 1
                        if row:sub(shapeX, shapeX) == "X" then
                            mask = mask + 2 ^ ((py - 1) * 2 + (px - 1))
                        end
                    end
                end
            end
            if mask > 0 then
                local inverted = mask >= 32
                if inverted then mask = 63 - mask end
                cells[#cells + 1] = {
                    dx = cellX, dy = cellY,
                    char = string.char(128 + mask),
                    inverted = inverted,
                }
            end
        end
    end
    pointerCellCache[key] = cells
    return cells
end

local function pointerRestore()
    local saved = pointer.under
    if not saved then return end
    pointer.under = nil
    for _, cell in ipairs(saved) do
        screen.setCursorPos(cell.x, cell.y)
        screen.blit(cell.char, cell.fg, cell.bg)
    end
end

local function pointerStamp()
    if not capturedMouse or not pointer.seen or not pointer.inside then return end
    if not settings.get("mineos.pointer") then return end

    local saved = {}
    local lines = {}
    local cells = pointerCells(math.max(1, math.ceil(density / 4)), pointer.subX or 0, pointer.subY or 0)
    for _, cell in ipairs(cells) do
        local x, y = pointer.x + cell.dx, pointer.y + cell.dy
        if x >= 1 and y >= 1 and x <= W and y <= H then
            local line = lines[y]
            if not line then
                local text, fg, bg = screen.getLine(y)
                line = { text = text, fg = fg, bg = bg }
                lines[y] = line
            end
            local underBg = line.bg:sub(x, x)
            saved[#saved + 1] = {
                x = x, y = y,
                char = line.text:sub(x, x), fg = line.fg:sub(x, x), bg = underBg,
            }
            screen.setCursorPos(x, y)
            if cell.inverted then
                screen.blit(cell.char, underBg, "0")
            else
                screen.blit(cell.char, "0", underBg)
            end
        end
    end
    pointer.under = saved
end

-- ------------------------------------------------------------------
-- Desktop + taskbar
-- ------------------------------------------------------------------
local selectedIcon = nil

local function iconPos(i)
    -- Icons flow down the left edge, then wrap into further columns.
    local perColumn = math.max(1, math.floor((H - 3) / 3))
    local column = math.floor((i - 1) / perColumn)
    local row = (i - 1) % perColumn
    return 3 + column * 14, 2 + row * 3
end

-- The wallpaper image is cached: drawDesktop runs on every relayout, and
-- re-reading the file each time would make window drags crawl.
local wallpaperImage, wallpaperImagePath = nil, nil

local function drawDesktop()
    screen.setBackgroundColour(wallpaperColour())
    screen.clear()

    -- An .nfp wallpaper, if configured.
    local wallpaper = settings.get("mineos.wallpaper")
    if type(wallpaper) == "string" and wallpaper:sub(-4) == ".nfp" and fs.exists(wallpaper) then
        if wallpaper ~= wallpaperImagePath then
            wallpaperImagePath = wallpaper
            wallpaperImage = paintutils.loadImage(wallpaper)
        end
        if wallpaperImage then
            local previous = term.redirect(screen)
            paintutils.drawImage(wallpaperImage, 1, 1)
            term.redirect(previous)
        end
    else
        wallpaperImagePath, wallpaperImage = nil, nil
    end

    for i, app in ipairs(APPS) do
        local x, y = iconPos(i)
        if y + 1 < H then
            screen.setCursorPos(x, y)
            screen.setBackgroundColour(app.colour)
            screen.setTextColour(colours.white)
            screen.write(" " .. app.icon .. " ")
            screen.setCursorPos(x - 1, y + 1)
            screen.setBackgroundColour(selectedIcon == i and theme.selection or wallpaperColour())
            screen.setTextColour(theme.desktopText)
            screen.write(app.name:sub(1, 12))
        end
    end
end

function drawTaskbar()
    screen.setCursorPos(1, H)
    screen.setBackgroundColour(theme.taskbar)
    screen.setTextColour(theme.taskbarText)
    screen.clearLine()

    screen.setCursorPos(1, H)
    screen.setBackgroundColour(theme.accent)
    screen.write(" MineOS ")

    local x = 10
    for _, wnd in ipairs(windows) do
        local label = " " .. wnd.title:sub(1, 10) .. " "
        screen.setCursorPos(x, H)
        if wnd.minimised then
            screen.setBackgroundColour(theme.taskbar)
            screen.setTextColour(colours.lightGrey)
        else
            screen.setBackgroundColour(wnd == focused() and theme.accent or theme.taskbar)
            screen.setTextColour(theme.taskbarText)
        end
        screen.write(label)
        wnd.taskbarX, wnd.taskbarW = x, #label
        x = x + #label + 1
    end

    local uptime = math.floor(os.clock() - startClock)
    local clock = ("%s Day %d  wasted %d:%02d "):format(
        textutils.formatTime(os.time(), false), os.day(),
        math.floor(uptime / 60), uptime % 60
    )
    screen.setBackgroundColour(theme.taskbar)
    screen.setTextColour(colours.lightGrey)
    screen.setCursorPos(W - #clock + 1, H)
    screen.write(clock)
end

-- ------------------------------------------------------------------
-- Window chrome
-- ------------------------------------------------------------------
local function drawFrame(wnd)
    local active = wnd == focused()
    local win = wnd.win
    win.setCursorPos(1, 1)
    win.setBackgroundColour(active and theme.titleBar or theme.titleBarInactive)
    win.setTextColour(theme.titleText)
    win.clearLine()
    win.setCursorPos(2, 1)
    win.write(wnd.title:sub(1, wnd.w - 6))

    -- Minimise, maximise, close.
    win.setCursorPos(wnd.w - 2, 1)
    win.setBackgroundColour(theme.minimise)
    win.setTextColour(colours.black)
    win.write("-")
    win.setBackgroundColour(theme.maximise)
    win.write(wnd.maximised and "\31" or "+")
    win.setBackgroundColour(theme.close)
    win.setTextColour(colours.white)
    win.write("x")

    -- Resize grip.
    if not wnd.maximised then
        win.setCursorPos(wnd.w, wnd.h)
        win.setBackgroundColour(theme.titleBarInactive)
        win.setTextColour(colours.grey)
        win.write("\127")
    end
end

local function drawMenuOverlay()
    if not menu then return end
    for i, item in ipairs(menu.items) do
        local y = menu.y + i - 1
        screen.setCursorPos(menu.x, y)
        if item.sep then
            screen.setBackgroundColour(theme.menuBg)
            screen.setTextColour(colours.lightGrey)
            screen.write(("\140"):rep(menu.w))
        else
            local hover = menu.hover == i
            screen.setBackgroundColour(hover and theme.menuHover or theme.menuBg)
            screen.setTextColour(hover and theme.menuHoverText or theme.menuText)
            screen.write(" " .. item.label .. (" "):rep(menu.w - #item.label - 1))
        end
    end
end

-- Repaint everything in one batch: the screen window is hidden while drawing,
-- so exactly one full push reaches the terminal per relayout.
function redrawAll()
    screen.setVisible(false)
    drawDesktop()
    for _, wnd in ipairs(windows) do
        if not wnd.minimised then
            drawFrame(wnd)
            wnd.win.redraw()
        end
    end
    drawTaskbar()
    drawMenuOverlay()
    screen.setVisible(true)
end

-- ------------------------------------------------------------------
-- Overlay menus (start menu, context menus)
-- ------------------------------------------------------------------
local function openMenu(x, y, items)
    local w = 4
    for _, item in ipairs(items) do
        if item.label and #item.label + 2 > w then w = #item.label + 2 end
    end
    local h = #items
    x = math.max(1, math.min(x, W - w + 1))
    y = math.max(1, math.min(y, H - h))
    menu = { x = x, y = y, w = w, h = h, items = items, hover = nil }
    drawMenuOverlay()
end

local function closeMenu()
    if not menu then return end
    menu = nil
    redrawAll()
end

local function menuItemAt(x, y)
    if not menu then return end
    if x < menu.x or x >= menu.x + menu.w or y < menu.y or y >= menu.y + menu.h then return end
    local item = menu.items[y - menu.y + 1]
    if item and not item.sep then return y - menu.y + 1, item end
end

-- Returns true when the event was consumed by the menu.
local function menuHandle(event, a, b, c)
    if not menu then return false end

    if event == "mouse_click" then
        local _, item = menuItemAt(b, c)
        closeMenu()
        if item and item.action then item.action() end
        return true
    elseif event == "mouse_move" then
        local index = menuItemAt(a, b)
        if index ~= menu.hover then
            menu.hover = index
            drawMenuOverlay()
        end
        return true
    elseif event == "key" then
        if a == keys.escape then
            closeMenu()
        elseif a == keys.up or a == keys.down then
            local delta = a == keys.up and -1 or 1
            local hover = menu.hover or (delta == 1 and 0 or #menu.items + 1)
            repeat
                hover = (hover + delta - 1) % #menu.items + 1
            until not menu.items[hover].sep
            menu.hover = hover
            drawMenuOverlay()
        elseif a == keys.enter then
            local item = menu.hover and menu.items[menu.hover]
            closeMenu()
            if item and item.action then item.action() end
        end
        return true
    elseif event == "mouse_drag" or event == "mouse_up" or event == "mouse_scroll" or event == "char" then
        return true
    end
    return false
end

-- ------------------------------------------------------------------
-- Window management
-- ------------------------------------------------------------------
local MIN_W, MIN_H = 14, 5

local function contentReposition(wnd)
    wnd.content.reposition(1, 2, wnd.w, wnd.h - 1)
end

--- Open a new window running `fn` in its own coroutine.
local function openWindow(title, x, y, w, h, fn)
    local wnd = { title = title, x = x, y = y, w = w, h = h, minimised = false, maximised = false }
    wnd.win = window.create(screen, x, y, w, h, true)
    wnd.content = window.create(wnd.win, 1, 2, w, h - 1, true)
    wnd.content.setBackgroundColour(theme.body)
    wnd.content.setTextColour(theme.bodyText)
    wnd.content.clear()
    wnd.content.setCursorPos(1, 1)
    wnd.term = wnd.content
    wnd.co = coroutine.create(function()
        local ok, err = pcall(fn, wnd)
        if not ok and err ~= nil and tostring(err) ~= "Terminated" then
            pcall(function()
                term.setBackgroundColour(theme.body)
                term.setTextColour(colours.red)
                print("\nThis app crashed:")
                print(tostring(err))
                term.setTextColour(colours.lightGrey)
                print("(click x to close)")
                while true do os.pullEvent("never") end
            end)
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
        resumeWindow(wnd, "terminate")
    end
    redrawAll()
end

function focusWindow(wnd)
    local i = findWindow(wnd)
    if not i then return end
    if wnd.minimised then
        wnd.minimised = false
        wnd.win.setVisible(true)
    end
    if i ~= #windows then
        table.remove(windows, i)
        windows[#windows + 1] = wnd
    end
    redrawAll()
end

local function minimiseWindow(wnd)
    wnd.minimised = true
    wnd.win.setVisible(false)
    redrawAll()
end

local function moveWindow(wnd, x, y)
    wnd.x = math.max(1 - wnd.w + 4, math.min(x, W - 3))
    wnd.y = math.max(1, math.min(y, H - 2))
    wnd.win.reposition(wnd.x, wnd.y)
    redrawAll()
end

-- Resize a window. During a live (grip-drag) resize the app is not notified:
-- a full app repaint per mouse event would lag behind the cursor, so the
-- term_resize is sent once, when the button is released.
local function resizeWindow(wnd, w, h, live)
    w = math.max(MIN_W, math.min(w, W))
    h = math.max(MIN_H, math.min(h, H - 1))
    if w == wnd.w and h == wnd.h then return end
    wnd.w, wnd.h = w, h
    wnd.win.reposition(wnd.x, wnd.y, w, h)
    contentReposition(wnd)
    if not live then resumeWindow(wnd, "term_resize") end
    redrawAll()
end

local function toggleMaximise(wnd)
    if wnd.maximised then
        wnd.maximised = false
        local r = wnd.restore
        wnd.x, wnd.y, wnd.w, wnd.h = r.x, r.y, r.w, r.h
    else
        wnd.maximised = true
        wnd.restore = { x = wnd.x, y = wnd.y, w = wnd.w, h = wnd.h }
        wnd.x, wnd.y, wnd.w, wnd.h = 1, 1, W, H - 1
    end
    wnd.win.reposition(wnd.x, wnd.y, wnd.w, wnd.h)
    contentReposition(wnd)
    resumeWindow(wnd, "term_resize")
    redrawAll()
end

--- Resume a window's coroutine with an event, respecting its event filter.
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

local function hitTest(x, y)
    for i = #windows, 1, -1 do
        local wnd = windows[i]
        if not wnd.minimised
            and x >= wnd.x and x < wnd.x + wnd.w and y >= wnd.y and y < wnd.y + wnd.h then
            return wnd, x - wnd.x + 1, y - wnd.y + 1
        end
    end
end

local openCount = 0
function launchApp(app, argument)
    openCount = openCount + 1
    local w = math.min(app.w or math.floor(W * 0.72), W - 2)
    local h = math.min(app.h or math.floor(H * 0.72), H - 2)
    local x = math.min(3 + (openCount % 5) * 3, W - w + 1)
    local y = math.min(2 + (openCount % 4), H - h)
    return openWindow(app.name, x, y, w, h, function(wnd)
        app.fn(wnd, argument)
    end)
end

-- ------------------------------------------------------------------
-- The mineos API, given to every app (and injected as a global into
-- third-party apps loaded from /apps).
-- ------------------------------------------------------------------

--- Draw a centred sheet frame over the current term, returning its rect.
local function sheetFrame(height)
    local tw, th = term.getSize()
    local w = math.max(12, tw - 4)
    local x = math.floor((tw - w) / 2) + 1
    local y = math.max(1, math.floor((th - height) / 2))
    term.setBackgroundColour(colours.lightGrey)
    for i = 0, height - 1 do
        term.setCursorPos(x, y + i)
        term.write((" "):rep(w))
    end
    return x, y, w, height
end

local function sheetButtons(x, y, w, buttons)
    local bx = x + 1
    for _, button in ipairs(buttons) do
        button.x, button.y, button.w = bx, y, #button.label + 2
        term.setCursorPos(bx, y)
        term.setBackgroundColour(button.colour)
        term.setTextColour(colours.white)
        term.write(" " .. button.label .. " ")
        bx = bx + button.w + 1
    end
end

local function sheetWait(buttons)
    while true do
        local event, a, b, c = os.pullEvent()
        if event == "mouse_click" then
            for _, button in ipairs(buttons) do
                if c == button.y and b >= button.x and b < button.x + button.w then
                    return button.value
                end
            end
        elseif event == "key" then
            if a == keys.enter then return buttons[1].value end
            if a == keys.escape then return buttons[#buttons].value end
        end
    end
end

mineosAPI = {
    version = VERSION,

    --- Show a message with an OK button.
    alert = function(title, message)
        local x, y, w = sheetFrame(6)
        term.setBackgroundColour(colours.lightGrey)
        term.setTextColour(colours.black)
        term.setCursorPos(x + 1, y + 1)
        term.write(title:sub(1, w - 2))
        term.setTextColour(colours.grey)
        term.setCursorPos(x + 1, y + 2)
        term.write(tostring(message):sub(1, w - 2))
        local buttons = { { label = "OK", colour = theme.accent, value = true } }
        sheetButtons(x, y + 4, w, buttons)
        sheetWait(buttons)
    end,

    --- Ask a yes/no question. Returns true for yes.
    confirm = function(title, message)
        local x, y, w = sheetFrame(6)
        term.setBackgroundColour(colours.lightGrey)
        term.setTextColour(colours.black)
        term.setCursorPos(x + 1, y + 1)
        term.write(title:sub(1, w - 2))
        term.setTextColour(colours.grey)
        term.setCursorPos(x + 1, y + 2)
        term.write(tostring(message):sub(1, w - 2))
        local buttons = {
            { label = "Yes", colour = theme.accent, value = true },
            { label = "No", colour = colours.grey, value = false },
        }
        sheetButtons(x, y + 4, w, buttons)
        return sheetWait(buttons)
    end,

    --- Prompt for a line of text. Returns the string, or nil if cancelled.
    prompt = function(title, default)
        local x, y, w = sheetFrame(5)
        term.setBackgroundColour(colours.lightGrey)
        term.setTextColour(colours.black)
        term.setCursorPos(x + 1, y + 1)
        term.write(title:sub(1, w - 2))
        term.setCursorPos(x + 1, y + 3)
        term.setTextColour(colours.grey)
        term.write("(enter to accept, empty to cancel)")
        term.setCursorPos(x + 1, y + 2)
        term.setBackgroundColour(colours.white)
        term.setTextColour(colours.black)
        term.write((" "):rep(w - 2))
        term.setCursorPos(x + 1, y + 2)
        local value = read(nil, nil, nil, default)
        if value == "" then return nil end
        return value
    end,

    --- Show a menu anchored at (x, y) in the app's terminal. Items are a
    -- list of strings; returns the chosen index and string, or nil.
    menu = function(x, y, items)
        local tw, th = term.getSize()
        local w = 4
        for _, label in ipairs(items) do
            if #label + 2 > w then w = #label + 2 end
        end
        x = math.max(1, math.min(x, tw - w + 1))
        y = math.max(1, math.min(y, th - #items + 1))

        local hover = nil
        local function draw()
            for i, label in ipairs(items) do
                term.setCursorPos(x, y + i - 1)
                term.setBackgroundColour(hover == i and theme.menuHover or theme.menuBg)
                term.setTextColour(hover == i and theme.menuHoverText or theme.menuText)
                term.write(" " .. label .. (" "):rep(w - #label - 1))
            end
        end
        draw()

        while true do
            local event, a, b, c = os.pullEvent()
            if event == "mouse_click" then
                if b >= x and b < x + w and c >= y and c < y + #items then
                    local index = c - y + 1
                    return index, items[index]
                end
                return nil
            elseif event == "mouse_move" then
                local index
                if a >= x and a < x + w and b >= y and b < y + #items then index = b - y + 1 end
                if index ~= hover then
                    hover = index
                    draw()
                end
            elseif event == "key" then
                if a == keys.escape then return nil end
                if a == keys.up or a == keys.down then
                    hover = ((hover or 0) + (a == keys.down and 1 or -1) - 1) % #items + 1
                    draw()
                elseif a == keys.enter and hover then
                    return hover, items[hover]
                end
            end
        end
    end,

    --- Open a file with the default handler (edit for text, a shell for .lua).
    open = function(path) mineosAPI.openFile(path) end,

    openFile = function(path)
        path = "/" .. fs.combine(path, "")
        if path:sub(-4) == ".lua" then
            launchApp({ name = fs.getName(path), fn = function()
                term.setBackgroundColour(colours.black)
                term.setTextColour(colours.white)
                term.clear()
                term.setCursorPos(1, 1)
                shell.run(path)
                print()
                term.setTextColour(colours.lightGrey)
                print("(finished - click x to close)")
                while true do os.pullEvent("never") end
            end })
        elseif path:sub(-4) == ".nfp" then
            local paint
            for _, app in ipairs(APPS) do
                if app.name == "Paint" then paint = app end
            end
            if paint then launchApp(paint, path) end
        else
            launchApp({ name = "Edit: " .. fs.getName(path), fn = function()
                shell.run("/rom/programs/edit.lua", path)
            end })
        end
    end,

    --- Launch an app by name.
    launch = function(name, argument)
        for _, app in ipairs(APPS) do
            if app.name == name then return launchApp(app, argument) ~= nil end
        end
        return false
    end,

    --- Ask MineOS to exit.
    quit = function() running = false end,
}

-- ------------------------------------------------------------------
-- Built-in apps
-- ------------------------------------------------------------------

local function appTerminal()
    term.setBackgroundColour(colours.black)
    term.setTextColour(colours.white)
    term.clear()
    term.setCursorPos(1, 1)
    shell.run("shell")
end

local function appFiles(wnd)
    local dir = ""
    local scroll = 0
    local selected = nil

    local function entries()
        local ok, list = pcall(fs.list, dir)
        if not ok then list = {} end
        table.sort(list, function(a, b)
            local da, db = fs.isDir(fs.combine(dir, a)), fs.isDir(fs.combine(dir, b))
            if da ~= db then return da end
            return a < b
        end)
        return list
    end

    local list = entries()

    local function draw()
        local tw, th = term.getSize()
        term.setBackgroundColour(theme.body)
        term.clear()

        -- Toolbar: up button, path, new file/folder.
        term.setCursorPos(1, 1)
        term.setBackgroundColour(colours.lightGrey)
        term.clearLine()
        term.setCursorPos(1, 1)
        term.setBackgroundColour(dir ~= "" and theme.accent or colours.grey)
        term.setTextColour(colours.white)
        term.write(" ^ ")
        term.setBackgroundColour(colours.lightGrey)
        term.setTextColour(colours.black)
        term.write(" /" .. dir)
        local newLabels = " +file +dir "
        term.setCursorPos(tw - #newLabels + 1, 1)
        term.setTextColour(colours.grey)
        term.write(newLabels)

        for i = 1, th - 1 do
            local name = list[i + scroll]
            if not name then break end
            local path = fs.combine(dir, name)
            term.setCursorPos(1, i + 1)
            term.setBackgroundColour(selected == i + scroll and theme.selection or theme.body)
            term.clearLine()
            term.setCursorPos(2, i + 1)
            if fs.isDir(path) then
                term.setTextColour(colours.blue)
                term.write("[" .. name .. "]")
            elseif name:sub(-4) == ".lua" then
                term.setTextColour(colours.green)
                term.write(name)
            elseif name:sub(-4) == ".nfp" then
                term.setTextColour(colours.magenta)
                term.write(name)
            else
                term.setTextColour(colours.black)
                term.write(name)
            end
        end
    end

    local function refresh()
        list = entries()
        local tw, th = term.getSize()
        scroll = math.max(0, math.min(scroll, #list - (th - 1)))
        if selected and not list[selected] then selected = nil end
        draw()
    end

    local function contextMenu(index, mx, my)
        local name = list[index]
        if not name then return end
        local path = fs.combine(dir, name)
        local isDir = fs.isDir(path)
        local readOnly = fs.isReadOnly(path)

        local items = {}
        items[#items + 1] = isDir and "Open" or (path:sub(-4) == ".lua" and "Run" or "Open")
        if not isDir then items[#items + 1] = "Edit" end
        if not readOnly then
            items[#items + 1] = "Rename"
            items[#items + 1] = "Delete"
        end
        local _, choice = mineosAPI.menu(mx, my, items)
        if choice == "Open" and isDir then
            dir = path
            scroll, selected = 0, nil
            refresh()
        elseif choice == "Open" or choice == "Run" then
            mineosAPI.openFile(path)
        elseif choice == "Edit" then
            launchApp({ name = "Edit: " .. name, fn = function()
                shell.run("/rom/programs/edit.lua", "/" .. path)
            end })
        elseif choice == "Rename" then
            local newName = mineosAPI.prompt("Rename " .. name, name)
            if newName and newName ~= name then
                local ok, err = pcall(fs.move, path, fs.combine(dir, newName))
                if not ok then mineosAPI.alert("Rename failed", err) end
            end
            refresh()
        elseif choice == "Delete" then
            if mineosAPI.confirm("Delete " .. name, "This cannot be undone.") then
                local ok, err = pcall(fs.delete, path)
                if not ok then mineosAPI.alert("Delete failed", err) end
            end
            refresh()
        else
            draw()
        end
    end

    draw()
    while true do
        local event, a, b, c = os.pullEvent()
        local tw, th = term.getSize()
        if event == "mouse_click" then
            local button, x, y = a, b, c
            if y == 1 then
                if x <= 3 then
                    if dir ~= "" then
                        dir = fs.getDir(dir)
                        if dir == ".." then dir = "" end
                        scroll, selected = 0, nil
                        refresh()
                    end
                elseif x > tw - 12 and x <= tw - 5 then
                    local name = mineosAPI.prompt("New file name")
                    if name then
                        local handle = fs.open(fs.combine(dir, name), "w")
                        if handle then handle.close() end
                    end
                    refresh()
                elseif x > tw - 5 then
                    local name = mineosAPI.prompt("New folder name")
                    if name then pcall(fs.makeDir, fs.combine(dir, name)) end
                    refresh()
                end
            else
                local index = y - 1 + scroll
                if list[index] then
                    if button == 2 then
                        selected = index
                        draw()
                        contextMenu(index, x, y)
                    elseif isDoubleClick("file:" .. dir .. "/" .. index) then
                        local path = fs.combine(dir, list[index])
                        if fs.isDir(path) then
                            dir = path
                            scroll, selected = 0, nil
                            refresh()
                        else
                            mineosAPI.openFile(path)
                        end
                    else
                        selected = index
                        draw()
                    end
                else
                    selected = nil
                    draw()
                end
            end
        elseif event == "mouse_scroll" then
            scroll = math.max(0, math.min(scroll + a, math.max(0, #list - (th - 1))))
            draw()
        elseif event == "term_resize" then
            refresh()
        end
    end
end

local function appSettings(wnd)
    local function densityLabel()
        local scale = 1
        if nativeTerm and nativeTerm.getResolution then
            local ok, value = pcall(nativeTerm.getResolution)
            if ok then scale = value end
        end
        return ("%dx (%dx%d)"):format(scale, W, H)
    end

    local rows

    local function buildRows()
        rows = {
            {
                label = "Boot into MineOS by default",
                value = function() return settings.get("mineos.autostart") and "[on ]" or "[off]" end,
                action = function()
                    settings.set("mineos.autostart", not settings.get("mineos.autostart"))
                end,
            },
            {
                label = "Mirror display to monitors (restart MineOS)",
                value = function() return settings.get("mineos.mirror") and "[on ]" or "[off]" end,
                action = function()
                    settings.set("mineos.mirror", not settings.get("mineos.mirror"))
                end,
            },
            {
                label = "MineOS mouse cursor",
                value = function() return settings.get("mineos.pointer") and "[on ]" or "[off]" end,
                action = function()
                    local enabled = not settings.get("mineos.pointer")
                    settings.set("mineos.pointer", enabled)
                    if nativeTerm and nativeTerm.setMouseCapture then
                        pcall(nativeTerm.setMouseCapture, enabled)
                        capturedMouse = enabled
                    end
                end,
            },
            {
                label = "Pixel density",
                value = function() return "[" .. densityLabel() .. "]" end,
                action = function()
                    local scale = settings.get("mineos.density")
                    if type(scale) ~= "number" then scale = 3 end
                    scale = scale % 10 + 1
                    settings.set("mineos.density", scale)
                    os.queueEvent("mineos_density", scale)
                end,
                supported = nativeTerm and nativeTerm.setResolution ~= nil,
            },
            {
                label = "Wallpaper colour",
                value = function() return "[" .. tostring(settings.get("mineos.wallpaper")):sub(1, 10) .. "]" end,
                action = function()
                    local current = settings.get("mineos.wallpaper")
                    local index = 0
                    for i, name in ipairs(WALLPAPER_COLOURS) do
                        if name == current then index = i end
                    end
                    settings.set("mineos.wallpaper", WALLPAPER_COLOURS[index % #WALLPAPER_COLOURS + 1])
                    redrawAll()
                end,
            },
        }
    end
    buildRows()

    local function draw()
        term.setBackgroundColour(theme.body)
        term.clear()
        term.setCursorPos(2, 1)
        term.setTextColour(colours.black)
        term.write("Settings")

        for i, row in ipairs(rows) do
            term.setCursorPos(2, 2 + i)
            if row.supported == false then
                term.setTextColour(colours.lightGrey)
                term.write(row.value() .. " " .. row.label .. " (unavailable)")
            else
                local value = row.value()
                term.setTextColour(value == "[off]" and colours.red or colours.green)
                term.write(value)
                term.setTextColour(colours.black)
                term.write(" " .. row.label)
            end
        end

        local _, th = term.getSize()
        term.setCursorPos(2, th)
        term.setTextColour(colours.lightGrey)
        term.write("Click a row to change it.")
    end

    draw()
    while true do
        local event, a, b, c = os.pullEvent()
        if event == "mouse_click" and a == 1 then
            local row = rows[c - 2]
            if row and row.supported ~= false then
                row.action()
                settings.save()
                buildRows()
                draw()
            end
        elseif event == "term_resize" then
            draw()
        end
    end
end

local function appPaint(wnd, path)
    local image = {}
    local current = colours.red
    if path and fs.exists(path) then
        local loaded = paintutils.loadImage(path)
        if loaded then
            for y, line in ipairs(loaded) do
                image[y] = {}
                for x, colour in pairs(line) do
                    if colour > 0 then image[y][x] = colour end
                end
            end
        end
    end

    local ORDER = {
        colours.white, colours.orange, colours.magenta, colours.lightBlue,
        colours.yellow, colours.lime, colours.pink, colours.grey,
        colours.lightGrey, colours.cyan, colours.purple, colours.blue,
        colours.brown, colours.green, colours.red, colours.black,
    }

    local function draw()
        local tw, th = term.getSize()
        term.setBackgroundColour(colours.black)
        term.clear()

        -- Palette + actions.
        for i, colour in ipairs(ORDER) do
            term.setCursorPos(i, 1)
            term.setBackgroundColour(colour)
            term.write(colour == current and "\7" or " ")
        end
        term.setCursorPos(18, 1)
        term.setBackgroundColour(colours.grey)
        term.setTextColour(colours.white)
        term.write(" save ")
        term.setCursorPos(25, 1)
        term.write(" clear ")
        term.setCursorPos(tw - 12, 1)
        term.setTextColour(colours.lightGrey)
        term.write("rmb erases")

        -- Canvas.
        for y = 2, th do
            for x = 1, tw do
                local colour = image[y - 1] and image[y - 1][x]
                term.setCursorPos(x, y)
                term.setBackgroundColour(colour or colours.black)
                term.write(colour and " " or (x + y) % 2 == 0 and "\127" or " ")
                if not colour then term.setTextColour(colours.grey) end
            end
        end
    end

    local function paintAt(x, y, erase)
        if y < 2 then return end
        local row = image[y - 1]
        if erase then
            if row then row[x] = nil end
        else
            if not row then
                row = {}
                image[y - 1] = row
            end
            row[x] = current
        end
        term.setCursorPos(x, y)
        term.setBackgroundColour(erase and colours.black or current)
        term.setTextColour(colours.grey)
        term.write(erase and ((x + y) % 2 == 0 and "\127" or " ") or " ")
    end

    local function save()
        local target = mineosAPI.prompt("Save image as", path or "/image.nfp")
        if not target then return end
        if target:sub(-4) ~= ".nfp" then target = target .. ".nfp" end

        local maxY = 0
        local maxX = 0
        for y, row in pairs(image) do
            for x in pairs(row) do
                if y > maxY then maxY = y end
                if x > maxX then maxX = x end
            end
        end

        local handle, err = fs.open(target, "w")
        if not handle then
            mineosAPI.alert("Save failed", err)
            return
        end
        for y = 1, maxY do
            local line = {}
            for x = 1, maxX do
                local colour = image[y] and image[y][x]
                line[x] = colour and ("0123456789abcdef"):sub(select(2, math.frexp(colour)), select(2, math.frexp(colour))) or " "
            end
            handle.writeLine(table.concat(line))
        end
        handle.close()
        path = target
        mineosAPI.alert("Saved", target)
        draw()
    end

    draw()
    while true do
        local event, button, x, y = os.pullEvent()
        if event == "mouse_click" or event == "mouse_drag" then
            if y == 1 and event == "mouse_click" then
                if x <= 16 and ORDER[x] then
                    current = ORDER[x]
                    draw()
                elseif x >= 18 and x <= 23 then
                    save()
                    draw()
                elseif x >= 25 and x <= 31 then
                    if mineosAPI.confirm("Clear canvas", "Erase everything?") then image = {} end
                    draw()
                end
            else
                paintAt(x, y, button == 2)
            end
        elseif event == "term_resize" then
            draw()
        end
    end
end

local function appTasks(wnd)
    local function draw()
        local tw, th = term.getSize()
        term.setBackgroundColour(theme.body)
        term.clear()
        term.setCursorPos(2, 1)
        term.setTextColour(colours.black)
        term.write("Task manager")

        for i, other in ipairs(windows) do
            if i + 2 > th - 1 then break end
            term.setCursorPos(2, 1 + i)
            term.setTextColour(other == wnd and colours.grey or colours.black)
            local status = other.minimised and "minimised" or coroutine.status(other.co)
            term.write(("%2d %-16s %-10s"):format(i, other.title:sub(1, 16), status))
            if other ~= wnd then
                term.setCursorPos(tw - 6, 1 + i)
                term.setTextColour(colours.red)
                term.write("[end]")
            end
        end

        local uptime = math.floor(os.clock() - startClock)
        term.setCursorPos(2, th - 1)
        term.setTextColour(colours.grey)
        term.write(("Screen %dx%d  Uptime %d:%02d"):format(W, H, math.floor(uptime / 60), uptime % 60))
        term.setCursorPos(2, th)
        term.write(("Memory %.0f KB  Runtime %s"):format(collectgarbage("count"), _VERSION))
    end

    draw()
    local timer = os.startTimer(1)
    while true do
        local event, a, b, c = os.pullEvent()
        if event == "timer" and a == timer then
            draw()
            timer = os.startTimer(1)
        elseif event == "mouse_click" then
            local tw = term.getSize()
            local target = windows[c - 1]
            if target and target ~= wnd and b >= tw - 6 and b <= tw - 2 then
                closeWindow(target)
                draw()
            end
        elseif event == "term_resize" then
            draw()
        end
    end
end

local function appAbout()
    local function draw()
        local tw, th = term.getSize()
        term.setBackgroundColour(theme.body)
        term.clear()

        local lines = {
            { "MineOS " .. VERSION, colours.blue },
            { "One step up from CraftOS.", colours.black },
            { "", colours.black },
            { "Runtime: " .. _VERSION .. " (" .. _HOST .. ")", colours.black },
            { "", colours.black },
            { "Drag title bars to move. Drag the \127 corner", colours.grey },
            { "to resize. - minimises, + maximises.", colours.grey },
            { "Right-click the desktop and files for menus.", colours.grey },
            { "Double-click icons to open them.", colours.grey },
            { "", colours.black },
            { "Make your own app: /apps/hello.lua", colours.black },
            { "  --#name Hello", colours.green },
            { "  --#icon H", colours.green },
            { "  --#colour lime", colours.green },
            { "  print(\"hi!\") mineos.alert(\"Hello\", \":)\")", colours.green },
        }
        local y = 1
        for _, line in ipairs(lines) do
            if y > th then break end
            term.setCursorPos(2, y)
            term.setTextColour(line[2])
            term.write(line[1]:sub(1, tw - 2))
            y = y + 1
        end
    end

    draw()
    while true do
        local event = os.pullEvent()
        if event == "term_resize" then draw() end
    end
end

-- ------------------------------------------------------------------
-- App registry
-- ------------------------------------------------------------------
local BUILTIN_APPS = {
    { name = "Files", icon = "=", colour = colours.orange, fn = appFiles },
    { name = "Terminal", icon = ">", colour = colours.black, fn = appTerminal },
    { name = "Paint", icon = "~", colour = colours.magenta, fn = appPaint },
    { name = "Tasks", icon = "%", colour = colours.green, fn = appTasks },
    { name = "Settings", icon = "*", colour = colours.lightGrey, fn = appSettings },
    { name = "About", icon = "?", colour = colours.blue, fn = appAbout },
}

--- Parse an app's metadata header: lines like `--#name Files` at the top.
local function appMeta(path)
    local meta = {}
    local handle = fs.open(path, "r")
    if not handle then return meta end
    for _ = 1, 10 do
        local line = handle.readLine()
        if not line then break end
        local key, value = line:match("^%-%-#(%w+)%s+(.+)$")
        if key then meta[key] = value end
    end
    handle.close()
    return meta
end

function refreshApps()
    APPS = {}
    for _, app in ipairs(BUILTIN_APPS) do APPS[#APPS + 1] = app end

    if fs.isDir("apps") then
        for _, file in ipairs(fs.list("apps")) do
            if file:sub(-4) == ".lua" then
                local path = "apps/" .. file
                local meta = appMeta(path)
                APPS[#APPS + 1] = {
                    name = meta.name or file:sub(1, -5),
                    icon = (meta.icon or file:sub(1, 1)):sub(1, 1):upper(),
                    colour = colours[meta.colour or ""] or colours.purple,
                    w = tonumber(meta.width), h = tonumber(meta.height),
                    fn = function(wnd, argument)
                        local env = setmetatable({ mineos = mineosAPI }, { __index = _ENV })
                        local fn, err = loadfile(path, nil, env)
                        if not fn then error(err, 0) end
                        fn(argument)
                        print()
                        term.setTextColour(colours.lightGrey)
                        print("(finished - click x to close)")
                        while true do os.pullEvent("never") end
                    end,
                }
            end
        end
    end
end
refreshApps()

-- ------------------------------------------------------------------
-- Start menu + desktop context menu
-- ------------------------------------------------------------------
function openStartMenu()
    local items = {}
    for _, app in ipairs(APPS) do
        items[#items + 1] = { label = app.name, action = function() launchApp(app) end }
    end
    items[#items + 1] = { sep = true }
    items[#items + 1] = { label = "Exit to CraftOS", action = function() running = false end }
    items[#items + 1] = { label = "Reboot", action = function() os.reboot() end }
    items[#items + 1] = { label = "Shut down", action = function() os.shutdown() end }
    openMenu(1, H - #items, items)
end

local function openDesktopMenu(x, y)
    openMenu(x, y, {
        { label = "New file...", action = function()
            launchApp({ name = "Edit", fn = function()
                local name = mineosAPI.prompt("New file name")
                if name then shell.run("/rom/programs/edit.lua", "/" .. fs.combine("", name)) end
            end })
        end },
        { label = "Refresh apps", action = function()
            refreshApps()
            redrawAll()
        end },
        { sep = true },
        { label = "Settings", action = function() mineosAPI.launch("Settings") end },
        { label = "About MineOS", action = function() mineosAPI.launch("About") end },
    })
end

local function openWindowMenu(wnd, x, y)
    openMenu(x, y, {
        { label = wnd.minimised and "Restore" or "Minimise", action = function()
            if wnd.minimised then focusWindow(wnd) else minimiseWindow(wnd) end
        end },
        { label = wnd.maximised and "Restore size" or "Maximise", action = function() toggleMaximise(wnd) end },
        { sep = true },
        { label = "Close", action = function() closeWindow(wnd) end },
    })
end

-- ------------------------------------------------------------------
-- Event handling
-- ------------------------------------------------------------------
local function handleClick(button, x, y)
    -- Taskbar.
    if y == H then
        if x <= 8 then
            if menu then closeMenu() else openStartMenu() end
            return
        end
        for _, wnd in ipairs(windows) do
            if wnd.taskbarX and x >= wnd.taskbarX and x < wnd.taskbarX + wnd.taskbarW then
                if wnd.minimised then
                    focusWindow(wnd)
                elseif wnd == focused() then
                    minimiseWindow(wnd)
                else
                    focusWindow(wnd)
                end
                return
            end
        end
        return
    end

    local wnd, wx, wy = hitTest(x, y)

    if not wnd then
        -- Desktop.
        if button == 2 then
            selectedIcon = nil
            openDesktopMenu(x, y)
            return
        end
        for i, app in ipairs(APPS) do
            local ix, iy = iconPos(i)
            if (y == iy and x >= ix and x <= ix + 2) or (y == iy + 1 and x >= ix - 1 and x <= ix + #app.name) then
                if isDoubleClick("icon:" .. i) then
                    selectedIcon = nil
                    launchApp(app)
                else
                    selectedIcon = i
                    redrawAll()
                end
                return
            end
        end
        if selectedIcon then
            selectedIcon = nil
            redrawAll()
        end
        return
    end

    if wnd ~= focused() then focusWindow(wnd) end

    if wy == 1 then
        -- Title bar.
        if button == 2 then
            openWindowMenu(wnd, x, y)
        elseif wx == wnd.w then
            closeWindow(wnd)
        elseif wx == wnd.w - 1 then
            toggleMaximise(wnd)
        elseif wx == wnd.w - 2 then
            minimiseWindow(wnd)
        elseif isDoubleClick(wnd) then
            toggleMaximise(wnd)
        elseif not wnd.maximised then
            dragging = { wnd = wnd, dx = wx, dy = wy }
        end
        return
    end

    if wy == wnd.h and wx == wnd.w and not wnd.maximised then
        resizing = { wnd = wnd }
        return
    end

    resumeWindow(wnd, "mouse_click", button, wx, wy - 1)
end

local function handleEvent(event)
    local name = event[1]

    if name == "terminate" then
        running = false
        return
    end

    -- Every mouse event carries a position: keep the pointer tracking through
    -- clicks and drags, not just plain movement.
    if name == "mouse_click" or name == "mouse_drag" or name == "mouse_up" or name == "mouse_scroll" then
        pointer.x, pointer.y = event[3], event[4]
        pointer.inside = true
        pointer.seen = true
    end

    -- Overlay menus swallow input first.
    if menu and menuHandle(name, event[2], event[3], event[4]) then return end

    if name == "timer" and event[2] == clockTimer then
        drawTaskbar()
        clockTimer = os.startTimer(1)
    elseif name == "mineos_density" then
        -- Settings requested a new density. Resize the native terminal; the
        -- resulting term_resize event drives the relayout below.
        if nativeTerm and nativeTerm.setResolution and pcall(nativeTerm.setResolution, event[2]) then
            ownsResolution = true
            density = event[2]
        end
    elseif name == "term_resize" then
        W, H = root.getSize()
        screen.reposition(1, 1, W, H)
        for _, wnd in ipairs(windows) do
            if wnd.maximised then
                wnd.x, wnd.y, wnd.w, wnd.h = 1, 1, W, H - 1
                wnd.win.reposition(1, 1, W, H - 1)
                contentReposition(wnd)
                resumeWindow(wnd, "term_resize")
            else
                if wnd.w > W or wnd.h > H - 1 then
                    wnd.w = math.min(wnd.w, W)
                    wnd.h = math.min(wnd.h, H - 1)
                    wnd.win.reposition(wnd.x, wnd.y, wnd.w, wnd.h)
                    contentReposition(wnd)
                    resumeWindow(wnd, "term_resize")
                end
                moveWindow(wnd, wnd.x, wnd.y)
            end
        end
        redrawAll()
    elseif name == "mouse_click" then
        handleClick(event[2], event[3], event[4])
    elseif name == "mouse_drag" then
        local x, y = event[3], event[4]
        if dragging or resizing then
            -- Coalesce: a fast swipe queues many drag events. Remember only the
            -- latest position and repaint once the burst has drained (the flush
            -- marker is queued behind the pending input).
            local drag = dragging or resizing
            drag.pending = { x = x, y = y }
            if not drag.queued then
                drag.queued = true
                os.queueEvent("mineos_flush")
            end
        else
            local wnd, wx, wy = hitTest(x, y)
            if wnd and wnd == focused() and wy > 1 then
                resumeWindow(wnd, "mouse_drag", event[2], wx, wy - 1)
            end
        end
    elseif name == "mineos_flush" then
        if dragging and dragging.pending then
            local p = dragging.pending
            dragging.pending, dragging.queued = nil, false
            moveWindow(dragging.wnd, p.x - dragging.dx + 1, p.y - dragging.dy + 1)
        elseif resizing and resizing.pending then
            local p = resizing.pending
            local wnd = resizing.wnd
            resizing.pending, resizing.queued = nil, false
            resizeWindow(wnd, p.x - wnd.x + 1, p.y - wnd.y + 1, true)
        end
    elseif name == "mouse_up" then
        if dragging or resizing then
            if resizing then resumeWindow(resizing.wnd, "term_resize") end
            dragging, resizing = nil, nil
        else
            local wnd, wx, wy = hitTest(event[3], event[4])
            if wnd and wnd == focused() and wy > 1 then
                resumeWindow(wnd, "mouse_up", event[2], wx, wy - 1)
            end
        end
    elseif name == "mouse_scroll" then
        local wnd, wx, wy = hitTest(event[3], event[4])
        if wnd and wnd == focused() and wy > 1 then
            resumeWindow(wnd, "mouse_scroll", event[2], wx, wy - 1)
        end
    elseif name == "mouse_move" then
        pointer.x, pointer.y = event[2], event[3]
        pointer.subX, pointer.subY = event[4] or 0, event[5] or 0
        pointer.inside = true
        pointer.seen = true
        if not dragging and not resizing then
            local wnd, wx, wy = hitTest(pointer.x, pointer.y)
            if wnd and wy > 1 then
                resumeWindow(wnd, "mouse_move", wx, wy - 1)
            end
        end
    elseif name == "mouse_leave" then
        pointer.inside = false
        local top = focused()
        if top then resumeWindow(top, "mouse_leave") end
    elseif name == "key" or name == "key_up" or name == "char" or name == "paste" then
        local wnd = focused()
        if wnd then resumeWindow(wnd, table.unpack(event, 1, event.n)) end
    else
        -- Broadcast everything else (timers, rednet, modem, ...).
        for i = #windows, 1, -1 do
            local wnd = windows[i]
            if wnd then resumeWindow(wnd, table.unpack(event, 1, event.n)) end
        end
    end

    -- Apps may have repainted underneath an open menu; keep it on top.
    if menu then drawMenuOverlay() end
end

-- ------------------------------------------------------------------
-- Main
-- ------------------------------------------------------------------
local previous = term.redirect(screen)
redrawAll()

clockTimer = os.startTimer(1)
while running do
    -- The pointer stays stamped while we wait; lift it only while processing,
    -- so apps never see (or overwrite) a stale arrow cell.
    local event = table.pack(os.pullEventRaw())
    pointerRestore()
    handleEvent(event)
    pointerStamp()
end

-- Shut down: give apps a chance to clean up, then restore the terminal.
for i = #windows, 1, -1 do
    local wnd = windows[i]
    if wnd and coroutine.status(wnd.co) ~= "dead" then resumeWindow(wnd, "terminate") end
end

term.redirect(previous)
if capturedMouse then pcall(nativeTerm.setMouseCapture, false) end
if ownsResolution then
    pcall(nativeTerm.setResolution, 1)
    local timer = os.startTimer(0.25)
    while true do
        local event, id = os.pullEventRaw()
        if event == "term_resize" or (event == "timer" and id == timer) or event == "terminate" then break end
    end
end
term.setBackgroundColour(colours.black)
term.setTextColour(colours.white)
term.clear()
term.setCursorPos(1, 1)
print("Thanks for using MineOS!")
