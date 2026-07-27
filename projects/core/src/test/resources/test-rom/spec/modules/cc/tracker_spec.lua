-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

local tracker = require "cc.tracker"

describe("cc.tracker", function()
    local function demo_song(overrides)
        local song = {
            tempo = 2,
            instruments = { "harp", "bass" },
            patterns = {
                { "C1 1|---", "---|A0 2 9", "F#1 1 5|---" },
                { "F#2 1|C1 2" },
            },
        }
        for k, v in pairs(overrides or {}) do song[k] = v end
        return song
    end

    describe("tracker.pitch", function()
        it("maps the speaker's range", function()
            expect(tracker.pitch("F#0")):eq(0)
            expect(tracker.pitch("C1")):eq(6)
            expect(tracker.pitch("A1")):eq(15)
            expect(tracker.pitch("F#2")):eq(24)
        end)

        it("rejects notes outside the range", function()
            local pitch, err = tracker.pitch("C0")
            expect(pitch):eq(nil)
            expect(err):str_match("out of the speaker's range")
            expect(tracker.pitch("G2")):eq(nil)
        end)

        it("rejects malformed names", function()
            local pitch, err = tracker.pitch("H3")
            expect(pitch):eq(nil)
            expect(err):str_match("malformed note")
        end)

        it("round trips through note_name", function()
            for pitch = 0, 24 do
                expect(tracker.pitch(tracker.note_name(pitch))):eq(pitch)
            end
        end)
    end)

    describe("tracker.parse", function()
        it("compiles a song", function()
            local song = tracker.parse(demo_song())
            expect(song.name):eq("Untitled")
            expect(#song.patterns):eq(2)
            expect(song.order):same { 1, 2 }

            local first = song.patterns[1][1]
            expect(#first):eq(1)
            expect(first[1].pitch):eq(6)
            expect(first[1].channel):eq(1)
            expect(first[1].volume):eq(1)
        end)

        it("parses volumes", function()
            local song = tracker.parse(demo_song())
            local cell = song.patterns[1][2][1]
            expect(cell.channel):eq(2)
            expect(cell.volume):eq(3)
        end)

        it("rejects unknown instruments", function()
            expect.error(tracker.parse, demo_song { patterns = { { "C1 9" } } })
                :str_match("no instrument 9")
        end)

        it("rejects malformed cells", function()
            expect.error(tracker.parse, demo_song { patterns = { { "C1" } } })
                :str_match("malformed cell")
        end)

        it("rejects bad order entries", function()
            expect.error(tracker.parse, demo_song { order = { 5 } })
                :str_match("no such pattern 5")
        end)
    end)

    describe("Player", function()
        it("steps through rows and patterns", function()
            local player = tracker.new(tracker.parse(demo_song()))

            local notes = player:tick()
            expect(#notes):eq(1)
            expect(notes[1].instrument):eq("harp")

            expect(#player:tick()):eq(1) -- row 2
            expect(#player:tick()):eq(1) -- row 3
            local last = player:tick()   -- pattern 2, row 1
            expect(#last):eq(2)
            expect(last[2].instrument):eq("bass")

            expect(player:tick()):eq(nil) -- song over
            expect(player:tick()):eq(nil) -- stays over
        end)

        it("loops when asked", function()
            local player = tracker.new(tracker.parse(demo_song { loop = true }))
            for _ = 1, 4 do player:tick() end
            local notes = player:tick() -- wrapped to the start
            expect(#notes):eq(1)
            expect(notes[1].pitch):eq(6)
        end)

        it("seeks", function()
            local player = tracker.new(tracker.parse(demo_song()))
            player:seek(2)
            local notes = player:tick()
            expect(#notes):eq(2)
            expect.error(function() player:seek(9) end):str_match("no such position")
        end)

        it("reports the row time", function()
            local player = tracker.new(tracker.parse(demo_song()))
            expect(player:row_time()):eq(0.1)
        end)
    end)
end)
