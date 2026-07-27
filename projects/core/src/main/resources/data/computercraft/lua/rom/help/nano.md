# nano computer
The nano computer is a tiny, headless embedded computer: no screen, no
keyboard, no GUI. It boots straight from a ROM chip and talks to the world
through redstone and whatever modules are hidden inside it. Perfect for
door controllers, sensor nodes, relays and other set-and-forget machines.

## Programming one
1. Craft a ROM chip and put it in a disk drive next to a normal computer.
2. Write your program: `edit disk/startup.lua`.
3. Optionally name it: `label set right My Program` (drive side may vary).
4. Pop the chip out and right-click it into a nano computer.

The nano reboots and runs the chip. Inside the nano the chip is mounted
read-only at `/chip` - true ROM - so a misbehaving program can't corrupt
itself. The nano still has its own small writable root for state files.
Chips hold a limited amount (a config option), so keep it lean.

## Internal modules
Right-click the nano with any pocket-computer upgrade item - a wireless
modem, ender modem, speaker, or upgrades from other mods - and it is
fitted invisibly inside, up to three modules. They attach as peripherals
on the "back", "top" and "bottom" sides.

    -- On the chip:
    peripheral.find("modem", rednet.open)
    while true do
        local _, msg = rednet.receive("doors")
        redstone.setOutput("front", msg == "open")
        sleep(0)
    end

## Handling
- Right-click (empty hand): power up / report status.
- Sneak-click (empty hand): pop out the chip, then modules; when empty,
  shut the computer down.
- Breaking the block drops the chip and modules.
- From Lua, the `chip` API offers `chip.present()`, `chip.getLabel()` and
  `chip.eject()`.

External peripherals still connect on the front, left and right; the other
sides belong to the module bays.
