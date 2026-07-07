---
module: [kind=reference] feature_compat
---

<!--
SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers

SPDX-License-Identifier: MPL-2.0
-->

# Lua 5.2/5.3 features in CC: Tweaked
CC: Tweaked runs on the [Luau](https://luau.org/) runtime when its native library is available for your platform
(Windows, Linux and macOS on desktop and servers), falling back to the Cobalt Lua runtime (Lua 5.2) elsewhere, such as
the web-based emulator.

## The Luau runtime
When running on Luau, computers use the real Luau virtual machine and compiler. This brings significant performance
improvements and the full set of [Luau language features](https://luau.org/syntax), including:

 - `continue` in loops.
 - Compound assignment (`+=`, `..=`, etc.).
 - String interpolation (`` `x = {x}` ``).
 - `if ... then ... else ...` expressions.
 - Type annotation syntax (type checking is not enforced at runtime).
 - The `buffer` and `vector` libraries, `table.freeze`/`table.clone`, and other Luau standard library extensions.

There are some differences to be aware of compared to the Cobalt runtime:

 - `goto`/labels and `string.dump` are not supported (Luau does not implement them).
 - Coroutines cannot yield across C boundaries such as `string.gsub` callbacks, `table.sort` comparators or
   metamethods. (`pcall`/`xpcall` and `load` remain yieldable.)
 - The `debug` library is limited to `debug.traceback` and `debug.info`, with a compatibility shim for common
   `debug.getinfo` usage. `debug.getlocal`, `debug.getupvalue`, `debug.sethook` and `debug.getregistry` are
   unavailable.
 - Binary chunks are rejected by `load`/`loadstring`, as Luau bytecode is unsafe to load from untrusted sources.
 - Tail calls are not eliminated, so `error` levels and stack traces may differ slightly.

The tables below describe the Cobalt runtime.

Cobalt and CC:T implement additional
features from Lua 5.2 and 5.3 (as well as some deprecated 5.0 and 5.1 features). This page lists all of the
compatibility for these newer versions.

## Lua 5.2
| Feature                                                       | Supported? | Notes                                                             |
|---------------------------------------------------------------|------------|-------------------------------------------------------------------|
| `goto`/labels                                                 | ✔          |                                                                   |
| `_ENV`                                                        | ✔          |                                                                   |
| `\z` escape                                                   | ✔          |                                                                   |
| `\xNN` escape                                                 | ✔          |                                                                   |
| Hex literal fractional/exponent parts                         | ✔          |                                                                   |
| Empty statements                                              | ✔          |                                                                   |
| `__len` metamethod                                            | ✔          |                                                                   |
| `__ipairs` metamethod                                         | ❌         | Deprecated in Lua 5.3. `ipairs` uses `__len`/`__index` instead.   |
| `__pairs` metamethod                                          | ✔          |                                                                   |
| `bit32` library                                               | ✔          |                                                                   |
| `collectgarbage` isrunning, generational, incremental options | ❌         | `collectgarbage` does not exist in CC:T.                          |
| New `load` syntax                                             | ✔          |                                                                   |
| `loadfile` mode parameter                                     | ✔          | Supports both 5.1 and 5.2+ syntax.                                |
| Removed `loadstring`                                          | ❌         |                                                                   |
| Removed `getfenv`, `setfenv`                                  | 🔶         | Only supports closures with an `_ENV` upvalue.                    |
| `rawlen` function                                             | ✔          |                                                                   |
| Negative index to `select`                                    | ✔          |                                                                   |
| Removed `unpack`                                              | ❌         |                                                                   |
| Arguments to `xpcall`                                         | ✔          |                                                                   |
| Second return value from `coroutine.running`                  | ✔          |                                                                   |
| Removed `module`                                              | ✔          |                                                                   |
| `package.loaders` -> `package.searchers`                      | ❌         |                                                                   |
| Second argument to loader functions                           | ✔          |                                                                   |
| `package.config`                                              | ✔          |                                                                   |
| `package.searchpath`                                          | ✔          |                                                                   |
| Removed `package.seeall`                                      | ✔          |                                                                   |
| `string.dump` on functions with upvalues (blanks them out)    | ❌         | `string.dump` is not supported                                    |
| `string.rep` separator                                        | ✔          |                                                                   |
| `%g` match group                                              | ❌         |                                                                   |
| Removal of `%z` match group                                   | ❌         |                                                                   |
| Removed `table.maxn`                                          | ❌         |                                                                   |
| `table.pack`/`table.unpack`                                   | ✔          |                                                                   |
| `math.log` base argument                                      | ✔          |                                                                   |
| Removed `math.log10`                                          | ❌         |                                                                   |
| `*L` mode to `file:read`                                      | ✔          |                                                                   |
| `os.execute` exit type + return value                         | ❌         | `os.execute` does not exist in CC:T.                              |
| `os.exit` close argument                                      | ❌         | `os.exit` does not exist in CC:T.                                 |
| `istailcall` field in `debug.getinfo`                         | ❌         |                                                                   |
| `nparams` field in `debug.getinfo`                            | ✔          |                                                                   |
| `isvararg` field in `debug.getinfo`                           | ✔          |                                                                   |
| `debug.getlocal` negative indices for varargs                 | ❌         |                                                                   |
| `debug.getuservalue`/`debug.setuservalue`                     | ❌         | Userdata are rarely used in CC:T, so this is not necessary.       |
| `debug.upvalueid`                                             | ✔          |                                                                   |
| `debug.upvaluejoin`                                           | ✔          |                                                                   |
| Tail call hooks                                               | ❌         |                                                                   |
| `=` prefix for chunks                                         | ✔          |                                                                   |
| Yield across C boundary                                       | ✔          |                                                                   |
| Removal of ambiguity error                                    | ✔          |                                                                   |
| Identifiers may no longer use locale-dependent letters        | ✔          |                                                                   |
| Ephemeron tables                                              | ❌         |                                                                   |
| Identical functions may be reused                             | ❌         | Removed in Lua 5.4                                                |
| Generational garbage collector                                | ❌         | Cobalt uses the built-in Java garbage collector.                  |

## Lua 5.3
| Feature                                                                               | Supported? | Notes                     |
|---------------------------------------------------------------------------------------|------------|---------------------------|
| Integer subtype                                                                       | ❌         |                           |
| Bitwise operators/floor division                                                      | ❌         |                           |
| `\u{XXX}` escape sequence                                                             | ✔          |                           |
| `utf8` library                                                                        | ✔          |                           |
| removed `__ipairs` metamethod                                                         | ✔          |                           |
| `coroutine.isyieldable`                                                               | ✔          |                           |
| `string.dump` strip argument                                                          | ✔          |                           |
| `string.pack`/`string.unpack`/`string.packsize`                                       | ✔          |                           |
| `table.move`                                                                          | ✔          |                           |
| `math.atan2` -> `math.atan`                                                           | 🔶         | `math.atan` supports its two argument form. |
| Removed `math.frexp`, `math.ldexp`, `math.pow`, `math.cosh`, `math.sinh`, `math.tanh` | ❌         |                           |
| `math.maxinteger`/`math.mininteger`                                                   | ❌         |                           |
| `math.tointeger`                                                                      | ❌         |                           |
| `math.type`                                                                           | ❌         |                           |
| `math.ult`                                                                            | ❌         |                           |
| Removed `bit32` library                                                               | ❌         |                           |
| Remove `*` from `file:read` modes                                                     | ✔          |                           |
| Metamethods respected in `table.*`, `ipairs`                                          | ✔          |                           |

## Lua 5.5
| Feature                            | Supported? | Notes |
|------------------------------------|------------|-------|
| `table.create`                     | ✔          |       |
| `utf8.offset` returns end position | ✔          |       |

## Lua 5.0
| Feature                          | Supported? | Notes                                            |
|----------------------------------|------------|--------------------------------------------------|
| `arg` table                      | 🔶         | Only set in the shell - not used in functions.   |
| `string.gfind`                   | ✔          | Equal to `string.gmatch`.                        |
| `table.getn`                     | ✔          | Equal to `#tbl`.                                 |
| `table.setn`                     | ❌         |                                                  |
| `math.mod`                       | ✔          | Equal to `math.fmod`.                            |
| `table.foreach`/`table.foreachi` | ✔          |                                                  |
| `gcinfo`                         | ❌         | Cobalt uses the built-in Java garbage collector. |
