-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

local args = { ... }

local function usage()
    print("Usage:")
    print("broadcast <channel> [camera side]")
    print("broadcast off [camera side]")
    print("broadcast aim <yaw> <pitch> [camera side]")
    print("broadcast zoom <fov> [camera side]")
    return
end

local function findCamera(side)
    if side then
        if peripheral.getType(side) ~= "camera" then
            printError(side .. " is not a camera.")
            return nil
        end
        return peripheral.wrap(side)
    end
    local camera = peripheral.find("camera")
    if not camera then printError("No camera attached (place one against this computer, or use a modem).") end
    return camera
end

if #args == 0 then return usage() end

if args[1] == "off" then
    local camera = findCamera(args[2])
    if not camera then return end
    camera.clearChannel()
    print("Broadcast stopped.")
elseif args[1] == "aim" then
    local yaw, pitch = tonumber(args[2]), tonumber(args[3])
    if not yaw or not pitch then return usage() end
    local camera = findCamera(args[4])
    if not camera then return end
    camera.setRotation(yaw, pitch)
    print(("Camera aimed (%d, %d)."):format(yaw, pitch))
elseif args[1] == "zoom" then
    local fov = tonumber(args[2])
    if not fov then return usage() end
    local camera = findCamera(args[3])
    if not camera then return end
    camera.setFov(fov)
    print("Field of view set to " .. fov .. ".")
else
    local channel = tonumber(args[1])
    if not channel then return usage() end
    local camera = findCamera(args[2])
    if not camera then return end
    camera.setChannel(channel)
    print(("Broadcasting on channel %d."):format(channel))
    print("Tune a billboard with: tune " .. channel)
end
