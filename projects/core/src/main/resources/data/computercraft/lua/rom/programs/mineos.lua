-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

--[[- MineOS 3: a pixel-graphics desktop for advanced computers.

The terminal runs at high density (term.setResolution), where each cell is
small enough to act as a pixel. The whole interface - windows, menus, icons
and text - is drawn as pixels, with text rendered from the CraftOS font
(mineos.font / mineos.gfx). Classic programs such as the shell and editor run
in windows whose terminal is software-rendered at a readable size
(mineos.vterm), so everything that runs on CraftOS still works here.

Third-party apps live in /apps: any Lua file with a metadata header appears on
the desktop and receives a `mineos` API for windows, menus and dialogs. See
the About app for the format.

Run with `mineos`, or enable `mineos.autostart` to boot straight into it.
]]

local VERSION = "3.0"

if not term.isColour() then
    printError("MineOS requires an advanced (gold) computer.")
    return
end

local gfx = require "mineos.gfx"
local vterm = require "mineos.vterm"

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
    description = "MineOS pixel density (terminal resolution multiplier).",
    default = 15, type = "number",
})
settings.define("mineos.wallpaper", {
    description = "MineOS wallpaper: a colour name, or the path of an .nfp image.",
    default = "cyan", type = "string",
})
settings.define("mineos.pointer", {
    description = "Show the MineOS mouse cursor over the screen.",
    default = true, type = "boolean",
})

-- ------------------------------------------------------------------
-- Resolution. MineOS needs a high density to draw pixels; 10x and 15x are
-- offered (15x is 765x285 cells on a standard computer).
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
if type(density) ~= "number" or (density ~= 10 and density ~= 15) then density = 15 end
if not applyDensity(density) then
    printError("MineOS requires a runtime with term.setResolution support.")
    return
end
ownsResolution = true

-- Ask the host to show the MineOS cursor while it is over us.
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
local g = gfx.new(screen)

-- ------------------------------------------------------------------
-- Theme + metrics (all in cells; a 3x2 block of cells is square)
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
    border = colours.grey,
    menuBg = colours.white,
    menuText = colours.black,
    menuHover = colours.blue,
    menuHoverText = colours.white,
    selection = colours.lightBlue,
}

-- Text sizes: cells per font pixel. SMALL is the workhorse; glyphs are
-- 12x9 cells (about 80% the size of the classic terminal font at 1x).
local SMALL_W, SMALL_H = 2, 1
local LARGE_W, LARGE_H = 4, 3
local GLYPH_W, GLYPH_H = gfx.GLYPH_W, gfx.GLYPH_H

local TITLE_H = GLYPH_H * SMALL_H + 4     -- window title bar height
local BTN_W = 18                          -- window title button width
local BORDER = 2                          -- window side/bottom border
local TASKBAR_H = GLYPH_H * SMALL_H + 6
local MENU_ROW = GLYPH_H * SMALL_H + 4
local GRIP_W, GRIP_H = 9, 6
local MIN_W, MIN_H = 140, 70

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
local dragging = nil
local resizing = nil
local menu = nil
local pointer = { x = -1, y = -1, inside = false, seen = false, under = nil }
local lastClick = { time = 0, target = nil }
local clockTimer = nil
local selectedIcon = nil

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
-- Pointer: a pixel-art arrow drawn in the screen's own pixel grid,
-- exactly as a program running on this machine would have to. At high
-- density the grid is fine enough that pixel-snapped movement is smooth.
-- The pixels underneath are saved and restored around every event.
-- ------------------------------------------------------------------
local POINTER_SHAPE = {
    "X          ",
    "XX         ",
    "XoX        ",
    "XooX       ",
    "XoooX      ",
    "XooooX     ",
    "XoooooX    ",
    "XooooooX   ",
    "XoooooooX  ",
    "XooooooooX ",
    "XoooooXXXXX",
    "XooXooX    ",
    "XoX XooX   ",
    "XX  XooX   ",
    "X    XooX  ",
    "     XooX  ",
    "      XX   ",
}

-- Rasterise the arrow for the current density. Cells are 2:3, so an equal
-- cell count per axis comes out at roughly the shape's intended proportions;
-- the size is chosen so the arrow is about 10 font-pixels tall at any density.
local pointerSpriteCache = {}

local function pointerSprite()
    local size = math.max(#POINTER_SHAPE, math.floor(10 * density / 9 + 0.5))
    local cached = pointerSpriteCache[size]
    if cached then return cached end

    local shapeH, shapeW = #POINTER_SHAPE, #POINTER_SHAPE[1]
    local rows = {}
    for y = 0, size - 1 do
        local row = {}
        for x = 0, size - 1 do
            local shapeRow = POINTER_SHAPE[math.floor(y * shapeH / size) + 1]
            row[x + 1] = shapeRow:sub(math.floor(x * shapeW / size) + 1, math.floor(x * shapeW / size) + 1)
        end
        rows[y + 1] = table.concat(row)
    end
    local sprite = { size = size, rows = rows }
    pointerSpriteCache[size] = sprite
    return sprite
end

local function pointerRestore()
    local saved = pointer.under
    if not saved then return end
    pointer.under = nil
    for _, row in ipairs(saved) do
        screen.setCursorPos(row.x, row.y)
        screen.blit(row.text, row.fg, row.bg)
    end
end

local function pointerStamp()
    if not capturedMouse or not pointer.seen or not pointer.inside then return end
    if not settings.get("mineos.pointer") then return end

    local sprite = pointerSprite()
    local saved = {}
    for sy = 1, sprite.size do
        local y = pointer.y + sy - 1
        if y >= 1 and y <= H then
            local shapeRow = sprite.rows[sy]
            -- Trim to the used extent of this row, clipped to the screen.
            local firstX, lastX = nil, 0
            for sx = 1, sprite.size do
                if shapeRow:sub(sx, sx) ~= " " then
                    local x = pointer.x + sx - 1
                    if x >= 1 and x <= W then
                        firstX = firstX or sx
                        lastX = sx
                    end
                end
            end
            if firstX then
                local x = pointer.x + firstX - 1
                local width = lastX - firstX + 1
                local text, fgLine, bgLine = screen.getLine(y)
                local segText = text:sub(x, x + width - 1)
                local segFg = fgLine:sub(x, x + width - 1)
                local segBg = bgLine:sub(x, x + width - 1)
                saved[#saved + 1] = { x = x, y = y, text = segText, fg = segFg, bg = segBg }

                -- Compose the arrow over the saved segment.
                local outText, outFg, outBg = {}, {}, {}
                for i = 1, width do
                    local kind = shapeRow:sub(firstX + i - 1, firstX + i - 1)
                    if kind == "X" then
                        outText[i], outFg[i], outBg[i] = " ", "f", "f"
                    elseif kind == "o" then
                        outText[i], outFg[i], outBg[i] = " ", "0", "0"
                    else
                        outText[i] = segText:sub(i, i)
                        outFg[i] = segFg:sub(i, i)
                        outBg[i] = segBg:sub(i, i)
                    end
                end
                screen.setCursorPos(x, y)
                screen.blit(table.concat(outText), table.concat(outFg), table.concat(outBg))
            end
        end
    end
    pointer.under = saved
end

-- ------------------------------------------------------------------
-- Desktop + taskbar
-- ------------------------------------------------------------------
local SLOT_W, SLOT_H = 116, 50
local TILE_W, TILE_H = 34, 26

local function iconPos(i)
    local perColumn = math.max(1, math.floor((H - TASKBAR_H - 8) / SLOT_H))
    local column = math.floor((i - 1) / perColumn)
    local row = (i - 1) % perColumn
    return 8 + column * (SLOT_W + 8), 6 + row * SLOT_H
end

local wallpaperImage, wallpaperImagePath = nil, nil

local function drawDesktop()
    screen.setBackgroundColour(wallpaperColour())
    screen.clear()

    -- An .nfp wallpaper, scaled up (in square pixel blocks) to fill the screen.
    local wallpaper = settings.get("mineos.wallpaper")
    if type(wallpaper) == "string" and wallpaper:sub(-4) == ".nfp" and fs.exists(wallpaper) then
        if wallpaper ~= wallpaperImagePath then
            wallpaperImagePath = wallpaper
            wallpaperImage = paintutils.loadImage(wallpaper)
        end
        local image = wallpaperImage
        if image and #image > 0 then
            local imageW = 0
            for _, line in ipairs(image) do
                for x in pairs(line) do
                    if x > imageW then imageW = x end
                end
            end
            if imageW > 0 then
                local scale = math.max(1, math.floor(math.min(W / (3 * imageW), H / (2 * #image))))
                local pxW, pxH = 3 * scale, 2 * scale
                local originX = math.floor((W - imageW * pxW) / 2) + 1
                local originY = math.floor((H - #image * pxH) / 2) + 1
                for y, line in ipairs(image) do
                    for x = 1, imageW do
                        local colour = line[x]
                        if colour and colour > 0 then
                            g:rect(originX + (x - 1) * pxW, originY + (y - 1) * pxH, pxW, pxH, colour)
                        end
                    end
                end
            end
        end
    else
        wallpaperImagePath, wallpaperImage = nil, nil
    end

    for i, app in ipairs(APPS) do
        local x, y = iconPos(i)
        if y + SLOT_H < H - TASKBAR_H then
            -- A coloured tile with a large glyph, and the app name below.
            local tileX = x + math.floor((SLOT_W - TILE_W) / 2)
            if selectedIcon == i then
                g:rect(tileX - 3, y - 2, TILE_W + 6, TILE_H + 4, theme.selection)
            end
            g:rect(tileX, y, TILE_W, TILE_H, app.colour)
            local glyphX = tileX + math.floor((TILE_W - GLYPH_W * LARGE_W) / 2)
            local glyphY = y + math.floor((TILE_H - GLYPH_H * LARGE_H * 2 / 3) / 2) - 2
            g:text(glyphX, glyphY, app.icon, colours.white, app.colour, LARGE_W, 2)

            local label = app.name:sub(1, 9)
            local labelW = g:textWidth(label, SMALL_W)
            local labelX = x + math.floor((SLOT_W - labelW) / 2)
            local labelBg = selectedIcon == i and theme.selection or wallpaperColour()
            g:text(labelX, y + TILE_H + 3, label, theme.desktopText, labelBg, SMALL_W, SMALL_H)
        end
    end
end

function drawTaskbar()
    local y = H - TASKBAR_H + 1
    g:rect(1, y, W, TASKBAR_H, theme.taskbar)

    -- Start button.
    local startW = g:textWidth("MineOS", SMALL_W) + 12
    g:rect(1, y, startW, TASKBAR_H, theme.accent)
    g:text(7, y + 3, "MineOS", theme.taskbarText, theme.accent, SMALL_W, SMALL_H)

    -- Window buttons.
    local x = startW + 6
    for _, wnd in ipairs(windows) do
        local label = wnd.title:sub(1, 9)
        local buttonW = g:textWidth(label, SMALL_W) + 10
        if x + buttonW > W - 220 then break end
        local bg = theme.taskbar
        local fg = colours.lightGrey
        if not wnd.minimised then
            bg = wnd == focused() and theme.accent or colours.lightGrey
            fg = wnd == focused() and theme.taskbarText or colours.black
        end
        g:rect(x, y + 2, buttonW, TASKBAR_H - 4, bg)
        g:text(x + 5, y + 3, label, fg, bg, SMALL_W, SMALL_H)
        wnd.taskbarX, wnd.taskbarW = x, buttonW
        x = x + buttonW + 4
    end

    -- Clock.
    local uptime = math.floor(os.clock() - startClock)
    local clock = ("%s Day %d  %d:%02d"):format(
        textutils.formatTime(os.time(), false), os.day(),
        math.floor(uptime / 60), uptime % 60
    )
    local clockW = g:textWidth(clock, SMALL_W)
    g:text(W - clockW - 6, y + 3, clock, colours.lightGrey, theme.taskbar, SMALL_W, SMALL_H)
end

-- ------------------------------------------------------------------
-- Window chrome
-- ------------------------------------------------------------------
local function drawFrame(wnd)
    local active = wnd == focused()
    local fg = gfx.new(wnd.win)
    local barColour = active and theme.titleBar or theme.titleBarInactive

    -- Title bar, borders.
    fg:rect(1, 1, wnd.w, TITLE_H, barColour)
    fg:rect(1, TITLE_H + 1, BORDER, wnd.h - TITLE_H, theme.border)
    fg:rect(wnd.w - BORDER + 1, TITLE_H + 1, BORDER, wnd.h - TITLE_H, theme.border)
    fg:rect(1, wnd.h - BORDER + 1, wnd.w, BORDER, theme.border)

    local maxTitle = math.floor((wnd.w - BTN_W * 3 - 12) / (GLYPH_W * SMALL_W))
    fg:text(6, 3, wnd.title:sub(1, maxTitle), theme.titleText, barColour, SMALL_W, SMALL_H)

    -- Minimise, maximise, close.
    local buttonY, buttonH = 1, TITLE_H
    local closeX = wnd.w - BTN_W + 1
    local maxX = closeX - BTN_W
    local minX = maxX - BTN_W
    fg:rect(minX, buttonY, BTN_W, buttonH, theme.minimise)
    fg:text(minX + math.floor((BTN_W - GLYPH_W * SMALL_W) / 2), buttonY + 3, "-", colours.black, theme.minimise, SMALL_W, SMALL_H)
    fg:rect(maxX, buttonY, BTN_W, buttonH, theme.maximise)
    fg:text(maxX + math.floor((BTN_W - GLYPH_W * SMALL_W) / 2), buttonY + 3, wnd.maximised and "\18" or "+", colours.black, theme.maximise, SMALL_W, SMALL_H)
    fg:rect(closeX, buttonY, BTN_W, buttonH, theme.close)
    fg:text(closeX + math.floor((BTN_W - GLYPH_W * SMALL_W) / 2), buttonY + 3, "x", colours.white, theme.close, SMALL_W, SMALL_H)

    -- Resize grip.
    if not wnd.maximised then
        fg:rect(wnd.w - GRIP_W + 1, wnd.h - GRIP_H + 1, GRIP_W, GRIP_H, active and theme.accent or colours.lightGrey)
    end
end

local function drawMenuOverlay()
    if not menu then return end
    g:rect(menu.x, menu.y, menu.w, menu.h, theme.menuBg)
    for i, item in ipairs(menu.items) do
        local y = menu.y + (i - 1) * MENU_ROW
        if item.sep then
            g:rect(menu.x + 4, y + math.floor(MENU_ROW / 2), menu.w - 8, 1, colours.lightGrey)
        else
            local hover = menu.hover == i
            if hover then g:rect(menu.x, y, menu.w, MENU_ROW, theme.menuHover) end
            g:text(menu.x + 6, y + 2, item.label, hover and theme.menuHoverText or theme.menuText,
                hover and theme.menuHover or theme.menuBg, SMALL_W, SMALL_H)
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
            -- The content window's buffer persists (including software-rendered
            -- legacy terminals), so pushing it is enough - no re-rasterising.
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
    local w = 40
    for _, item in ipairs(items) do
        if item.label then w = math.max(w, g:textWidth(item.label, SMALL_W) + 12) end
    end
    local h = #items * MENU_ROW
    x = math.max(1, math.min(x, W - w + 1))
    y = math.max(1, math.min(y, H - TASKBAR_H - h))
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
    local index = math.floor((y - menu.y) / MENU_ROW) + 1
    local item = menu.items[index]
    if item and not item.sep then return index, item end
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
local function contentArea(wnd)
    return wnd.w - BORDER * 2, wnd.h - TITLE_H - BORDER
end

local function contentReposition(wnd)
    local cw, ch = contentArea(wnd)
    wnd.content.reposition(BORDER + 1, TITLE_H + 1, cw, ch)
    if wnd.vterm then
        local cols, rows = vterm.fit(cw, ch, 2, 1)
        wnd.vterm.resize(cols, rows)
        wnd.content.setBackgroundColour(colours.black)
        wnd.content.clear()
        wnd.vterm.render()
    end
end

--- Open a new window running `fn` in its own coroutine.
--
-- `kind` is "pixel" (the app draws with mineos.gfx on a raw high-density
-- terminal) or "legacy" (the app gets a classic software-rendered terminal).
local function openWindow(title, x, y, w, h, kind, fn)
    local wnd = { title = title, x = x, y = y, w = w, h = h, kind = kind, minimised = false, maximised = false }
    wnd.win = window.create(screen, x, y, w, h, true)
    local cw, ch = contentArea(wnd)
    wnd.content = window.create(wnd.win, BORDER + 1, TITLE_H + 1, cw, ch, true)

    if kind == "legacy" then
        local cols, rows = vterm.fit(cw, ch, 2, 1)
        wnd.vterm = vterm.new(wnd.content, cols, rows, 2, 1)
        wnd.term = wnd.vterm
        wnd.content.setBackgroundColour(colours.black)
        wnd.content.clear()
    else
        wnd.content.setBackgroundColour(theme.body)
        wnd.content.setTextColour(theme.bodyText)
        wnd.content.clear()
        wnd.content.setCursorPos(1, 1)
        wnd.term = wnd.content
    end

    wnd.co = coroutine.create(function()
        local ok, err = pcall(fn, wnd)
        if not ok and err ~= nil and tostring(err) ~= "Terminated" then
            pcall(function()
                if kind == "legacy" then
                    term.setBackgroundColour(colours.black)
                    term.setTextColour(colours.red)
                    print("\nThis app crashed:")
                    print(tostring(err))
                    term.setTextColour(colours.lightGrey)
                    print("(click x to close)")
                else
                    local cg = gfx.new(term.current())
                    local cw2, ch2 = term.current().getSize()
                    cg:rect(1, 1, cw2, ch2, theme.body)
                    cg:text(6, 4, "This app crashed:", colours.red, theme.body, SMALL_W, SMALL_H)
                    cg:text(6, 4 + MENU_ROW, tostring(err):sub(1, 60), colours.black, theme.body, SMALL_W, SMALL_H)
                    cg:text(6, 4 + MENU_ROW * 2, "(click x to close)", colours.lightGrey, theme.body, SMALL_W, SMALL_H)
                end
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
    wnd.x = math.max(1 - wnd.w + 30, math.min(x, W - 30))
    wnd.y = math.max(1, math.min(y, H - TASKBAR_H - TITLE_H))
    wnd.win.reposition(wnd.x, wnd.y)
    redrawAll()
end

-- Resize a window. During a live (grip-drag) resize the app is not notified:
-- the term_resize is sent once, when the button is released.
local function resizeWindow(wnd, w, h, live)
    w = math.max(MIN_W, math.min(w, W))
    h = math.max(MIN_H, math.min(h, H - TASKBAR_H))
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
        wnd.x, wnd.y, wnd.w, wnd.h = 1, 1, W, H - TASKBAR_H
    end
    wnd.win.reposition(wnd.x, wnd.y, wnd.w, wnd.h)
    contentReposition(wnd)
    resumeWindow(wnd, "term_resize")
    redrawAll()
end

--- Resume a window's coroutine with an event, respecting its event filter.
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

--- Deliver a mouse event into a window's content, converting to the app's
-- coordinate space (virtual cells for legacy windows).
local function contentMouse(wnd, event, button, wx, wy)
    local cx, cy = wx - BORDER, wy - TITLE_H
    local cw, ch = contentArea(wnd)
    if cx < 1 or cy < 1 or cx > cw or cy > ch then return end
    if wnd.vterm then
        cx, cy = wnd.vterm.toVirtual(cx, cy)
    end
    if event == "mouse_move" then
        resumeWindow(wnd, event, cx, cy)
    else
        resumeWindow(wnd, event, button, cx, cy)
    end
end

local openCount = 0
function launchApp(app, argument)
    openCount = openCount + 1
    local w = math.min(app.w or math.floor(W * 0.78), W - 10)
    local h = math.min(app.h or math.floor(H * 0.78), H - TASKBAR_H - 6)
    local x = math.min(10 + (openCount % 5) * 14, W - w + 1)
    local y = math.min(6 + (openCount % 4) * 8, H - TASKBAR_H - h)
    return openWindow(app.name, x, y, w, h, app.kind or "pixel", function(wnd)
        app.fn(wnd, argument)
    end)
end

-- ------------------------------------------------------------------
-- The mineos API, given to every app (and injected as a global into
-- third-party apps loaded from /apps).
-- ------------------------------------------------------------------

--- A centred sheet frame over the current (pixel) term. Returns a canvas and rect.
local function sheetFrame(height)
    local current = term.current()
    local sheet = gfx.new(current)
    local tw, th = current.getSize()
    local w = math.max(120, tw - 24)
    local x = math.floor((tw - w) / 2) + 1
    local y = math.max(1, math.floor((th - height) / 2))
    sheet:rect(x, y, w, height, colours.lightGrey)
    sheet:frame(x, y, w, height, colours.grey)
    return sheet, x, y, w, height
end

local function sheetButtons(sheet, x, y, buttons)
    local bx = x + 6
    for _, button in ipairs(buttons) do
        button.x, button.y = bx, y
        button.w, button.h = sheet:textWidth(button.label, SMALL_W) + 10, MENU_ROW
        sheet:rect(bx, y, button.w, button.h, button.colour)
        sheet:text(bx + 5, y + 2, button.label, colours.white, button.colour, SMALL_W, SMALL_H)
        bx = bx + button.w + 6
    end
end

local function sheetWait(buttons)
    while true do
        local event, a, b, c = os.pullEvent()
        if event == "mouse_click" then
            for _, button in ipairs(buttons) do
                if c >= button.y and c < button.y + button.h and b >= button.x and b < button.x + button.w then
                    return button.value
                end
            end
        elseif event == "key" then
            if a == keys.enter then return buttons[1].value end
            if a == keys.escape then return buttons[#buttons].value end
        end
    end
end

--- A single-line text editor rendered with the pixel font.
local function sheetInput(sheet, x, y, w, default)
    local value = default or ""
    local maxChars = math.floor((w - 8) / (GLYPH_W * SMALL_W))

    local function draw()
        sheet:rect(x, y, w, MENU_ROW, colours.white)
        local shown = value:sub(-maxChars + 1)
        local width = sheet:text(x + 4, y + 2, shown, colours.black, colours.white, SMALL_W, SMALL_H)
        -- Caret.
        sheet:rect(x + 4 + width, y + 2, 2, GLYPH_H * SMALL_H, colours.blue)
    end
    draw()

    while true do
        local event, a = os.pullEvent()
        if event == "char" then
            value = value .. a
            draw()
        elseif event == "key" then
            if a == keys.enter then
                return value
            elseif a == keys.escape then
                return nil
            elseif a == keys.backspace and #value > 0 then
                value = value:sub(1, -2)
                draw()
            end
        end
    end
end

mineosAPI = {
    version = VERSION,

    --- Show a message with an OK button.
    alert = function(title, message)
        local sheet, x, y, w = sheetFrame(MENU_ROW * 4)
        sheet:text(x + 6, y + 4, tostring(title), colours.black, colours.lightGrey, SMALL_W, SMALL_H)
        sheet:text(x + 6, y + 4 + MENU_ROW, tostring(message):sub(1, 60), colours.grey, colours.lightGrey, SMALL_W, SMALL_H)
        local buttons = { { label = "OK", colour = theme.accent, value = true } }
        sheetButtons(sheet, x, y + MENU_ROW * 4 - MENU_ROW - 4, buttons)
        sheetWait(buttons)
    end,

    --- Ask a yes/no question. Returns true for yes.
    confirm = function(title, message)
        local sheet, x, y, w = sheetFrame(MENU_ROW * 4)
        sheet:text(x + 6, y + 4, tostring(title), colours.black, colours.lightGrey, SMALL_W, SMALL_H)
        sheet:text(x + 6, y + 4 + MENU_ROW, tostring(message):sub(1, 60), colours.grey, colours.lightGrey, SMALL_W, SMALL_H)
        local buttons = {
            { label = "Yes", colour = theme.accent, value = true },
            { label = "No", colour = colours.grey, value = false },
        }
        sheetButtons(sheet, x, y + MENU_ROW * 4 - MENU_ROW - 4, buttons)
        return sheetWait(buttons)
    end,

    --- Prompt for a line of text. Returns the string, or nil if cancelled.
    prompt = function(title, default)
        local sheet, x, y, w = sheetFrame(MENU_ROW * 4)
        sheet:text(x + 6, y + 4, tostring(title), colours.black, colours.lightGrey, SMALL_W, SMALL_H)
        sheet:text(x + 6, y + MENU_ROW * 4 - MENU_ROW, "enter accepts, escape cancels", colours.grey, colours.lightGrey, SMALL_W, SMALL_H)
        local value = sheetInput(sheet, x + 6, y + 4 + MENU_ROW, w - 12, default)
        if value == "" then return nil end
        return value
    end,

    --- Show a menu anchored at (x, y) in the app's terminal (cell coords).
    -- Items are a list of strings; returns the chosen index and string, or nil.
    menu = function(x, y, items)
        local current = term.current()
        local am = gfx.new(current)
        local tw, th = current.getSize()
        local w = 40
        for _, label in ipairs(items) do
            w = math.max(w, am:textWidth(label, SMALL_W) + 12)
        end
        local h = #items * MENU_ROW
        x = math.max(1, math.min(x, tw - w + 1))
        y = math.max(1, math.min(y, th - h + 1))

        local hover = nil
        local function draw()
            am:rect(x, y, w, h, theme.menuBg)
            for i, label in ipairs(items) do
                local rowY = y + (i - 1) * MENU_ROW
                if hover == i then am:rect(x, rowY, w, MENU_ROW, theme.menuHover) end
                am:text(x + 6, rowY + 2, label, hover == i and theme.menuHoverText or theme.menuText,
                    hover == i and theme.menuHover or theme.menuBg, SMALL_W, SMALL_H)
            end
        end
        draw()

        while true do
            local event, a, b, c = os.pullEvent()
            if event == "mouse_click" then
                if b >= x and b < x + w and c >= y and c < y + h then
                    local index = math.floor((c - y) / MENU_ROW) + 1
                    return index, items[index]
                end
                return nil
            elseif event == "mouse_move" then
                local index
                if a >= x and a < x + w and b >= y and b < y + h then
                    index = math.floor((b - y) / MENU_ROW) + 1
                end
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

    --- Open a file with the default handler.
    open = function(path) mineosAPI.openFile(path) end,

    openFile = function(path)
        path = "/" .. fs.combine(path, "")
        if path:sub(-4) == ".lua" then
            launchApp({ name = fs.getName(path), kind = "legacy", fn = function()
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
            launchApp({ name = "Edit: " .. fs.getName(path), kind = "legacy", fn = function()
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

    --- The pixel-drawing toolkit (see mineos.gfx). Apps can call
    -- mineos.gfx.new(term.current()) to draw pixel graphics and text.
    gfx = gfx,

    --- Text metric constants for pixel apps.
    text = { SMALL_W = SMALL_W, SMALL_H = SMALL_H, LARGE_W = LARGE_W, LARGE_H = LARGE_H, ROW = MENU_ROW },

    --- Ask MineOS to exit.
    quit = function() running = false end,
}

-- ------------------------------------------------------------------
-- Built-in apps
-- ------------------------------------------------------------------

local function appTerminal()
    shell.run("shell")
end

local function appFiles(wnd)
    local dir = ""
    local scroll = 0
    local selected = nil
    local ROW = MENU_ROW

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

    local function rowsVisible()
        local _, th = term.current().getSize()
        return math.floor((th - ROW) / ROW)
    end

    local function draw()
        local current = term.current()
        local ag = gfx.new(current)
        local tw, th = current.getSize()
        ag:rect(1, 1, tw, th, theme.body)

        -- Toolbar: up, path, new file/folder.
        ag:rect(1, 1, tw, ROW, colours.lightGrey)
        ag:rect(1, 1, 22, ROW, dir ~= "" and theme.accent or colours.grey)
        ag:text(8, 3, "^", colours.white, dir ~= "" and theme.accent or colours.grey, SMALL_W, SMALL_H)
        ag:text(28, 3, ("/" .. dir):sub(1, 24), colours.black, colours.lightGrey, SMALL_W, SMALL_H)
        local newDirW = ag:textWidth("+dir", SMALL_W) + 8
        local newFileW = ag:textWidth("+file", SMALL_W) + 8
        ag:text(tw - newDirW - newFileW - 8, 3, "+file", colours.grey, colours.lightGrey, SMALL_W, SMALL_H)
        ag:text(tw - newDirW - 2, 3, "+dir", colours.grey, colours.lightGrey, SMALL_W, SMALL_H)

        for i = 1, rowsVisible() do
            local name = list[i + scroll]
            if not name then break end
            local path = fs.combine(dir, name)
            local y = ROW + (i - 1) * ROW + 1
            local bg = selected == i + scroll and theme.selection or theme.body
            ag:rect(1, y, tw, ROW, bg)
            local colour = colours.black
            local label = name
            if fs.isDir(path) then
                colour = colours.blue
                label = "[" .. name .. "]"
            elseif name:sub(-4) == ".lua" then
                colour = colours.green
            elseif name:sub(-4) == ".nfp" then
                colour = colours.magenta
            end
            ag:text(8, y + 2, label:sub(1, 40), colour, bg, SMALL_W, SMALL_H)
        end
    end

    local function refresh()
        list = entries()
        scroll = math.max(0, math.min(scroll, #list - rowsVisible()))
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
            draw()
        elseif choice == "Edit" then
            launchApp({ name = "Edit: " .. name, kind = "legacy", fn = function()
                shell.run("/rom/programs/edit.lua", "/" .. path)
            end })
            draw()
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
        local tw = term.current().getSize()
        if event == "mouse_click" then
            local button, x, y = a, b, c
            if y <= ROW then
                if x <= 22 then
                    if dir ~= "" then
                        dir = fs.getDir(dir)
                        if dir == ".." then dir = "" end
                        scroll, selected = 0, nil
                        refresh()
                    end
                elseif x > tw - 110 and x <= tw - 55 then
                    local name = mineosAPI.prompt("New file name")
                    if name then
                        local handle = fs.open(fs.combine(dir, name), "w")
                        if handle then handle.close() end
                    end
                    refresh()
                elseif x > tw - 55 then
                    local name = mineosAPI.prompt("New folder name")
                    if name then pcall(fs.makeDir, fs.combine(dir, name)) end
                    refresh()
                end
            else
                local index = math.floor((y - ROW - 1) / ROW) + 1 + scroll
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
                            draw()
                        end
                    else
                        selected = index
                        draw()
                    end
                elseif selected then
                    selected = nil
                    draw()
                end
            end
        elseif event == "mouse_scroll" then
            scroll = math.max(0, math.min(scroll + a, math.max(0, #list - rowsVisible())))
            draw()
        elseif event == "term_resize" then
            refresh()
        end
    end
end

local function appSettings()
    local rows

    local function densityLabel()
        return density == 15 and "15x quality" or "10x fast"
    end

    local function buildRows()
        rows = {
            {
                label = "Boot into MineOS by default",
                value = function() return settings.get("mineos.autostart") and "on" or "off" end,
                action = function()
                    settings.set("mineos.autostart", not settings.get("mineos.autostart"))
                end,
            },
            {
                label = "Mirror display to monitors (restart)",
                value = function() return settings.get("mineos.mirror") and "on" or "off" end,
                action = function()
                    settings.set("mineos.mirror", not settings.get("mineos.mirror"))
                end,
            },
            {
                label = "MineOS mouse cursor",
                value = function() return settings.get("mineos.pointer") and "on" or "off" end,
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
                value = densityLabel,
                action = function()
                    local scale = density == 15 and 10 or 15
                    settings.set("mineos.density", scale)
                    os.queueEvent("mineos_density", scale)
                end,
            },
            {
                label = "Wallpaper colour",
                value = function() return tostring(settings.get("mineos.wallpaper")):sub(1, 10) end,
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

    local ROW = MENU_ROW + 4

    local function draw()
        local current = term.current()
        local ag = gfx.new(current)
        local tw, th = current.getSize()
        ag:rect(1, 1, tw, th, theme.body)
        ag:text(8, 4, "Settings", colours.black, theme.body, LARGE_W, 2)

        for i, row in ipairs(rows) do
            local y = 24 + (i - 1) * ROW
            local value = row.value()
            local valueColour = value == "off" and colours.red or colours.green
            ag:rect(8, y, 44, MENU_ROW, colours.lightGrey)
            ag:text(8 + math.floor((44 - ag:textWidth(value, SMALL_W)) / 2), y + 2, value, valueColour, colours.lightGrey, SMALL_W, SMALL_H)
            ag:text(60, y + 2, row.label, colours.black, theme.body, SMALL_W, SMALL_H)
        end

        ag:text(8, 24 + #rows * ROW + 4, "Click a value to change it.", colours.lightGrey, theme.body, SMALL_W, SMALL_H)
    end

    draw()
    while true do
        local event, a, b, c = os.pullEvent()
        if event == "mouse_click" and a == 1 then
            local index = math.floor((c - 24) / ROW) + 1
            local row = rows[index]
            if row and c >= 24 + (index - 1) * ROW and c < 24 + (index - 1) * ROW + MENU_ROW and b >= 8 and b < 52 then
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

    local TOP = MENU_ROW
    local SWATCH = 14
    -- Paint pixels are square 3x2 cell blocks.
    local PX_W, PX_H = 3, 2

    local function draw()
        local currentTerm = term.current()
        local ag = gfx.new(currentTerm)
        local tw, th = currentTerm.getSize()
        ag:rect(1, 1, tw, th, colours.black)

        -- Toolbar: palette, save, clear.
        ag:rect(1, 1, tw, TOP, colours.grey)
        for i, colour in ipairs(ORDER) do
            ag:rect((i - 1) * SWATCH + 1, 1, SWATCH, TOP, colour)
            if colour == current then
                ag:rect((i - 1) * SWATCH + 5, 4, 4, 4, colour == colours.white and colours.black or colours.white)
            end
        end
        local saveX = 16 * SWATCH + 8
        ag:text(saveX, 3, "save", colours.white, colours.grey, SMALL_W, SMALL_H)
        ag:text(saveX + 60, 3, "clear", colours.white, colours.grey, SMALL_W, SMALL_H)

        -- Canvas.
        for y, row in pairs(image) do
            for x, colour in pairs(row) do
                ag:rect((x - 1) * PX_W + 1, TOP + (y - 1) * PX_H + 1, PX_W, PX_H, colour)
            end
        end
    end

    local function paintAt(x, y, erase)
        if y <= TOP then return end
        local px = math.floor((x - 1) / PX_W) + 1
        local py = math.floor((y - TOP - 1) / PX_H) + 1
        local ag = gfx.new(term.current())
        if erase then
            if image[py] then image[py][px] = nil end
            ag:rect((px - 1) * PX_W + 1, TOP + (py - 1) * PX_H + 1, PX_W, PX_H, colours.black)
        else
            image[py] = image[py] or {}
            image[py][px] = current
            ag:rect((px - 1) * PX_W + 1, TOP + (py - 1) * PX_H + 1, PX_W, PX_H, current)
        end
    end

    local function save()
        local target = mineosAPI.prompt("Save image as", path or "/image.nfp")
        if not target then
            draw()
            return
        end
        if target:sub(-4) ~= ".nfp" then target = target .. ".nfp" end

        local maxY, maxX = 0, 0
        for y, row in pairs(image) do
            for x in pairs(row) do
                if y > maxY then maxY = y end
                if x > maxX then maxX = x end
            end
        end

        local handle, err = fs.open(target, "w")
        if not handle then
            mineosAPI.alert("Save failed", err)
            draw()
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
            local tw = term.current().getSize()
            if y <= TOP and event == "mouse_click" then
                local swatch = math.floor((x - 1) / SWATCH) + 1
                if swatch >= 1 and swatch <= 16 then
                    current = ORDER[swatch]
                    draw()
                elseif x >= 16 * SWATCH + 8 and x < 16 * SWATCH + 60 then
                    save()
                elseif x >= 16 * SWATCH + 68 then
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
    local ROW = MENU_ROW + 2

    local function draw()
        local current = term.current()
        local ag = gfx.new(current)
        local tw, th = current.getSize()
        ag:rect(1, 1, tw, th, theme.body)
        ag:text(8, 4, "Task manager", colours.black, theme.body, LARGE_W, 2)

        for i, other in ipairs(windows) do
            local y = 24 + (i - 1) * ROW
            if y + ROW > th - MENU_ROW * 2 then break end
            local status = other.minimised and "minimised" or coroutine.status(other.co)
            ag:text(8, y, ("%d %s"):format(i, other.title:sub(1, 16)), other == wnd and colours.grey or colours.black, theme.body, SMALL_W, SMALL_H)
            ag:text(240, y, status, colours.grey, theme.body, SMALL_W, SMALL_H)
            if other ~= wnd then
                ag:rect(tw - 60, y, 52, MENU_ROW, theme.close)
                ag:text(tw - 60 + 8, y + 2, "end", colours.white, theme.close, SMALL_W, SMALL_H)
            end
        end

        local uptime = math.floor(os.clock() - startClock)
        ag:text(8, th - MENU_ROW * 2, ("Screen %dx%d  Uptime %d:%02d"):format(W, H, math.floor(uptime / 60), uptime % 60), colours.grey, theme.body, SMALL_W, SMALL_H)
        ag:text(8, th - MENU_ROW, ("Memory %.0f KB  Runtime %s"):format(collectgarbage("count"), _VERSION), colours.grey, theme.body, SMALL_W, SMALL_H)
    end

    draw()
    local timer = os.startTimer(1)
    while true do
        local event, a, b, c = os.pullEvent()
        if event == "timer" and a == timer then
            draw()
            timer = os.startTimer(1)
        elseif event == "mouse_click" then
            local tw = term.current().getSize()
            local index = math.floor((c - 24) / ROW) + 1
            local target = windows[index]
            if target and target ~= wnd and b >= tw - 60 and b < tw - 8 and c >= 24 + (index - 1) * ROW and c < 24 + (index - 1) * ROW + MENU_ROW then
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
        local current = term.current()
        local ag = gfx.new(current)
        local tw, th = current.getSize()
        ag:rect(1, 1, tw, th, theme.body)

        ag:text(8, 4, "MineOS " .. VERSION, theme.accent, theme.body, LARGE_W, 3)
        local lines = {
            { "A pixel-graphics desktop, one step up from CraftOS.", colours.black },
            { "", colours.black },
            { "Runtime " .. _VERSION .. "  Screen " .. W .. "x" .. H .. " pixels", colours.grey },
            { "", colours.black },
            { "Drag title bars to move, the corner grip to resize.", colours.grey },
            { "Right-click the desktop and files for menus.", colours.grey },
            { "Double-click icons to open them.", colours.grey },
            { "", colours.black },
            { "Make your own app: /apps/hello.lua", colours.black },
            { "  --#name Hello", colours.green },
            { "  --#icon H", colours.green },
            { "  --#colour lime", colours.green },
            { "  --#legacy true   (classic terminal app)", colours.green },
            { "  print(\"hi!\")", colours.green },
        }
        for i, line in ipairs(lines) do
            local y = 4 + GLYPH_H * 3 + 6 + (i - 1) * MENU_ROW
            if y + MENU_ROW > th then break end
            ag:text(8, y, line[1]:sub(1, math.floor(tw / (GLYPH_W * SMALL_W)) - 2), line[2], theme.body, SMALL_W, SMALL_H)
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
    { name = "Terminal", icon = ">", colour = colours.black, fn = appTerminal, kind = "legacy" },
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
                    kind = meta.legacy == "true" and "legacy" or "pixel",
                    fn = function(wnd, argument)
                        local env = setmetatable({ mineos = mineosAPI }, { __index = _ENV })
                        local fn, err = loadfile(path, nil, env)
                        if not fn then error(err, 0) end
                        fn(argument)
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
    openMenu(1, H - TASKBAR_H - #items * MENU_ROW, items)
end

local function openDesktopMenu(x, y)
    openMenu(x, y, {
        { label = "New file...", action = function()
            launchApp({ name = "Edit", kind = "legacy", fn = function()
                write("File name: ")
                local name = read()
                if name and name ~= "" then shell.run("/rom/programs/edit.lua", "/" .. fs.combine("", name)) end
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
    if y > H - TASKBAR_H then
        local startW = g:textWidth("MineOS", SMALL_W) + 12
        if x <= startW then
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
            if x >= ix and x < ix + SLOT_W and y >= iy - 2 and y < iy + SLOT_H - 4 then
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

    if wy <= TITLE_H then
        -- Title bar.
        if button == 2 then
            openWindowMenu(wnd, x, y)
        elseif wx > wnd.w - BTN_W then
            closeWindow(wnd)
        elseif wx > wnd.w - BTN_W * 2 then
            toggleMaximise(wnd)
        elseif wx > wnd.w - BTN_W * 3 then
            minimiseWindow(wnd)
        elseif isDoubleClick(wnd) then
            toggleMaximise(wnd)
        elseif not wnd.maximised then
            dragging = { wnd = wnd, dx = wx, dy = wy }
        end
        return
    end

    if wy > wnd.h - GRIP_H and wx > wnd.w - GRIP_W and not wnd.maximised then
        resizing = { wnd = wnd }
        return
    end

    contentMouse(wnd, "mouse_click", button, wx, wy)
end

local function handleEvent(event)
    local name = event[1]

    if name == "terminate" then
        running = false
        return
    end

    -- Every mouse event carries a position: the pointer tracks through clicks
    -- and drags, not just plain movement.
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
            density = event[2]
        end
    elseif name == "term_resize" then
        W, H = root.getSize()
        screen.reposition(1, 1, W, H)
        for _, wnd in ipairs(windows) do
            if wnd.maximised then
                wnd.x, wnd.y, wnd.w, wnd.h = 1, 1, W, H - TASKBAR_H
                wnd.win.reposition(1, 1, wnd.w, wnd.h)
                contentReposition(wnd)
                resumeWindow(wnd, "term_resize")
            else
                if wnd.w > W or wnd.h > H - TASKBAR_H then
                    wnd.w = math.min(wnd.w, W)
                    wnd.h = math.min(wnd.h, H - TASKBAR_H)
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
            -- Coalesce: remember only the latest position and repaint once the
            -- burst has drained (the flush marker queues behind pending input).
            local drag = dragging or resizing
            drag.pending = { x = x, y = y }
            if not drag.queued then
                drag.queued = true
                os.queueEvent("mineos_flush")
            end
        else
            local wnd, wx, wy = hitTest(x, y)
            if wnd and wnd == focused() then
                contentMouse(wnd, "mouse_drag", event[2], wx, wy)
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
            if wnd and wnd == focused() then
                contentMouse(wnd, "mouse_up", event[2], wx, wy)
            end
        end
    elseif name == "mouse_scroll" then
        local wnd, wx, wy = hitTest(event[3], event[4])
        if wnd and wnd == focused() then
            contentMouse(wnd, "mouse_scroll", event[2], wx, wy)
        end
    elseif name == "mouse_move" then
        pointer.x, pointer.y = event[2], event[3]
        pointer.inside = true
        pointer.seen = true
        if not dragging and not resizing then
            local wnd, wx, wy = hitTest(pointer.x, pointer.y)
            if wnd then
                contentMouse(wnd, "mouse_move", nil, wx, wy)
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
    -- so apps never see (or overwrite) stale arrow pixels.
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
