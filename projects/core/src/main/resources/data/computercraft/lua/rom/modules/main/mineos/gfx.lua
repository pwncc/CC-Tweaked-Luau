-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

--[[- Pixel drawing for high-density terminals.

At high resolutions (term.setResolution) each terminal cell is small enough to
act as a pixel: fill it with a background colour and ignore the character.
This module draws rectangles, text (using the CraftOS font as a bitmap) and
pixel-art icons in that style.

Terminal cells are 2:3 (wider than tall... rather, taller than wide): a block
of 3x2 cells is physically square. Text and icons take per-pixel cell sizes
(pxW, pxH) so callers can pick their aspect and size.

@module mineos.gfx
]]

local FONT = require "mineos.font"

-- On the Luau runtime the hot paths (rectangle fills and font
-- rasterisation) run natively; the Lua implementations below are the
-- reference and the fallback for foreign terminal targets.
local native = _CC_NATIVE_GFX
if native and not pcall(native.setFont, FONT.data, FONT.width, FONT.height) then
    native = nil
end

local HEX = "0123456789abcdef"

local function hexOf(colour)
    -- colours.toBlit without the dependency.
    local n = math.floor(math.log(colour) / math.log(2) + 0.5)
    return HEX:sub(n + 1, n + 1)
end

local gfx = {}
gfx.GLYPH_W = FONT.width
gfx.GLYPH_H = FONT.height

local canvas = {}
canvas.__index = canvas

--- Create a canvas over a terminal object (typically a window).
function gfx.new(term)
    return setmetatable({ term = term }, canvas)
end

function canvas:size()
    return self.term.getSize()
end

--- Fill a rectangle of cells with a colour.
function canvas:rect(x, y, w, h, colour)
    if w <= 0 or h <= 0 then return end
    if native and native.fillRect(self.term, x, y, w, h, hexOf(colour)) then return end

    local term = self.term
    local text = (" "):rep(w)
    local hex = hexOf(colour):rep(w)
    for row = y, y + h - 1 do
        term.setCursorPos(x, row)
        term.blit(text, hex, hex)
    end
end

--- Draw a 1-cell outline. Cells are thin, so this is a hairline.
function canvas:frame(x, y, w, h, colour)
    self:rect(x, y, w, 1, colour)
    self:rect(x, y + h - 1, w, 1, colour)
    self:rect(x, y, 2, h, colour)
    self:rect(x + w - 2, y, 2, h, colour)
end

--- The width, in cells, of a string drawn at the given pixel width.
function gfx.textWidth(str, pxW)
    return #str * FONT.width * pxW
end

canvas.textWidth = function(_, str, pxW) return gfx.textWidth(str, pxW) end

--- Draw text using the CraftOS font as pixel art.
--
-- @tparam number x The x cell to start at.
-- @tparam number y The y cell to start at.
-- @tparam string str The text to draw.
-- @tparam number fg The text colour.
-- @tparam number bg The background colour.
-- @tparam number pxW Cells per font pixel, horizontally.
-- @tparam number pxH Cells per font pixel, vertically.
-- @treturn number The width drawn, in cells.
function canvas:text(x, y, str, fg, bg, pxW, pxH)
    if native then
        local width = native.drawText(self.term, x, y, str, hexOf(fg), hexOf(bg), pxW, pxH)
        if width then return width end
    end

    local term = self.term
    local data = FONT.data
    local fgHex, bgHex = hexOf(fg), hexOf(bg)
    local glyphW, glyphH = FONT.width, FONT.height

    for fontRow = 0, glyphH - 1 do
        -- Build one blit row covering the whole string, then repeat it pxH times.
        local colours = {}
        for i = 1, #str do
            local glyph = str:byte(i)
            local mask = data:byte(glyph * glyphH + fontRow + 1) - 35
            for fx = 0, glyphW - 1 do
                local set = bit32.extract(mask, fx) == 1
                colours[#colours + 1] = (set and fgHex or bgHex):rep(pxW)
            end
        end
        local colourRow = table.concat(colours)
        local textRow = (" "):rep(#colourRow)
        for sub = 0, pxH - 1 do
            term.setCursorPos(x, y + fontRow * pxH + sub)
            term.blit(textRow, colourRow, colourRow)
        end
    end
    return #str * glyphW * pxW
end

--- Draw a pixel-art icon: a list of strings of blit colour characters, with
-- spaces (or dots) taking the given background colour. Each icon pixel is
-- drawn as a pxW x pxH block of cells (3x2 is square).
function canvas:icon(x, y, image, bg, pxW, pxH)
    local term = self.term
    local bgHex = hexOf(bg)
    for row = 1, #image do
        local line = image[row]
        local colours = {}
        for i = 1, #line do
            local char = line:sub(i, i)
            if char == " " or char == "." then char = bgHex end
            colours[#colours + 1] = char:rep(pxW)
        end
        local colourRow = table.concat(colours)
        local textRow = (" "):rep(#colourRow)
        for sub = 0, pxH - 1 do
            term.setCursorPos(x, y + (row - 1) * pxH + sub)
            term.blit(textRow, colourRow, colourRow)
        end
    end
end

return gfx
