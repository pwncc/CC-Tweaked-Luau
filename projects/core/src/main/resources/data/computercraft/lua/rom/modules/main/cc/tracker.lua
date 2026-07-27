-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

--[[- A tiny pattern-based music tracker for the speaker.

Songs are tables of patterns; a pattern is a list of rows and a row is a
`|`-separated list of channel cells. Each cell is either `---` (rest) or a
note name, an instrument index, and an optional volume digit:

    C1 1     -- play C1 on instrument 1
    F#1 2 9  -- play F#1 on instrument 2 at full volume
    ---      -- rest

Note names run from `F#0` (the speaker's lowest pitch) to `F#2` (its
highest), with the octave number stepping up at each C.

This module is the engine only: it parses songs and steps through them,
returning the notes to play each row. The `tracker` program supplies the
speaker and the interface.

@module cc.tracker
@since 1.121.0
@usage Step through a song, printing the notes of each row.

    local tracker = require "cc.tracker"
    local player = tracker.new(tracker.parse(song_data))
    while true do
        local notes = player:tick()
        if not notes then break end
        for _, note in ipairs(notes) do
            print(("channel %d: %s #%d vol %.1f"):format(
                note.channel, note.instrument, note.pitch, note.volume))
        end
    end
]]

local expect = require "cc.expect"
local expect, field = expect.expect, expect.field

local NOTE_OFFSETS = {
    C = 0, ["C#"] = 1, D = 2, ["D#"] = 3, E = 4, F = 5,
    ["F#"] = 6, G = 7, ["G#"] = 8, A = 9, ["A#"] = 10, B = 11,
}

local tracker = {}

--- Convert a note name (such as `"F#1"` or `"C2"`) to a speaker pitch (0-24).
--
-- @tparam string name The note name.
-- @treturn[1] number The speaker pitch.
-- @treturn[2] nil If the note name is invalid.
-- @treturn[2] string The reason the name is invalid.
function tracker.pitch(name)
    expect(1, name, "string")
    local note, octave = name:match("^([A-G]#?)(%d)$")
    if not note then return nil, ("malformed note %q"):format(name) end

    local offset = NOTE_OFFSETS[note]
    local pitch = tonumber(octave) * 12 + offset - 6
    if pitch < 0 or pitch > 24 then
        return nil, ("note %s is out of the speaker's range (F#0 to F#2)"):format(name)
    end
    return pitch
end

--- The name of a speaker pitch, inverting [`tracker.pitch`].
--
-- @tparam number pitch The pitch, between 0 and 24.
-- @treturn string The note name.
function tracker.note_name(pitch)
    expect(1, pitch, "number")
    if pitch < 0 or pitch > 24 then error("pitch out of range (0-24)", 2) end
    local names = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" }
    local from_c = pitch + 6
    return names[from_c % 12 + 1] .. math.floor(from_c / 12)
end

local function parse_cell(cell, instruments, where)
    cell = cell:match("^%s*(.-)%s*$")
    if cell == "" or cell == "---" then return nil end

    local note, instrument, volume = cell:match("^(%S+)%s+(%d+)%s*(%d?)$")
    if not note then
        error(("%s: malformed cell %q (expected 'NOTE INSTRUMENT [VOLUME]' or '---')"):format(where, cell), 0)
    end

    local pitch, err = tracker.pitch(note)
    if not pitch then error(("%s: %s"):format(where, err), 0) end

    instrument = tonumber(instrument)
    if not instruments[instrument] then
        error(("%s: no instrument %d (the song defines %d)"):format(where, instrument, #instruments), 0)
    end

    local vol = volume ~= "" and tonumber(volume) / 9 * 3 or 1
    return { pitch = pitch, instrument = instrument, volume = vol }
end

--- Parse and validate a song table.
--
-- A song is a table with the following fields:
--
-- - `name` (optional string): The song's title.
-- - `tempo` (optional number): Game ticks per row, between 1 and 20.
--   Defaults to 4 (five rows a second).
-- - `loop` (optional boolean): Whether the song repeats once finished.
-- - `instruments`: A list of speaker instrument names, such as `"harp"` or
--   `"bass"`. Cells refer to instruments by index into this list.
-- - `patterns`: A list of patterns, each a list of row strings.
-- - `order` (optional): The sequence of pattern indices to play. Defaults
--   to playing each pattern once, in order.
--
-- @tparam table song The song data, typically loaded via [`textutils.unserialise`].
-- @treturn table The compiled song, for use with [`tracker.new`].
-- @throws If the song is malformed.
function tracker.parse(song)
    expect(1, song, "table")
    field(song, "name", "string", "nil")
    field(song, "tempo", "number", "nil")
    field(song, "loop", "boolean", "nil")
    field(song, "instruments", "table")
    field(song, "patterns", "table")
    field(song, "order", "table", "nil")

    local tempo = song.tempo or 4
    if tempo < 1 or tempo > 20 then error("tempo must be between 1 and 20 ticks per row", 0) end

    if #song.instruments == 0 then error("song defines no instruments", 0) end
    for i, instrument in ipairs(song.instruments) do
        if type(instrument) ~= "string" then
            error(("instrument %d: expected string, got %s"):format(i, type(instrument)), 0)
        end
    end

    if #song.patterns == 0 then error("song defines no patterns", 0) end
    local patterns = {}
    for p, pattern in ipairs(song.patterns) do
        if type(pattern) ~= "table" or #pattern == 0 then
            error(("pattern %d: expected a non-empty list of rows"):format(p), 0)
        end

        local rows = {}
        for r, row in ipairs(pattern) do
            if type(row) ~= "string" then
                error(("pattern %d, row %d: expected string, got %s"):format(p, r, type(row)), 0)
            end

            local cells, channel = {}, 0
            for cell in (row .. "|"):gmatch("([^|]*)|") do
                channel = channel + 1
                local where = ("pattern %d, row %d, channel %d"):format(p, r, channel)
                local note = parse_cell(cell, song.instruments, where)
                if note then
                    note.channel = channel
                    cells[#cells + 1] = note
                end
            end
            rows[r] = cells
        end
        patterns[p] = rows
    end

    local order
    if song.order then
        order = {}
        for i, entry in ipairs(song.order) do
            if type(entry) ~= "number" or not patterns[entry] then
                error(("order entry %d: no such pattern %s"):format(i, tostring(entry)), 0)
            end
            order[i] = entry
        end
        if #order == 0 then error("order is empty", 0) end
    else
        order = {}
        for i = 1, #patterns do order[i] = i end
    end

    return {
        name = song.name or "Untitled",
        tempo = tempo,
        loop = song.loop or false,
        instruments = song.instruments,
        patterns = patterns,
        order = order,
    }
end

local Player = {}
local Player_mt = { __index = Player }

--- Advance the player by one row.
--
-- @treturn[1] { table... } The notes to play this row: each has `channel`,
-- `instrument` (the speaker instrument name), `pitch` and `volume` fields.
-- The list is empty for a rest row.
-- @treturn[2] nil If the song has finished (and does not loop).
function Player:tick()
    if self.finished then return nil end

    local pattern = self.song.patterns[self.song.order[self.position]]
    local cells = pattern[self.row]

    local notes = {}
    for i, cell in ipairs(cells) do
        notes[i] = {
            channel = cell.channel,
            instrument = self.song.instruments[cell.instrument],
            pitch = cell.pitch,
            volume = cell.volume,
        }
    end

    -- Advance to the next row, pattern or (on loop) the song start.
    self.row = self.row + 1
    if self.row > #pattern then
        self.row = 1
        self.position = self.position + 1
        if self.position > #self.song.order then
            self.position = 1
            if not self.song.loop then self.finished = true end
        end
    end

    return notes
end

--- Restart the song from a given position in the order.
--
-- @tparam[opt=1] number position The order index to seek to.
function Player:seek(position)
    expect(1, position, "number", "nil")
    position = position or 1
    if not self.song.order[position] then error("no such position " .. position, 2) end
    self.position = position
    self.row = 1
    self.finished = false
end

--- The number of seconds each row lasts, derived from the song's tempo.
--
-- @treturn number The row duration in seconds.
function Player:row_time()
    return self.song.tempo * 0.05
end

--- Create a player which steps through a parsed song.
--
-- @tparam table song A song from [`tracker.parse`].
-- @treturn table The player.
function tracker.new(song)
    expect(1, song, "table")
    return setmetatable({ song = song, position = 1, row = 1, finished = false }, Player_mt)
end

return tracker
