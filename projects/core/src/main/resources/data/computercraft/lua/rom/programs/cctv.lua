-- SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
--
-- SPDX-License-Identifier: MPL-2.0

--- The cctv program: stream camera frames over modems, and watch streams.
--
-- `cctv serve` captures frames from an attached camera and broadcasts them
-- (compressed) over rednet. `cctv watch` receives such a stream anywhere on
-- the network and plays it on the terminal or a monitor. Frames also carry a
-- station name, so one wireless network can carry many streams.

local frames = require "cc.frames"

local PROTOCOL = "cctv"

local function printUsage()
    local programName = arg[0] or fs.getName(shell.getRunningProgram())
    print("Usages:")
    print(programName .. " serve <station> [fps] [width] [height]")
    print(programName .. " watch <station> [monitor]")
    print(programName .. " list")
end

local function openModem()
    local modem = peripheral.find("modem", function(_, m) return m.isWireless() end)
        or peripheral.find("modem")
    if not modem then
        error("No modem attached: cctv needs a modem to stream over", 0)
    end
    rednet.open(peripheral.getName(modem))
    return modem
end

local function serve(station, fps, width, height)
    local camera = peripheral.find("camera")
    if not camera then error("No camera attached", 0) end
    openModem()

    fps = tonumber(fps) or 2
    width = tonumber(width) or 320
    height = tonumber(height) or 180
    local interval = 1 / math.max(0.05, math.min(fps, 10))

    if not camera.getChannel() then
        -- Pick an arbitrary broadcast channel derived from the computer id.
        camera.setChannel(os.getComputerID() % 65536)
    end

    print(("Serving %q at %dx%d, target %g fps."):format(station, width, height, 1 / interval))
    print("Hold Ctrl+T to stop.")

    local sequence = 0
    while true do
        local started = os.clock()
        local frame, err = camera.capture(width, height)
        if frame then
            sequence = sequence + 1
            rednet.broadcast({
                station = station,
                sequence = sequence,
                width = frame.width,
                height = frame.height,
                format = frame.format,
                data = textutils.compress(frame.data),
            }, PROTOCOL)
            local _, y = term.getCursorPos()
            term.setCursorPos(1, y)
            term.clearLine()
            term.write(("Frame %d sent (%0.1fs)"):format(sequence, os.clock() - started))
        else
            print()
            printError("Capture failed: " .. tostring(err))
            sleep(1)
        end

        local elapsed = os.clock() - started
        if elapsed < interval then sleep(interval - elapsed) end
    end
end

local function watch(station, monitorName)
    openModem()

    local target = term.current()
    local graphics = false
    if monitorName then
        target = peripheral.wrap(monitorName)
        if not target then error(("No such monitor %q"):format(monitorName), 0) end
        -- Prefer full-fidelity graphics mode when the screen supports it.
        graphics = target.setGraphicsMode ~= nil
        if not graphics then target.setTextScale(0.5) end
    end

    local width, height
    if not graphics then
        width, height = target.getSize()
        target.setBackgroundColour(colours.black)
        target.clear()
    end
    if not monitorName then
        term.setCursorPos(1, 1)
        print(("Watching %q. Hold Ctrl+T to stop."):format(station))
    end

    local graphicsW, graphicsH
    while true do
        local _, message = rednet.receive(PROTOCOL)
        if type(message) == "table" and message.station == station
            and type(message.data) == "string" and type(message.width) == "number"
            and type(message.height) == "number" and type(message.format) == "string"
        then
            local data = textutils.decompress(message.data)
            if data then
                local frame = {
                    width = message.width,
                    height = message.height,
                    format = message.format,
                    data = data,
                }
                if graphics then
                    if graphicsW ~= frame.width or graphicsH ~= frame.height then
                        graphicsW, graphicsH = frame.width, frame.height
                        target.setGraphicsMode(graphicsW, graphicsH)
                    end
                    frames.present(frame, target)
                else
                    local ok, rendered = pcall(frames.render, frame, width, height)
                    if ok then frames.draw(rendered, target) end
                end
            end
        end
    end
end

local function list()
    openModem()
    print("Listening for streams for 5 seconds...")
    local stations, order = {}, {}
    local timer = os.startTimer(5)
    while true do
        local event, a, b, c = os.pullEvent()
        if event == "timer" and a == timer then break end
        if event == "rednet_message" and c == PROTOCOL and type(b) == "table" and type(b.station) == "string" then
            if not stations[b.station] then
                stations[b.station] = true
                order[#order + 1] = b.station
            end
        end
    end

    if #order == 0 then
        print("No streams found.")
    else
        print(("%d stream(s):"):format(#order))
        table.sort(order)
        for _, station in ipairs(order) do print("  " .. station) end
    end
end

local command, station = arg[1], arg[2]
if command == "serve" and station then
    serve(station, arg[3], arg[4], arg[5])
elseif command == "watch" and station then
    watch(station, arg[3])
elseif command == "list" then
    list()
else
    printUsage()
end
