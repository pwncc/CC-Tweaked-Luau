-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

-- MineOS boot hook: on the very first boot of an advanced computer, ask
-- whether to use CraftOS or MineOS as the default, and honour that choice on
-- every boot after.

settings.define("mineos.autostart", {
    description = "Boot into the MineOS desktop instead of the CraftOS shell.",
    default = nil,
    type = "boolean",
})
settings.define("mineos.mirror", {
    description = "Mirror the MineOS desktop onto attached monitors.",
    default = false,
    type = "boolean",
})

-- Only regular, advanced, interactive computers: no turtles or command
-- computers, and skip entirely when the user has their own startup scripts
-- (automated computers should never sit at a prompt).
if turtle or commands or pocket then return end
if not term.isColour() then return end
if fs.exists("startup") or fs.exists("startup.lua") then return end

local choice = settings.get("mineos.autostart")

if choice == nil then
    -- First boot: ask.
    local w, h = term.getSize()
    term.setBackgroundColour(colours.black)
    term.clear()

    local function centre(y, text, fg, bg)
        term.setCursorPos(math.floor((w - #text) / 2) + 1, y)
        term.setTextColour(fg or colours.white)
        term.setBackgroundColour(bg or colours.black)
        term.write(text)
    end

    centre(3, " Welcome! ", colours.yellow)
    centre(5, "How would you like to use this computer?", colours.white)

    local craftY, mineY = 8, 11
    centre(craftY, "  [1] CraftOS - the classic shell   ", colours.white, colours.grey)
    centre(mineY, "  [2] MineOS  - a graphical desktop ", colours.white, colours.blue)
    centre(14, "Click or press 1/2. Change later in Settings", colours.lightGrey)
    centre(15, "or with: set mineos.autostart true/false", colours.lightGrey)

    local chosen
    while chosen == nil do
        local event, a, _, y = os.pullEvent()
        if event == "key" then
            if a == keys.one then chosen = false
            elseif a == keys.two then chosen = true
            end
        elseif event == "mouse_click" then
            if y == craftY then chosen = false
            elseif y == mineY then chosen = true
            end
        end
    end

    settings.set("mineos.autostart", chosen)
    settings.save()
    choice = chosen

    term.setBackgroundColour(colours.black)
    term.setTextColour(colours.white)
    term.clear()
    term.setCursorPos(1, 1)
end

if choice then
    shell.run("/rom/programs/mineos.lua")
end
