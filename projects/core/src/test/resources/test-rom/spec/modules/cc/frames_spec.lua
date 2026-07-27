-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

local frames = require "cc.frames"

describe("cc.frames", function()
    local function rgb888(width, height, pixels)
        local out = {}
        for i, pixel in ipairs(pixels) do
            out[i] = string.char(
                bit32.rshift(pixel, 16),
                bit32.band(bit32.rshift(pixel, 8), 0xFF),
                bit32.band(pixel, 0xFF)
            )
        end
        return { width = width, height = height, format = "rgb888", data = table.concat(out) }
    end

    describe("frames.get", function()
        it("reads rgb888 pixels", function()
            local frame = rgb888(2, 1, { 0xFF0000, 0x0000FF })
            local r, g, b = frames.get(frame, 1, 1)
            expect({ r, g, b }):same { 255, 0, 0 }
            local r2, _, b2 = frames.get(frame, 2, 1)
            expect(r2):eq(0)
            expect(b2):eq(255)
        end)

        it("reads rgb332 pixels", function()
            -- 0xE0 = full red, 0x03 = full blue.
            local frame = { width = 2, height = 1, format = "rgb332", data = "\224\3" }
            local r, g, b = frames.get(frame, 1, 1)
            expect(r):eq(255)
            expect(g):eq(0)
            expect(b):eq(0)
            local _, _, b2 = frames.get(frame, 2, 1)
            expect(b2):eq(255)
        end)

        it("rejects out of bounds reads", function()
            local frame = rgb888(1, 1, { 0 })
            expect.error(frames.get, frame, 2, 1):str_match("out of bounds")
        end)

        it("rejects unknown formats", function()
            expect.error(frames.get, { width = 1, height = 1, format = "bmp", data = "x" }, 1, 1)
                :str_match("bad frame format")
        end)
    end)

    describe("frames.scale", function()
        it("averages when shrinking", function()
            local frame = rgb888(2, 1, { 0x000000, 0xFFFFFF })
            local scaled = frames.scale(frame, 1, 1)
            expect(scaled.format):eq("rgb888")
            local r, g, b = frames.get(scaled, 1, 1)
            -- Both implementations box-filter, so this must be mid-grey.
            expect(r > 100 and r < 155):eq(true)
            expect(g):eq(r)
            expect(b):eq(r)
        end)

        it("stretches when growing", function()
            local frame = rgb888(1, 1, { 0x123456 })
            local scaled = frames.scale(frame, 3, 2)
            expect(scaled.width):eq(3)
            expect(scaled.height):eq(2)
            local r, g, b = frames.get(scaled, 3, 2)
            expect({ r, g, b }):same { 0x12, 0x34, 0x56 }
        end)
    end)

    describe("frames.render", function()
        it("produces a well-formed rendering", function()
            -- A 4x6 frame: left half red, right half blue -> 2x2 characters.
            local pixels = {}
            for y = 1, 6 do
                for x = 1, 4 do
                    pixels[#pixels + 1] = x <= 2 and 0xFF0000 or 0x0000FF
                end
            end
            local rendered = frames.render(rgb888(4, 6, pixels), 2, 2)

            expect(rendered.width):eq(2)
            expect(rendered.height):eq(2)
            expect(#rendered.lines):eq(2)
            expect(#rendered.palette):eq(16)

            for _, line in ipairs(rendered.lines) do
                expect(#line[1]):eq(2)
                expect(#line[2]):eq(2)
                expect(#line[3]):eq(2)
                expect(line[2]:match("^[0-9a-f]+$") ~= nil):eq(true)
                expect(line[3]:match("^[0-9a-f]+$") ~= nil):eq(true)
            end

            -- The palette must contain something reddish and something blueish.
            local has_red, has_blue = false, false
            for _, colour in ipairs(rendered.palette) do
                local r = bit32.rshift(colour, 16)
                local b = bit32.band(colour, 0xFF)
                if r > 150 and b < 100 then has_red = true end
                if b > 150 and r < 100 then has_blue = true end
            end
            expect(has_red):eq(true)
            expect(has_blue):eq(true)
        end)

        it("renders solid colours as solid cells", function()
            local pixels = {}
            for i = 1, 4 * 6 do pixels[i] = 0x00FF00 end
            local rendered = frames.render(rgb888(4, 6, pixels), 2, 2)

            -- A solid frame must render each cell with equal foreground and
            -- background, whatever character was chosen.
            for _, line in ipairs(rendered.lines) do
                expect(line[2]):eq(line[3])
            end
        end)
    end)
end)
