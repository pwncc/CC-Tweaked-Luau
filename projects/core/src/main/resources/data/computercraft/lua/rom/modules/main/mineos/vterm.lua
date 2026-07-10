-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

--[[- A virtual terminal, rendering classic terminal cells onto a
high-density (pixel) terminal.

Legacy programs (the shell, edit, ...) expect a coarse grid of readable
characters. On a high-density desktop a raw cell is a pixel, far too small to
read. A vterm gives such programs a classic terminal whose every cell is
software-rendered as a block of pixels using the CraftOS font - including the
teletext drawing characters, so existing art keeps working.

@module mineos.vterm
]]

local FONT = require "mineos.font"

local HEX = "0123456789abcdef"
local GLYPH_W, GLYPH_H = FONT.width, FONT.height

local vterm = {}

--- Create a virtual terminal.
--
-- @tparam table parent The (high-density) terminal to render onto.
-- @tparam number cols The width of the virtual terminal, in characters.
-- @tparam number rows The height of the virtual terminal, in characters.
-- @tparam number pxW Cells per font pixel, horizontally.
-- @tparam number pxH Cells per font pixel, vertically.
-- @treturn table A term redirect object (with a `.render` method).
function vterm.new(parent, cols, rows, pxW, pxH)
    local cellW, cellH = GLYPH_W * pxW, GLYPH_H * pxH

    local text, fg, bg = {}, {}, {}
    for y = 1, rows do
        text[y] = (" "):rep(cols)
        fg[y] = ("0"):rep(cols)
        bg[y] = ("f"):rep(cols)
    end

    local curX, curY = 1, 1
    local curFg, curBg = "0", "f"
    local blink = false

    local function toHex(colour)
        local n = math.floor(math.log(colour) / math.log(2) + 0.5)
        return HEX:sub(n + 1, n + 1)
    end

    --- Render one virtual row (1-based) to the parent.
    local function renderRow(y)
        local rowText, rowFg, rowBg = text[y], fg[y], bg[y]
        local showCursor = blink and curY == y and curX >= 1 and curX <= cols

        for fontRow = 0, GLYPH_H - 1 do
            local colours = {}
            for i = 1, cols do
                local fgHex, bgHex = rowFg:sub(i, i), rowBg:sub(i, i)
                local mask = FONT.data:byte(rowText:byte(i) * GLYPH_H + fontRow + 1) - 35
                -- The cursor renders as an underscore overlay on its cell.
                if showCursor and i == curX and fontRow == GLYPH_H - 2 then mask = 63 end
                for fx = 0, GLYPH_W - 1 do
                    colours[#colours + 1] = (bit32.extract(mask, fx) == 1 and fgHex or bgHex):rep(pxW)
                end
            end
            local colourRow = table.concat(colours)
            local blank = (" "):rep(#colourRow)
            for sub = 0, pxH - 1 do
                parent.setCursorPos(1, (y - 1) * cellH + fontRow * pxH + sub + 1)
                parent.blit(blank, colourRow, colourRow)
            end
        end
    end

    local function renderAll()
        for y = 1, rows do renderRow(y) end
    end

    local lastCursorY = nil
    local function moveCursor(x, y)
        local oldY = lastCursorY
        curX, curY = x, y
        lastCursorY = y
        if blink then
            if oldY and oldY ~= y and oldY >= 1 and oldY <= rows then renderRow(oldY) end
            if y >= 1 and y <= rows then renderRow(y) end
        end
    end

    local redirect = {}

    function redirect.getSize() return cols, rows end
    function redirect.getCursorPos() return curX, curY end
    function redirect.setCursorPos(x, y) moveCursor(math.floor(x), math.floor(y)) end
    function redirect.getCursorBlink() return blink end

    function redirect.setCursorBlink(value)
        if blink == value then return end
        blink = value
        if curY >= 1 and curY <= rows then renderRow(curY) end
    end

    function redirect.isColour() return parent.isColour() end
    redirect.isColor = redirect.isColour

    function redirect.setTextColour(colour) curFg = toHex(colour) end
    redirect.setTextColor = redirect.setTextColour
    function redirect.setBackgroundColour(colour) curBg = toHex(colour) end
    redirect.setBackgroundColor = redirect.setBackgroundColour

    function redirect.getTextColour() return 2 ^ tonumber(curFg, 16) end
    redirect.getTextColor = redirect.getTextColour
    function redirect.getBackgroundColour() return 2 ^ tonumber(curBg, 16) end
    redirect.getBackgroundColor = redirect.getBackgroundColour

    local function put(str, fgStr, bgStr)
        if curY < 1 or curY > rows then
            curX = curX + #str
            return
        end
        local startX = curX
        local from, to = 1, #str
        if startX < 1 then
            from = 2 - startX
            startX = 1
        end
        if startX + (to - from) > cols then to = from + (cols - startX) end
        if from <= to then
            local left, right = startX - 1, startX + (to - from) + 1
            text[curY] = text[curY]:sub(1, left) .. str:sub(from, to) .. text[curY]:sub(right)
            fg[curY] = fg[curY]:sub(1, left) .. fgStr:sub(from, to) .. fg[curY]:sub(right)
            bg[curY] = bg[curY]:sub(1, left) .. bgStr:sub(from, to) .. bg[curY]:sub(right)
            renderRow(curY)
        end
        curX = curX + #str
    end

    function redirect.write(str)
        str = tostring(str)
        put(str, curFg:rep(#str), curBg:rep(#str))
    end

    function redirect.blit(str, fgStr, bgStr)
        if #str ~= #fgStr or #str ~= #bgStr then error("Arguments must be the same length", 2) end
        put(str, fgStr:lower(), bgStr:lower())
    end

    function redirect.clear()
        for y = 1, rows do
            text[y] = (" "):rep(cols)
            fg[y] = curFg:rep(cols)
            bg[y] = curBg:rep(cols)
        end
        renderAll()
    end

    function redirect.clearLine()
        if curY < 1 or curY > rows then return end
        text[curY] = (" "):rep(cols)
        fg[curY] = curFg:rep(cols)
        bg[curY] = curBg:rep(cols)
        renderRow(curY)
    end

    function redirect.scroll(n)
        if n == 0 then return end
        -- Iterate in copy-safe order: towards the direction the content moves.
        local first, last, step = 1, rows, 1
        if n < 0 then first, last, step = rows, 1, -1 end
        for y = first, last, step do
            local from = y + n
            if from >= 1 and from <= rows then
                text[y], fg[y], bg[y] = text[from], fg[from], bg[from]
            else
                text[y] = (" "):rep(cols)
                fg[y] = curFg:rep(cols)
                bg[y] = curBg:rep(cols)
            end
        end
        renderAll()
    end

    function redirect.getLine(y)
        if y < 1 or y > rows then error("Line is out of range.", 2) end
        return text[y], fg[y], bg[y]
    end

    function redirect.setPaletteColour(...) return parent.setPaletteColour(...) end
    redirect.setPaletteColor = redirect.setPaletteColour
    function redirect.getPaletteColour(...) return parent.getPaletteColour(...) end
    redirect.getPaletteColor = redirect.getPaletteColour

    --- Repaint everything (e.g. after the parent window was resized or damaged).
    redirect.render = renderAll

    --- Resize the virtual terminal, preserving the overlapping content.
    -- The caller should re-render and send the program a term_resize event.
    function redirect.resize(newCols, newRows)
        if newCols == cols and newRows == rows then return end
        for y = 1, newRows do
            if text[y] then
                text[y] = (text[y] .. (" "):rep(newCols)):sub(1, newCols)
                fg[y] = (fg[y] .. curFg:rep(newCols)):sub(1, newCols)
                bg[y] = (bg[y] .. curBg:rep(newCols)):sub(1, newCols)
            else
                text[y] = (" "):rep(newCols)
                fg[y] = curFg:rep(newCols)
                bg[y] = curBg:rep(newCols)
            end
        end
        for y = newRows + 1, rows do
            text[y], fg[y], bg[y] = nil, nil, nil
        end
        cols, rows = newCols, newRows
    end

    --- Convert a parent-cell position to a virtual cell position.
    function redirect.toVirtual(x, y)
        return math.max(1, math.min(cols, math.ceil(x / cellW))),
            math.max(1, math.min(rows, math.ceil(y / cellH)))
    end

    return redirect
end

--- The size of virtual terminal which fits in the given cell area.
function vterm.fit(cells_w, cells_h, pxW, pxH)
    return math.max(1, math.floor(cells_w / (GLYPH_W * pxW))),
        math.max(1, math.floor(cells_h / (GLYPH_H * pxH)))
end

return vterm
