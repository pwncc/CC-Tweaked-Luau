# tracker
The tracker plays pattern-based chiptunes through a speaker, in the spirit
of classic module trackers.

Run `tracker demo` to hear the built-in tune, or `tracker <file>` to play a
song of your own.

## Song files
A song is a serialised Lua table (usually saved with `textutils.serialise`)
with these fields:

* `name`: The song's title.
* `tempo`: Game ticks per row (1-20). 4 means five rows a second.
* `loop`: Whether to repeat forever.
* `instruments`: A list of speaker instruments, e.g. `{ "harp", "bass", "hat" }`.
* `patterns`: A list of patterns; each pattern is a list of row strings.
* `order`: The sequence of pattern indices to play.

Each row is a `|`-separated list of channels. A channel cell is either `---`
(rest) or `NOTE INSTRUMENT [VOLUME]`:

    C1 1     play C1 on instrument 1
    F#1 2 9  play F#1 on instrument 2 at full volume
    ---      rest

Notes range from `F#0` (lowest) to `F#2` (highest), with the octave number
stepping up at each C. Volume is a digit from 0 to 9.

While playing: space pauses, `+`/`-` change the tempo, and `q` quits.

The engine is also available to your own programs as the `cc.tracker`
module - see its documentation for details.
