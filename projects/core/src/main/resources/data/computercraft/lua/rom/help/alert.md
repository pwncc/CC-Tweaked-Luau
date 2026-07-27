# alert
The alert API lets a computer notify nearby players directly: messages
appear on the action bar (just above the hotbar), with an optional audible
ping from the computer.

It is a doorbell, not a broadcast tower: messages reach players within a
few dozen blocks at most, and alerts are limited to four per second.

## Functions
* `alert.broadcast(message [, range])` - show `message` to every player
  within `range` blocks (default 16, at most 64). If the computer has a
  label it is shown before the message. Returns how many players saw it.
* `alert.ping([pitch])` - play a short "pling" from the computer, with an
  optional pitch between 0.5 and 2.

## Example
    alert.broadcast("Smelting done - come and get it!", 24)
    alert.ping()
