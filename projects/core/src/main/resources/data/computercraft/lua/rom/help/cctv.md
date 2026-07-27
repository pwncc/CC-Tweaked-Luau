# cctv
Streams camera frames over modems, so any computer on the network can show
a live picture - on its terminal, or on a monitor.

Where billboards tune into a camera's channel directly (see `camera`), cctv
moves *frames as data*: the serving computer captures pixel frames with
`camera.capture`, compresses them, and broadcasts them over rednet. That
means the picture can hop wired and wireless networks, be recorded to disk
or cassette, filtered, or re-broadcast - it is just a string of pixels.

## Serving
On a computer with a camera and a modem:

    cctv serve gate

This captures 320x180 frames twice a second and broadcasts them as station
"gate". Tune the rate and size with `cctv serve gate 4 160 90`.

## Watching
On any computer with a modem:

    cctv watch gate

renders the stream on the terminal. Add a monitor name to use a wall
screen instead:

    cctv watch gate monitor_0

`cctv list` shows the stations currently broadcasting.

## Doing it yourself
The stream is plain rednet messages on the "cctv" protocol, and the frames
are ordinary frame tables, so a custom viewer is a few lines:

    local frames = require "cc.frames"
    while true do
        local _, msg = rednet.receive("cctv")
        local frame = {
            width = msg.width, height = msg.height,
            format = msg.format,
            data = textutils.decompress(msg.data),
        }
        frames.draw(frames.render(frame, term.getSize()))
    end

See `help camera` for capturing your own frames, and the `cc.frames` module
for scaling, reading pixels and drawing.
