# cassette
Cassettes are big, cheap, and stubbornly linear: megabytes of storage that
you read and write wherever the tape head happens to be. Seeking means
physically winding the reels, which takes real time - plan your layout like
it's 1985.

Put a cassette in a cassette deck and wrap it as a peripheral:

    local deck = peripheral.find("cassette_deck")
    deck.write("dear diary. today a creeper ")
    deck.rewind()
    while not deck.isReady() do os.pullEvent("cassette_ready") end
    print(deck.read(28))

## Functions
* `isReady()` - a cassette is inserted and the reels are still.
* `read([count])` / `write(data)` - at the head, advancing it.
* `seek(offset)` - start winding by a relative offset. Returns the wind
  time in seconds; a `cassette_ready` event fires when done.
* `rewind()` - wind back to the start.
* `getPosition()`, `getSize()`, `getRemaining()` - where you are.
* `getLabel()` / `setLabel(name)` - the sticker on the shell.
* `eject()` - pop the tape out.

Reading or writing while winding throws "The tape is winding" - wait for
the `cassette_ready` event.

Cassettes survive being moved between decks; the tape keeps its contents
and its position is per-deck. They hold about 8MB by default (see the
`cassette_capacity` server config).
