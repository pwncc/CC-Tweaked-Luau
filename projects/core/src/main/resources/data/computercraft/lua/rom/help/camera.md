# camera
Cameras broadcast a live, fully rendered view of the world around them on a
numbered channel. Billboards tuned to that channel show the feed on their
screen - across any distance, and across dimensions.

The picture is streamed as world data and rendered on each viewer's own
client, so it stays sharp at any resolution (up to 720p) and only runs
while somebody is actually watching.

## Setup
1. Place a camera facing the scene, with a computer against it (or reach it
   over a wired modem).
2. On the camera's computer: `broadcast 7` (any channel 0-65535).
3. On a billboard: `tune 7`.

## Steering
The camera is fixed to its block, but the view can pan, tilt and zoom:

    local cam = peripheral.find("camera")
    cam.setRotation(45, -10) -- pan right, tilt up
    cam.setFov(40)           -- zoom in
    print(cam.getViewers())  -- who's watching?

`broadcast aim 45 -10` and `broadcast zoom 40` do the same from the shell.

A slowly rotating security camera is a classic:

    local cam = peripheral.find("camera")
    cam.setChannel(7)
    while true do
        for yaw = -60, 60 do cam.setRotation(yaw, 0) sleep(0.1) end
        for yaw = 60, -60, -1 do cam.setRotation(yaw, 0) sleep(0.1) end
    end

## Capturing frames
Beyond live billboard feeds, a broadcasting camera can hand its picture to
Lua as pixels:

    local frame = camera.capture(320, 180)

The frame is rendered by an online player's client and returns as a table
of RGB332 pixel data (up to 1280x720). The `cc.frames` module renders
frames onto terminals and monitors, scales them, and reads pixels; the
`cctv` program builds a whole streaming network on top of this - see
`help cctv`.

## Notes
- One camera per channel: the most recently placed wins.
- The feed shows blocks and fluids; entities are not yet streamed.
- `display.setChannel(n)` / `display.clearChannel()` are available on the
  billboard's own computer, so channel-hopping over rednet is just a
  `rednet.receive` loop away.
- Capturing needs at least one player online (their client draws the
  frame), and the camera must be broadcasting on a channel first.
