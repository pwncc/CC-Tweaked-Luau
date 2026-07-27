-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

--[[- Display RGB image frames (such as camera captures) on terminals and
monitors.

A frame is a table with `width`, `height`, `format` (`"rgb332"` or
`"rgb888"`) and `data` (a string of packed pixels) - the shape produced by
[`camera.capture`]. This module turns frames into terminal-ready form:
[`render`] converts a frame into a 16-colour palette plus teletext blit
lines at 2x3 subpixels per character, and [`draw`] puts them on screen.

On the Luau runtime rendering runs natively (with a palette fitted to the
image), fast enough for live playback; other runtimes fall back to a pure
Lua implementation using the default palette.

@module cc.frames
@since 1.121.0
@usage Show a camera frame on the terminal.

    local frames = require "cc.frames"
    local camera = peripheral.find("camera")
    local frame = camera.capture(160, 90)
    local w, h = term.getSize()
    frames.draw(frames.render(frame, w, h))
]]

local expect = require "cc.expect"
local expect, field = expect.expect, expect.field

local native = _CC_NATIVE_FRAMES

-- The default terminal palette, used by the fallback renderer.
local DEFAULT_PALETTE = {
    0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2,
    0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
    0x999999, 0x4C99B2, 0xB266E5, 0x3366CC,
    0x7F664C, 0x57A64E, 0xCC4C4C, 0x111111,
}

local HEX = "0123456789abcdef"

local frames = {}

local function check_frame(index, frame)
    expect(index, frame, "table")
    field(frame, "width", "number")
    field(frame, "height", "number")
    field(frame, "format", "string")
    field(frame, "data", "string")
    if frame.format ~= "rgb332" and frame.format ~= "rgb888" then
        error(("bad frame format %q (expected rgb332 or rgb888)"):format(frame.format), 3)
    end
    return frame
end

local function pixel_at(frame, x, y)
    local index = (y - 1) * frame.width + (x - 1)
    if frame.format == "rgb332" then
        local v = frame.data:byte(index + 1)
        return math.floor(bit32.rshift(v, 5) * 255 / 7),
            math.floor(bit32.band(bit32.rshift(v, 2), 7) * 255 / 7),
            math.floor(bit32.band(v, 3) * 255 / 3)
    else
        local r, g, b = frame.data:byte(index * 3 + 1, index * 3 + 3)
        return r, g, b
    end
end

--- Get the colour of a single pixel of a frame.
--
-- @tparam table frame The frame to read.
-- @tparam number x The x position, between 1 and the frame's width.
-- @tparam number y The y position, between 1 and the frame's height.
-- @treturn number The red component, between 0 and 255.
-- @treturn number The green component.
-- @treturn number The blue component.
function frames.get(frame, x, y)
    check_frame(1, frame)
    expect(2, x, "number")
    expect(3, y, "number")
    if x < 1 or x > frame.width or y < 1 or y > frame.height then
        error("Position out of bounds", 2)
    end

    if native then return native.get(frame.data, frame.width, frame.height, frame.format, x, y) end
    return pixel_at(frame, x, y)
end

local function scale_lua(frame, target_w, target_h)
    local out = {}
    local n = 0
    for y = 1, target_h do
        local y0 = math.floor((y - 1) * frame.height / target_h) + 1
        local y1 = math.max(y0, math.floor(y * frame.height / target_h))
        for x = 1, target_w do
            local x0 = math.floor((x - 1) * frame.width / target_w) + 1
            local x1 = math.max(x0, math.floor(x * frame.width / target_w))

            local r, g, b, count = 0, 0, 0, 0
            for sy = y0, y1 do
                for sx = x0, x1 do
                    local pr, pg, pb = pixel_at(frame, sx, sy)
                    r, g, b, count = r + pr, g + pg, b + pb, count + 1
                end
            end
            n = n + 1
            out[n] = string.char(math.floor(r / count), math.floor(g / count), math.floor(b / count))
        end
    end
    return table.concat(out)
end

--- Scale a frame to a new size, averaging pixels.
--
-- @tparam table frame The frame to scale.
-- @tparam number width The new width.
-- @tparam number height The new height.
-- @treturn table The scaled frame, in `rgb888` format.
function frames.scale(frame, width, height)
    check_frame(1, frame)
    expect(2, width, "number")
    expect(3, height, "number")
    if width < 1 or height < 1 then error("Target size out of range", 2) end

    local data
    if native then
        data = native.scale(frame.data, frame.width, frame.height, frame.format, width, height)
    else
        data = scale_lua(frame, width, height)
    end
    return { width = width, height = height, format = "rgb888", data = data }
end

local function nearest_palette(r, g, b)
    local best, best_dist = 1, math.huge
    for i = 1, 16 do
        local c = DEFAULT_PALETTE[i]
        local dr = r - bit32.rshift(c, 16)
        local dg = g - bit32.band(bit32.rshift(c, 8), 0xFF)
        local db = b - bit32.band(c, 0xFF)
        local dist = dr * dr + dg * dg + db * db
        if dist < best_dist then best, best_dist = i, dist end
    end
    return best
end

local function render_lua(frame, char_w, char_h)
    -- Downscale to the subpixel grid, then map to the default palette.
    local sub = frames.scale(frame, char_w * 2, char_h * 3)
    local sub_w = char_w * 2

    local indexed = {}
    for y = 1, char_h * 3 do
        for x = 1, sub_w do
            local r, g, b = pixel_at(sub, x, y)
            indexed[(y - 1) * sub_w + x] = nearest_palette(r, g, b)
        end
    end

    local lines = {}
    for cy = 1, char_h do
        local text, fg, bg = {}, {}, {}
        for cx = 1, char_w do
            local cell = {}
            for i = 0, 5 do
                local sx = (cx - 1) * 2 + i % 2 + 1
                local sy = (cy - 1) * 3 + math.floor(i / 2) + 1
                cell[i + 1] = indexed[(sy - 1) * sub_w + sx]
            end

            local counts = {}
            for _, c in ipairs(cell) do counts[c] = (counts[c] or 0) + 1 end
            local first, second
            for c, n in pairs(counts) do
                if not first or n > counts[first] then
                    second = first
                    first = c
                elseif not second or n > counts[second] then
                    second = c
                end
            end
            second = second or first

            local bits = 0
            for i = 1, 6 do
                local c = cell[i]
                local pick_first = c == first
                if not pick_first and c ~= second then
                    local cr = DEFAULT_PALETTE[c]
                    local fr = DEFAULT_PALETTE[first]
                    local sr = DEFAULT_PALETTE[second]
                    local function dist(a, b2)
                        local dr = bit32.rshift(a, 16) - bit32.rshift(b2, 16)
                        local dg = bit32.band(bit32.rshift(a, 8), 0xFF) - bit32.band(bit32.rshift(b2, 8), 0xFF)
                        local db = bit32.band(a, 0xFF) - bit32.band(b2, 0xFF)
                        return dr * dr + dg * dg + db * db
                    end
                    pick_first = dist(cr, fr) <= dist(cr, sr)
                end
                if pick_first then bits = bit32.bor(bits, bit32.lshift(1, i - 1)) end
            end

            local cell_fg, cell_bg = first, second
            if bit32.band(bits, 0x20) ~= 0 then
                bits = bit32.band(bit32.bnot(bits), 0x3F)
                cell_fg, cell_bg = second, first
            end

            text[cx] = string.char(0x80 + bit32.band(bits, 0x1F))
            fg[cx] = HEX:sub(cell_fg, cell_fg)
            bg[cx] = HEX:sub(cell_bg, cell_bg)
        end
        lines[cy] = { table.concat(text), table.concat(fg), table.concat(bg) }
    end

    local palette = {}
    for i = 1, 16 do palette[i] = DEFAULT_PALETTE[i] end
    return { width = char_w, height = char_h, palette = palette, lines = lines }
end

--- Render a frame for a character grid, producing a palette and blit lines.
--
-- @tparam table frame The frame to render.
-- @tparam number width The width of the target area, in characters.
-- @tparam number height The height of the target area, in characters.
-- @treturn table The rendered form: a `palette` of 16 colours and `lines`
-- of `{ text, fg, bg }` triples suitable for [`term.blit`].
function frames.render(frame, width, height)
    check_frame(1, frame)
    expect(2, width, "number")
    expect(3, height, "number")
    if width < 1 or height < 1 then error("Target size out of range", 2) end

    if native then
        local rendered = native.render(frame.data, frame.width, frame.height, frame.format, width, height)
        rendered.width = width
        rendered.height = height
        return rendered
    end
    return render_lua(frame, width, height)
end

--- Present a frame on a screen's pixel buffer (graphics mode), at full pixel
-- fidelity.
--
-- The screen must support graphics mode: monitors and billboard displays do
-- (see `monitor.setGraphicsMode`). This is a small convenience wrapper
-- around the screen's own `drawFrame`.
--
-- @tparam table frame The frame to present.
-- @tparam table screen The screen to draw on: a wrapped monitor, or the
-- `display` API on a billboard.
-- @tparam[opt=1] number x The 1-based x pixel position to draw at.
-- @tparam[opt=1] number y The 1-based y pixel position to draw at.
-- @treturn boolean Whether the screen supported graphics mode.
function frames.present(frame, screen, x, y)
    check_frame(1, frame)
    expect(2, screen, "table")
    expect(3, x, "number", "nil")
    expect(4, y, "number", "nil")

    if not screen.drawFrame then return false end
    screen.drawFrame(frame.width, frame.height, frame.format, frame.data, x, y)
    return true
end

--- Draw a rendered frame to a terminal.
--
-- This sets the terminal's palette to the rendered palette, then blits each
-- line. Pass a [`window`], monitor or other terminal object to draw
-- somewhere other than the current terminal.
--
-- @tparam table rendered A rendered frame from [`frames.render`].
-- @tparam[opt] table target The terminal to draw to, defaulting to the
-- current terminal.
-- @tparam[opt=1] number x The x position to draw at.
-- @tparam[opt=1] number y The y position to draw at.
function frames.draw(rendered, target, x, y)
    expect(1, rendered, "table")
    expect(2, target, "table", "nil")
    expect(3, x, "number", "nil")
    expect(4, y, "number", "nil")
    target = target or term.current()
    x = x or 1
    y = y or 1

    if rendered.palette then
        for i = 1, 16 do
            target.setPaletteColour(2 ^ (i - 1), rendered.palette[i])
        end
    end

    local lines = rendered.lines
    for i = 1, #lines do
        target.setCursorPos(x, y + i - 1)
        target.blit(lines[i][1], lines[i][2], lines[i][3])
    end
end

return frames
