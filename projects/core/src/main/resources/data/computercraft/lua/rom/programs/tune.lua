-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

local args = { ... }

-- Tune a screen to a camera channel: the billboard's own display when run on
-- one, otherwise an attached monitor.
local screen, description
if display then
    screen = display
    description = "billboard screen"
else
    for _, side in ipairs(peripheral.getNames()) do
        if peripheral.getType(side) == "monitor" then
            screen = peripheral.wrap(side)
            description = "monitor (" .. side .. ")"
            break
        end
    end
    if not screen then
        printError("Nothing to tune: run this on a billboard, or attach a monitor.")
        return
    end
end

if #args == 0 then
    local channel = screen.getChannel()
    if channel then
        print(("The %s is tuned to channel %d."):format(description, channel))
    else
        print(("The %s is showing its terminal. Use 'tune <channel>' to show a camera."):format(description))
    end
    return
end

if args[1] == "off" then
    screen.clearChannel()
    print(("The %s is back to its terminal."):format(description))
    return
end

local channel = tonumber(args[1])
if not channel then
    print("Usage: tune [<channel> | off]")
    return
end

screen.setChannel(channel)
print(("Tuned the %s to channel %d."):format(description, channel))
