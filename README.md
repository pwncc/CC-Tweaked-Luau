<!--
SPDX-FileCopyrightText: 2017 The CC: Tweaked Developers

SPDX-License-Identifier: MPL-2.0
-->

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./doc/logo-darkmode.png">
  <source media="(prefers-color-scheme: light)" srcset="./doc/logo.png">
  <img alt="CC: Tweaked" src="./doc/logo.png">
</picture>

[![Current build status](https://github.com/cc-tweaked/CC-Tweaked/workflows/Build/badge.svg)](https://github.com/cc-tweaked/CC-Tweaked/actions "Current build status")
[![Download CC: Tweaked on Modrinth](https://img.shields.io/static/v1?label=Download&color=00AF5C&logoColor=00AF5C&logo=Modrinth&message=CC:%20Tweaked)][Modrinth]

CC: Tweaked is a mod for Minecraft which adds programmable computers, turtles and more to the game. A fork of the
much-beloved [ComputerCraft], it continues its legacy with improved performance and stability, along with a wealth of
new features.

CC: Tweaked can be installed from [Modrinth]. It runs on both [Minecraft Forge] and [Fabric].

## Contributing
Any contribution is welcome, be that using the mod, reporting bugs or contributing code. If you want to get started
developing the mod, [check out the instructions here](CONTRIBUTING.md#developing).

## Community
If you need help getting started with CC: Tweaked, want to show off your latest project, or just want to chat about
ComputerCraft, do check out our [GitHub discussions page][GitHub discussions]! There's also a fairly populated,
albeit quiet IRC channel on [EsperNet], if that's more your cup of tea. You can join `#computercraft` through your
desktop client, or online using [KiwiIRC].

We also host fairly comprehensive documentation at [tweaked.cc](https://tweaked.cc/ "The CC: Tweaked website").

## Using
CC: Tweaked is hosted on my maven repo, and so is relatively simple to depend on. You may wish to add a soft (or hard)
dependency in your `mods.toml` file, with the appropriate version bounds, to ensure that API functionality you depend
on is present.

```groovy
repositories {
  maven {
    url "https://maven.squiddev.cc"
    content {
      includeGroup("cc.tweaked")
    }
  }
}

dependencies {
  // Vanilla (i.e. for multi-loader systems)
  compileOnly("cc.tweaked:cc-tweaked-$mcVersion-common-api:$cctVersion")

  // Forge Gradle
  compileOnly("cc.tweaked:cc-tweaked-$mcVersion-forge-api:$cctVersion")
  runtimeOnly("cc.tweaked:cc-tweaked-$mcVersion-forge:$cctVersion")

  // Fabric Loom
  modCompileOnly("cc.tweaked:cc-tweaked-$mcVersion-fabric-api:$cctVersion")
  modRuntimeOnly("cc.tweaked:cc-tweaked-$mcVersion-fabric:$cctVersion")
}
```

You should also be careful to only use classes within the `dan200.computercraft.api` package. Non-API classes are
subject to change at any point. If you depend on functionality outside the API (or need to mixin to CC:T), please file
an issue to let me know!

We bundle the API sources with the jar, so documentation should be easily viewable within your editor. Alternatively,
the generated documentation [can be browsed online](https://tweaked.cc/javadoc/).

[computercraft]: https://github.com/dan200/ComputerCraft "ComputerCraft on GitHub"
[modrinth]: https://modrinth.com/mod/gu7yAYhd "Download CC: Tweaked from Modrinth"
[Minecraft Forge]: https://files.minecraftforge.net/ "Download Minecraft Forge."
[Fabric]: https://fabricmc.net/use/installer/ "Download Fabric."
[GitHub Discussions]: https://github.com/cc-tweaked/CC-Tweaked/discussions
[EsperNet]: https://www.esper.net/
[KiwiIRC]: https://kiwiirc.com/nextclient/#irc://irc.esper.net:+6697/#computercraft "#computercraft on EsperNet"

## Performance improvements
Computers now run on the native [Luau](https://luau.org/) VM (with its native code generator enabled) instead of the Cobalt runtime. Benchmark of identical workloads on both runtimes (lower is better):

| Benchmark                             | Luau (new) | Cobalt (old) | Change      |
|---------------------------------------|-----------:|-------------:|-------------|
| Numeric loop (50M iterations)         |     324 ms |      3795 ms | 12x faster  |
| Coroutine switching (500k resumes)    |      52 ms |      1036 ms | 20x faster  |
| Function calls (fib 30)               |      60 ms |       463 ms | 7.7x faster |
| Table (array) reads/writes            |      49 ms |       258 ms | 5.3x faster |
| Table (hash) writes                   |      93 ms |       494 ms | 5.3x faster |
| String format/upper/gsub              |      57 ms |       172 ms | 3.0x faster |
| Pattern matching (gmatch)             |    1371 ms |      2351 ms | 1.7x faster |
| String concatenation                  |      66 ms |        82 ms | 1.2x faster |
| Terminal redraw (100k term calls)     |      14 ms |        46 ms | 3.3x faster |
| Java API calls (200k calls)           |      14 ms |       162 ms | 12x faster  |

On top of the faster VM, the hottest subsystems are implemented natively inside the runtime. Measured on the same Luau VM, reference Lua implementation vs native (lower is better):

| Subsystem                                        | Lua impl | Native | Change      |
|--------------------------------------------------|---------:|-------:|-------------|
| Window compositing (nested windows, 20k writes)  |   116 ms |  12 ms | 9.7x faster |
| Pixel text rendering (MineOS UI font rasteriser) |  1858 ms |  29 ms | 64x faster  |
| File IO (20k-line file written + read 5x)        |   219 ms | 107 ms | 2.0x faster |

The `term`, `redstone`, `window` and `os` time APIs run natively inside the VM, file handles are buffered, and computer screens now sync to viewers at up to 60Hz rather than once per server tick.
