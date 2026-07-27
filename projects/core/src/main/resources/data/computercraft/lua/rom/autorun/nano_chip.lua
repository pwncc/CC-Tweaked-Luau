-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

-- Boot a nano computer from its ROM chip: if a chip is mounted and carries a
-- startup program, hand the session over to it.

if not chip then return end -- Only nano computers have the chip API.

if fs.isDir("chip") and fs.exists("chip/startup.lua") then
    shell.run("chip/startup.lua")
end
