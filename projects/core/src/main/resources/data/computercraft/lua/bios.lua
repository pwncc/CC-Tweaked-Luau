-- SPDX-FileCopyrightText: 2017 Daniel Ratcliffe
--
-- SPDX-License-Identifier: LicenseRef-CCPL

-- Compatibility shims for the Luau runtime. Luau's debug library only
-- provides debug.info/debug.traceback, so reconstruct the parts of
-- debug.getinfo that CraftOS uses. See also cc.internal.exception, which
-- degrades gracefully when debug.getlocal is unavailable.
if _VERSION == "Luau" and debug and not debug.getmetatable then
    -- Luau has no debug.getmetatable. Plain getmetatable is close enough for
    -- our purposes (it only differs for __metatable-protected tables).
    debug.getmetatable = getmetatable
end

if _VERSION == "Luau" and debug and not debug.getinfo and debug.info then
    local dinfo = debug.info

    local function build_info(short_src, line, name, func, ok)
        if not ok then return nil end

        -- debug.info returns the PUC-style "short source". Reconstruct the
        -- raw chunk name from it as best we can.
        local source = short_src
        if type(short_src) == "string" and short_src ~= "[C]" then
            local str_name = short_src:match('^%[string "(.*)"%]$')
            if str_name then
                source = str_name
            else
                source = "@" .. short_src
            end
        end

        return {
            source = source,
            short_src = short_src,
            currentline = line,
            linedefined = -1,
            name = name ~= "" and name or nil,
            what = short_src == "[C]" and "C" or "Lua",
            func = func,
        }
    end

    debug.getinfo = function(a, b, c)
        if type(a) == "thread" then
            local ok, source, line, name, func = pcall(dinfo, a, b, "slnf")
            if not ok or source == nil then return nil end
            return build_info(source, line, name, func, true)
        elseif type(a) == "function" then
            local source, line, name = dinfo(a, "sln")
            local nparams, is_vararg = dinfo(a, "a")
            local info = build_info(source, line, name, a, true)
            -- For a function argument, "l" is the line it was defined at.
            info.linedefined, info.currentline = line, -1
            info.nparams, info.isvararg = nparams, is_vararg
            return info
        else
            -- Add one to the level to skip this shim function. dinfo must be
            -- called directly (not via pcall), as extra frames would change
            -- the level the caller asked about.
            local source, line, name, func = dinfo(a + 1, "slnf")
            if source == nil then return nil end
            return build_info(source, line, name, func, true)
        end
    end
end

-- Load in expect from the module path.
--
-- Ideally we'd use require, but that is part of the shell, and so is not
-- available to the BIOS or any APIs. All APIs load this using dofile, but that
-- has not been defined at this point.
local expect

do
    local h = fs.open("rom/modules/main/cc/expect.lua", "r")
    local f, err = loadstring(h.readAll(), "@/rom/modules/main/cc/expect.lua")
    h.close()

    if not f then error(err) end
    expect = f().expect
end

-- On the Luau runtime, wrap file handles with a Lua-side buffer: reads pull
-- 64KB from Java at a time and lines are sliced out of the buffer, writes
-- accumulate until 64KB. Line-by-line IO thus no longer crosses the runtime
-- boundary on every call. The semantics (including the readLine \r\n rules
-- and error messages) match the Java handles exactly; unusual argument types
-- are delegated to the underlying handle so errors stay identical.
if _CC_NATIVE_WINDOW then
    local native_open = fs.open
    local CHUNK = 64 * 1024

    local function closedError()
        error("attempt to use a closed file", 0)
    end

    local function wrapRead(handle, binary)
        local buffer, pos, eof, closed = "", 1, false, false

        local function buffered() return #buffer - pos + 1 end

        local function fill()
            if eof then return false end
            local chunk = handle.read(CHUNK)
            if chunk == nil or #chunk == 0 then
                eof = true
                return false
            end
            if pos > 1 then
                buffer = buffer:sub(pos)
                pos = 1
            end
            buffer = buffer == "" and chunk or buffer .. chunk
            return true
        end

        local out = {}

        function out.readLine(withTrailing)
            if closed then closedError() end
            if withTrailing ~= nil and type(withTrailing) ~= "boolean" then
                error(("bad argument #1 (boolean expected, got %s)"):format(type(withTrailing)), 0)
            end
            while true do
                local nl = buffer:find("\n", pos, true)
                if nl then
                    local line
                    if withTrailing then
                        line = buffer:sub(pos, nl)
                    else
                        line = buffer:sub(pos, nl - 1)
                        if line:sub(-1) == "\r" then line = line:sub(1, -2) end
                    end
                    pos = nl + 1
                    return line
                end
                if not fill() then
                    -- End of file: return the remainder (unstripped), or nil.
                    if buffered() > 0 then
                        local line = buffer:sub(pos)
                        pos = #buffer + 1
                        return line
                    end
                    return nil
                end
            end
        end

        function out.readAll()
            if closed then closedError() end
            local before = buffer:sub(pos)
            buffer, pos, eof = "", 1, true
            local rest = handle.readAll()
            if rest == nil then
                if before == "" then return nil end
                return before
            end
            return before .. rest
        end

        function out.read(count)
            if closed then closedError() end
            if count == nil then
                if binary then
                    if buffered() == 0 and not fill() then return nil end
                    local value = buffer:byte(pos)
                    pos = pos + 1
                    return value
                end
                count = 1
            end
            if type(count) ~= "number" then
                error(("bad argument #1 (number expected, got %s)"):format(type(count)), 0)
            end
            count = count >= 0 and math.floor(count) or math.ceil(count)
            if count < 0 then error("Cannot read a negative number of bytes", 0) end
            if count == 0 then
                if buffered() > 0 then return "" end
                return handle.read(0)
            end
            while buffered() < count do
                if not fill() then break end
            end
            local available = buffered()
            if available == 0 then return nil end
            local n = count < available and count or available
            local result = buffer:sub(pos, pos + n - 1)
            pos = pos + n
            return result
        end

        function out.seek(whence, offset)
            if closed then closedError() end
            if whence ~= nil and type(whence) ~= "string" then
                error(("bad argument #1 (string expected, got %s)"):format(type(whence)), 0)
            end
            local result, err
            if whence == nil or whence == "cur" then
                -- The underlying position is ahead of the logical one by
                -- however much is still buffered.
                local base, baseErr = handle.seek("cur", 0)
                if not base then return base, baseErr end
                result, err = handle.seek("set", base - buffered() + (offset or 0))
            else
                result, err = handle.seek(whence, offset)
            end
            -- Only a successful seek invalidates the buffer.
            if result then buffer, pos, eof = "", 1, false end
            return result, err
        end

        function out.close()
            if closed then closedError() end
            handle.close()
            closed = true
            buffer, pos = "", 1
        end

        return out
    end

    local function wrapWrite(handle, binary)
        local parts, size, closed = {}, 0, false

        local function flushBuffer()
            if size == 0 then return end
            local data = table.concat(parts)
            parts, size = {}, 0
            handle.write(data)
        end

        local out = {}

        function out.write(value)
            if closed then closedError() end
            local kind = type(value)
            if kind == "string" then
                parts[#parts + 1] = value
                size = size + #value
            elseif kind == "number" then
                if binary then
                    local n = value >= 0 and math.floor(value) or math.ceil(value)
                    parts[#parts + 1] = string.char(n % 256)
                    size = size + 1
                else
                    local text = tostring(value)
                    parts[#parts + 1] = text
                    size = size + #text
                end
            else
                -- Let the underlying handle produce the exact error.
                flushBuffer()
                return handle.write(value)
            end
            if size >= CHUNK then flushBuffer() end
        end

        function out.writeLine(value)
            if closed then closedError() end
            local kind = type(value)
            if kind == "string" or kind == "number" then
                out.write(value)
                out.write("\n")
            else
                flushBuffer()
                return handle.writeLine(value)
            end
        end

        function out.flush()
            if closed then closedError() end
            flushBuffer()
            return handle.flush()
        end

        function out.seek(whence, offset)
            if closed then closedError() end
            flushBuffer()
            return handle.seek(whence, offset)
        end

        function out.close()
            if closed then closedError() end
            flushBuffer()
            handle.close()
            closed = true
        end

        return out
    end

    function fs.open(path, mode)
        local handle, err = native_open(path, mode)
        if not handle then return handle, err end
        if mode == "r" then return wrapRead(handle, false) end
        if mode == "rb" then return wrapRead(handle, true) end
        if mode == "w" or mode == "a" then return wrapWrite(handle, false) end
        if mode == "wb" or mode == "ab" then return wrapWrite(handle, true) end
        return handle
    end
end

-- Inject a stub for the old bit library
_G.bit = {
    bnot = bit32.bnot,
    band = bit32.band,
    bor = bit32.bor,
    bxor = bit32.bxor,
    brshift = bit32.arshift,
    blshift = bit32.lshift,
    blogic_rshift = bit32.rshift,
}

-- Install lua parts of the os api
function os.version()
    return "CraftOS 1.9"
end

function os.pullEventRaw(sFilter)
    return coroutine.yield(sFilter)
end

function os.pullEvent(sFilter)
    local eventData = table.pack(os.pullEventRaw(sFilter))
    if eventData[1] == "terminate" then
        error("Terminated", 0)
    end
    return table.unpack(eventData, 1, eventData.n)
end

-- Install globals
function sleep(nTime)
    expect(1, nTime, "number", "nil")
    local timer = os.startTimer(nTime or 0)
    repeat
        local _, param = os.pullEvent("timer")
    until param == timer
end

function write(sText)
    expect(1, sText, "string", "number")

    local w, h = term.getSize()
    local x, y = term.getCursorPos()

    local nLinesPrinted = 0
    local function newLine()
        if y + 1 <= h then
            term.setCursorPos(1, y + 1)
        else
            term.setCursorPos(1, h)
            term.scroll(1)
        end
        x, y = term.getCursorPos()
        nLinesPrinted = nLinesPrinted + 1
    end

    -- Print the line with proper word wrapping
    sText = tostring(sText)
    while #sText > 0 do
        local whitespace = string.match(sText, "^[ \t]+")
        if whitespace then
            -- Print whitespace
            term.write(whitespace)
            x, y = term.getCursorPos()
            sText = string.sub(sText, #whitespace + 1)
        end

        local newline = string.match(sText, "^\n")
        if newline then
            -- Print newlines
            newLine()
            sText = string.sub(sText, 2)
        end

        local text = string.match(sText, "^[^ \t\n]+")
        if text then
            sText = string.sub(sText, #text + 1)
            if #text > w then
                -- Print a multiline word
                while #text > 0 do
                    if x > w then
                        newLine()
                    end
                    term.write(text)
                    text = string.sub(text, w - x + 2)
                    x, y = term.getCursorPos()
                end
            else
                -- Print a word normally
                if x + #text - 1 > w then
                    newLine()
                end
                term.write(text)
                x, y = term.getCursorPos()
            end
        end
    end

    return nLinesPrinted
end

function print(...)
    local nLinesPrinted = 0
    local nLimit = select("#", ...)
    for n = 1, nLimit do
        local s = tostring(select(n, ...))
        if n < nLimit then
            s = s .. "\t"
        end
        nLinesPrinted = nLinesPrinted + write(s)
    end
    nLinesPrinted = nLinesPrinted + write("\n")
    return nLinesPrinted
end

function printError(...)
    local oldColour
    if term.isColour() then
        oldColour = term.getTextColour()
        term.setTextColour(colors.red)
    end
    print(...)
    if term.isColour() then
        term.setTextColour(oldColour)
    end
end

function read(_sReplaceChar, _tHistory, _fnComplete, _sDefault)
    expect(1, _sReplaceChar, "string", "nil")
    expect(2, _tHistory, "table", "nil")
    expect(3, _fnComplete, "function", "nil")
    expect(4, _sDefault, "string", "nil")

    term.setCursorBlink(true)

    local sLine
    if type(_sDefault) == "string" then
        sLine = _sDefault
    else
        sLine = ""
    end
    local nHistoryPos
    local nPos, nScroll = #sLine, 0
    if _sReplaceChar then
        _sReplaceChar = string.sub(_sReplaceChar, 1, 1)
    end

    local tCompletions
    local nCompletion
    local function recomplete()
        if _fnComplete and nPos == #sLine then
            tCompletions = _fnComplete(sLine)
            if tCompletions and #tCompletions > 0 then
                nCompletion = 1
            else
                nCompletion = nil
            end
        else
            tCompletions = nil
            nCompletion = nil
        end
    end

    local function uncomplete()
        tCompletions = nil
        nCompletion = nil
    end

    local w = term.getSize()
    local sx = term.getCursorPos()

    local function redraw(_bClear)
        local cursor_pos = nPos - nScroll
        if sx + cursor_pos >= w then
            -- We've moved beyond the RHS, ensure we're on the edge.
            nScroll = sx + nPos - w
        elseif cursor_pos < 0 then
            -- We've moved beyond the LHS, ensure we're on the edge.
            nScroll = nPos
        end

        local _, cy = term.getCursorPos()
        term.setCursorPos(sx, cy)
        local sReplace = _bClear and " " or _sReplaceChar
        if sReplace then
            term.write(string.rep(sReplace, math.max(#sLine - nScroll, 0)))
        else
            term.write(string.sub(sLine, nScroll + 1))
        end

        if nCompletion then
            local sCompletion = tCompletions[nCompletion]
            local oldText, oldBg
            if not _bClear then
                oldText = term.getTextColor()
                oldBg = term.getBackgroundColor()
                term.setTextColor(colors.white)
                term.setBackgroundColor(colors.gray)
            end
            if sReplace then
                term.write(string.rep(sReplace, #sCompletion))
            else
                term.write(sCompletion)
            end
            if not _bClear then
                term.setTextColor(oldText)
                term.setBackgroundColor(oldBg)
            end
        end

        term.setCursorPos(sx + nPos - nScroll, cy)
    end

    local function clear()
        redraw(true)
    end

    recomplete()
    redraw()

    local function acceptCompletion()
        if nCompletion then
            -- Clear
            clear()

            -- Find the common prefix of all the other suggestions which start with the same letter as the current one
            local sCompletion = tCompletions[nCompletion]
            sLine = sLine .. sCompletion
            nPos = #sLine

            -- Redraw
            recomplete()
            redraw()
        end
    end
    while true do
        local sEvent, param, param1, param2 = os.pullEvent()
        if sEvent == "char" then
            -- Typed key
            clear()
            sLine = string.sub(sLine, 1, nPos) .. param .. string.sub(sLine, nPos + 1)
            nPos = nPos + 1
            recomplete()
            redraw()

        elseif sEvent == "paste" then
            -- Pasted text
            clear()
            sLine = string.sub(sLine, 1, nPos) .. param .. string.sub(sLine, nPos + 1)
            nPos = nPos + #param
            recomplete()
            redraw()

        elseif sEvent == "key" then
            if param == keys.enter or param == keys.numPadEnter then
                -- Enter/Numpad Enter
                if nCompletion then
                    clear()
                    uncomplete()
                    redraw()
                end
                break

            elseif param == keys.left then
                -- Left
                if nPos > 0 then
                    clear()
                    nPos = nPos - 1
                    recomplete()
                    redraw()
                end

            elseif param == keys.right then
                -- Right
                if nPos < #sLine then
                    -- Move right
                    clear()
                    nPos = nPos + 1
                    recomplete()
                    redraw()
                else
                    -- Accept autocomplete
                    acceptCompletion()
                end

            elseif param == keys.up or param == keys.down then
                -- Up or down
                if nCompletion then
                    -- Cycle completions
                    clear()
                    if param == keys.up then
                        nCompletion = nCompletion - 1
                        if nCompletion < 1 then
                            nCompletion = #tCompletions
                        end
                    elseif param == keys.down then
                        nCompletion = nCompletion + 1
                        if nCompletion > #tCompletions then
                            nCompletion = 1
                        end
                    end
                    redraw()

                elseif _tHistory then
                    -- Cycle history
                    clear()
                    if param == keys.up then
                        -- Up
                        if nHistoryPos == nil then
                            if #_tHistory > 0 then
                                nHistoryPos = #_tHistory
                            end
                        elseif nHistoryPos > 1 then
                            nHistoryPos = nHistoryPos - 1
                        end
                    else
                        -- Down
                        if nHistoryPos == #_tHistory then
                            nHistoryPos = nil
                        elseif nHistoryPos ~= nil then
                            nHistoryPos = nHistoryPos + 1
                        end
                    end
                    if nHistoryPos then
                        sLine = _tHistory[nHistoryPos]
                        nPos, nScroll = #sLine, 0
                    else
                        sLine = ""
                        nPos, nScroll = 0, 0
                    end
                    uncomplete()
                    redraw()

                end

            elseif param == keys.backspace then
                -- Backspace
                if nPos > 0 then
                    clear()
                    sLine = string.sub(sLine, 1, nPos - 1) .. string.sub(sLine, nPos + 1)
                    nPos = nPos - 1
                    if nScroll > 0 then nScroll = nScroll - 1 end
                    recomplete()
                    redraw()
                end

            elseif param == keys.home then
                -- Home
                if nPos > 0 then
                    clear()
                    nPos = 0
                    recomplete()
                    redraw()
                end

            elseif param == keys.delete then
                -- Delete
                if nPos < #sLine then
                    clear()
                    sLine = string.sub(sLine, 1, nPos) .. string.sub(sLine, nPos + 2)
                    recomplete()
                    redraw()
                end

            elseif param == keys["end"] then
                -- End
                if nPos < #sLine then
                    clear()
                    nPos = #sLine
                    recomplete()
                    redraw()
                end

            elseif param == keys.tab then
                -- Tab (accept autocomplete)
                acceptCompletion()

            end

        elseif sEvent == "mouse_click" or sEvent == "mouse_drag" and param == 1 then
            local _, cy = term.getCursorPos()
            if param1 >= sx and param1 <= w and param2 == cy then
                -- Ensure we don't scroll beyond the current line
                nPos = math.min(math.max(nScroll + param1 - sx, 0), #sLine)
                redraw()
            end

        elseif sEvent == "term_resize" then
            -- Terminal resized
            w = term.getSize()
            redraw()

        end
    end

    local _, cy = term.getCursorPos()
    term.setCursorBlink(false)
    term.setCursorPos(w + 1, cy)
    print()

    return sLine
end

function loadfile(filename, mode, env)
    -- Support the previous `loadfile(filename, env)` form instead.
    if type(mode) == "table" and env == nil then
        mode, env = nil, mode
    end

    expect(1, filename, "string")
    expect(2, mode, "string", "nil")
    expect(3, env, "table", "nil")

    local file = fs.open(filename, "r")
    if not file then return nil, "File not found" end

    local func, err = load(file.readAll(), "@/" .. fs.combine(filename), mode, env)
    file.close()
    return func, err
end

function dofile(_sFile)
    expect(1, _sFile, "string")

    local fnFile, e = loadfile(_sFile, nil, _G)
    if fnFile then
        return fnFile()
    else
        error(e, 2)
    end
end

-- Install the rest of the OS api
function os.run(_tEnv, _sPath, ...)
    expect(1, _tEnv, "table")
    expect(2, _sPath, "string")

    local tEnv = _tEnv
    setmetatable(tEnv, { __index = _G })

    if settings.get("bios.strict_globals", false) then
        -- load will attempt to set _ENV on this environment, which
        -- throws an error with this protection enabled. Thus we set it here first.
        tEnv._ENV = tEnv
        getmetatable(tEnv).__newindex = function(_, name)
          error("Attempt to create global " .. tostring(name), 2)
        end
    end

    local fnFile, err = loadfile(_sPath, nil, tEnv)
    if fnFile then
        local ok, err = pcall(fnFile, ...)
        if not ok then
            if err and err ~= "" then
                printError(err)
            end
            return false
        end
        return true
    end
    if err and err ~= "" then
        printError(err)
    end
    return false
end

local tAPIsLoading = {}
function os.loadAPI(_sPath)
    expect(1, _sPath, "string")
    local sName = fs.getName(_sPath)
    if sName:sub(-4) == ".lua" then
        sName = sName:sub(1, -5)
    end
    if tAPIsLoading[sName] == true then
        printError("API " .. sName .. " is already being loaded")
        return false
    end
    tAPIsLoading[sName] = true

    local tEnv = {}
    setmetatable(tEnv, { __index = _G })
    local fnAPI, err = loadfile(_sPath, nil, tEnv)
    if fnAPI then
        local ok, err = pcall(fnAPI)
        if not ok then
            tAPIsLoading[sName] = nil
            return error("Failed to load API " .. sName .. " due to " .. err, 1)
        end
    else
        tAPIsLoading[sName] = nil
        return error("Failed to load API " .. sName .. " due to " .. err, 1)
    end

    local tAPI = {}
    for k, v in pairs(tEnv) do
        if k ~= "_ENV" then
            tAPI[k] =  v
        end
    end

    _G[sName] = tAPI
    tAPIsLoading[sName] = nil
    return true
end

function os.unloadAPI(_sName)
    expect(1, _sName, "string")
    if _sName ~= "_G" and type(_G[_sName]) == "table" then
        _G[_sName] = nil
    end
end

function os.sleep(nTime)
    sleep(nTime)
end

local nativeShutdown = os.shutdown
function os.shutdown()
    nativeShutdown()
    while true do
        coroutine.yield()
    end
end

local nativeReboot = os.reboot
function os.reboot()
    nativeReboot()
    while true do
        coroutine.yield()
    end
end

local bAPIError = false

local function load_apis(dir)
    if not fs.isDir(dir) then return end

    for _, file in ipairs(fs.list(dir)) do
        if file:sub(1, 1) ~= "." then
            local path = fs.combine(dir, file)
            if not fs.isDir(path) then
                if not os.loadAPI(path) then
                    bAPIError = true
                end
            end
        end
    end
end

-- Load APIs
load_apis("rom/apis")
if http then load_apis("rom/apis/http") end
if turtle then load_apis("rom/apis/turtle") end
if pocket then load_apis("rom/apis/pocket") end

if commands and fs.isDir("rom/apis/command") then
    -- Load command APIs
    if os.loadAPI("rom/apis/command/commands.lua") then
        -- Add a special case-insensitive metatable to the commands api
        local tCaseInsensitiveMetatable = {
            __index = function(table, key)
                local value = rawget(table, key)
                if value ~= nil then
                    return value
                end
                if type(key) == "string" then
                    local value = rawget(table, string.lower(key))
                    if value ~= nil then
                        return value
                    end
                end
                return nil
            end,
        }
        setmetatable(commands, tCaseInsensitiveMetatable)
        setmetatable(commands.async, tCaseInsensitiveMetatable)

        -- Add global "exec" function
        exec = commands.exec
    else
        bAPIError = true
    end
end

if bAPIError then
    print("Press any key to continue")
    os.pullEvent("key")
    term.clear()
    term.setCursorPos(1, 1)
end

-- Set default settings
settings.define("shell.allow_startup", {
    default = true,
    description = "Run startup files when the computer turns on.",
    type = "boolean",
})
settings.define("shell.allow_disk_startup", {
    default = commands == nil,
    description = "Run startup files from disk drives when the computer turns on.",
    type = "boolean",
})

settings.define("shell.autocomplete", {
    default = true,
    description = "Autocomplete program and arguments in the shell.",
    type = "boolean",
})
settings.define("edit.autocomplete", {
    default = true,
    description = "Autocomplete API and function names in the editor.",
        type = "boolean",
})
settings.define("lua.autocomplete", {
    default = true,
    description = "Autocomplete API and function names in the Lua REPL.",
        type = "boolean",
})

settings.define("edit.default_extension", {
    default = "lua",
    description = [[The file extension the editor will use if none is given. Set to "" to disable.]],
    type = "string",
})
settings.define("paint.default_extension", {
    default = "nfp",
    description = [[The file extension the paint program will use if none is given. Set to "" to disable.]],
    type = "string",
})

settings.define("list.show_hidden", {
    default = false,
    description = [[Whether the list program show  hidden files (those starting with ".").]],
    type = "boolean",
})

settings.define("motd.enable", {
    default = pocket == nil,
    description = "Display a random message when the computer starts up.",
    type = "boolean",
})
settings.define("motd.path", {
    default = "/rom/motd.txt:/motd.txt",
    description = [[The path to load random messages from. Should be a colon (":") separated string of file paths.]],
    type = "string",
})

settings.define("lua.warn_against_use_of_local", {
    default = true,
    description = [[Print a message when input in the Lua REPL starts with the word 'local'. Local variables defined in the Lua REPL are be inaccessible on the next input.]],
    type = "boolean",
})
settings.define("lua.function_args", {
    default = true,
    description = "Show function arguments when printing functions.",
    type = "boolean",
})
settings.define("lua.function_source", {
    default = false,
    description = "Show where a function was defined when printing functions.",
    type = "boolean",
})
settings.define("bios.strict_globals", {
    default = false,
    description = "Prevents assigning variables into a program's environment. Make sure you use the local keyword or assign to _G explicitly.",
    type = "boolean",
})
settings.define("shell.autocomplete_hidden", {
    default = false,
    description = [[Autocomplete hidden files and folders (those starting with ".").]],
    type = "boolean",
})

if term.isColour() then
    settings.define("bios.use_multishell", {
        default = true,
        description = [[Allow running multiple programs at once, through the use of the "fg" and "bg" programs.]],
        type = "boolean",
    })
end
if _CC_DEFAULT_SETTINGS then
    for sPair in string.gmatch(_CC_DEFAULT_SETTINGS, "[^,]+") do
        local sName, sValue = string.match(sPair, "([^=]*)=(.*)")
        if sName and sValue then
            local value
            if sValue == "true" then
                value = true
            elseif sValue == "false" then
                value = false
            elseif sValue == "nil" then
                value = nil
            elseif tonumber(sValue) then
                value = tonumber(sValue)
            else
                value = sValue
            end
            if value ~= nil then
                settings.set(sName, value)
            else
                settings.unset(sName)
            end
        end
    end
end

-- Load user settings
if fs.exists(".settings") then
    settings.load(".settings")
end

-- Run the shell
local ok, err = pcall(parallel.waitForAny,
    function()
        local sShell
        if term.isColour() and settings.get("bios.use_multishell") then
            sShell = "rom/programs/advanced/multishell.lua"
        else
            sShell = "rom/programs/shell.lua"
        end
        os.run({}, sShell)
        os.run({}, "rom/programs/shutdown.lua")
    end,
    rednet.run
)

-- If the shell errored, let the user read it.
term.redirect(term.native())
if not ok then
    printError(err)
    pcall(function()
        term.setCursorBlink(false)
        print("Press any key to continue")
        os.pullEvent("key")
    end)
end

-- End
os.shutdown()
