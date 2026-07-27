-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

local tracker = require "cc.tracker"

local args = { ... }

local speaker = peripheral.find("speaker")
if not speaker then
    printError("This program needs a speaker (attach one, or craft a noisy turtle).")
    return
end

-- A little built-in tune, so `tracker demo` works out of the box.
local demo = {
    name = "Floppy Disco",
    tempo = 3,
    loop = true,
    instruments = { "harp", "bass", "hat" },
    patterns = {
        {
            "A1 1 7|A0 2 9|F#1 3 4", "---|---|---", "C2 1 6|---|F#1 3 3", "E2 1 7|---|---",
            "A1 1 5|A0 2 9|F#1 3 4", "---|---|---", "C2 1 6|---|F#1 3 3", "E2 1 7|---|---",
            "G1 1 7|G0 2 9|F#1 3 4", "---|---|---", "B1 1 6|---|F#1 3 3", "D2 1 7|---|---",
            "G1 1 5|G0 2 9|F#1 3 4", "---|---|---", "B1 1 6|---|F#1 3 3", "D2 1 7|---|---",
        },
        {
            "F1 1 8|D1 2 9|F#1 3 5", "---|---|F#1 3 2", "A1 1 7|---|F#1 3 3", "C2 1 8|---|F#1 3 2",
            "E2 1 8|E1 2 9|F#1 3 5", "---|---|F#1 3 2", "C2 1 6|---|F#1 3 3", "A1 1 6|---|F#1 3 2",
            "F1 1 8|D1 2 9|F#1 3 5", "A1 1 6|---|F#1 3 2", "C2 1 7|---|F#1 3 3", "F2 1 8|---|F#1 3 2",
            "E2 1 8|E1 2 9|F#1 3 5", "C2 1 6|---|F#1 3 2", "B1 1 6|G0 2 9|F#1 3 3", "D2 1 7|---|F#1 3 2",
        },
    },
    order = { 1, 1, 2, 2 },
}

local song_data
if #args == 0 or args[1] == "demo" then
    song_data = demo
else
    local path = shell.resolve(args[1])
    if not fs.exists(path) and fs.exists(path .. ".trk") then path = path .. ".trk" end
    if not fs.exists(path) then
        printError("No such song: " .. args[1])
        print("Usage: tracker [demo | <file>]")
        return
    end

    local handle = fs.open(path, "r")
    local contents = handle.readAll()
    handle.close()

    local parsed = textutils.unserialise(contents)
    if not parsed then
        printError("Not a song file (expected a serialised Lua table).")
        return
    end
    song_data = parsed
end

local ok, song = pcall(tracker.parse, song_data)
if not ok then
    printError("Bad song: " .. tostring(song))
    return
end

local player = tracker.new(song)

local width, height = term.getSize()
local paused = false

-- Rebuild a readable cell for each channel of the current row.
local function row_text(pattern_id, row)
    local cells = song.patterns[pattern_id][row]
    local by_channel = {}
    for _, cell in ipairs(cells) do
        by_channel[cell.channel] = ("%-3s %d"):format(tracker.note_name(cell.pitch), cell.instrument)
    end

    local out = {}
    for i = 1, 3 do out[i] = by_channel[i] or "---  " end
    return table.concat(out, " | ")
end

local function draw()
    local colour = term.isColour()
    term.setBackgroundColour(colours.black)
    term.clear()

    term.setCursorPos(1, 1)
    term.setTextColour(colour and colours.yellow or colours.white)
    term.write(("%s  [%d/%d]"):format(song.name, player.position, #song.order):sub(1, width))

    term.setCursorPos(1, 2)
    term.setTextColour(colour and colours.lightGrey or colours.white)
    term.write(("tempo %d  %s"):format(song.tempo, paused and "|| paused" or "> playing"):sub(1, width))

    -- A window of rows centred on the playhead.
    local pattern_id = song.order[player.position]
    local pattern = song.patterns[pattern_id]
    local view_top = 4
    local view_rows = height - view_top
    local first = math.max(1, math.min(player.row - math.floor(view_rows / 2), #pattern - view_rows + 1))

    for i = 0, view_rows - 1 do
        local row = first + i
        if row > #pattern then break end
        term.setCursorPos(1, view_top + i)
        if row == player.row then
            term.setTextColour(colour and colours.white or colours.white)
            term.write((">%2d %s"):format(row, row_text(pattern_id, row)):sub(1, width))
        else
            term.setTextColour(colour and colours.grey or colours.white)
            term.write((" %2d %s"):format(row, row_text(pattern_id, row)):sub(1, width))
        end
    end

    term.setCursorPos(1, 3)
    term.setTextColour(colour and colours.lightBlue or colours.white)
    term.write(("space pause  +/- tempo  q quit"):sub(1, width))
end

draw()
local timer = os.startTimer(player:row_time())

while true do
    local event, arg = os.pullEvent()
    if event == "timer" and arg == timer then
        if not paused then
            local notes = player:tick()
            if not notes then break end
            for _, note in ipairs(notes) do
                speaker.playNote(note.instrument, note.volume, note.pitch)
            end
            draw()
        end
        timer = os.startTimer(player:row_time())
    elseif event == "key" then
        if arg == keys.q then
            break
        elseif arg == keys.space then
            paused = not paused
            draw()
        elseif arg == keys.equals or arg == keys.numPadAdd then
            song.tempo = math.max(1, song.tempo - 1)
            draw()
        elseif arg == keys.minus or arg == keys.numPadSubtract then
            song.tempo = math.min(20, song.tempo + 1)
            draw()
        end
    end
end

term.setBackgroundColour(colours.black)
term.setTextColour(colours.white)
term.clear()
term.setCursorPos(1, 1)
print("Thanks for listening!")
