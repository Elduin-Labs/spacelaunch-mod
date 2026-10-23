# Space Launch

<img src="src/main/resources/assets/spacelaunch/icon.png" width="128" align="right">

Adds `/spaceLaunch`. You go up. You float. Then gravity remembers you.

## What happens

1. **Liftoff** — an explosion sound, and you rise 3 blocks per tick on a column of smoke.
2. **Space** — at 320 blocks above your launch point you hang weightless for 6 seconds.
3. **Re-entry** — gravity comes back and you fall.
4. **Impact** — you die, with the death message *"<player> failed to fly."*

Vanilla fall damage is suppressed during the launch so the custom death message isn't stolen
by an ordinary fall kill.

> **This kills you.** That means dropping your inventory unless the world has keepInventory on.
> It is a joke command, not a transport system.

## Commands

```
/spaceLaunch              launch yourself
/spaceLaunch <targets>    launch other players    (permission level 2)
/spacelaunch              alias, same command
```

Minecraft command literals are case-sensitive, so the all-lowercase spelling is registered as a
redirect to the camelCase one — both work identically.

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.19.0+
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 21

## Installation

Drop `spacelaunch-1.1.0.jar` into your `mods` folder alongside Fabric API. Server-side — it works
for vanilla clients connecting to a server that has it.

## Building

```bash
./gradlew build
```

## License

MIT — see [LICENSE](LICENSE).
