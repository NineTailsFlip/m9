# M9 — Messages on 9 keys

SMS conversations for Android phones with a keypad and no touchscreen.

[![Build](https://github.com/NineTailsFlip/m9/actions/workflows/build.yml/badge.svg)](https://github.com/NineTailsFlip/m9/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/NineTailsFlip/m9)](https://github.com/NineTailsFlip/m9/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)

M9 is [NineTailsFlip](https://github.com/NineTailsFlip)'s fork of M9 from Timo Furrer's
[Nine suite](https://nine.furrer.life). It keeps upstream's look — one column of plain text on black, a numbered `*` menu, a hint bar — and adds updates from GitHub and the look chosen in the phone's first-time setup.

## Features

- Conversations, newest first, filtered live as you type a name, number or text.
- Unread only with `#`; mark read or unread; drafts are kept per conversation.
- A conversation by day, with message details; forward, copy, delete, resend.
- `#` copies a one-time code from a message.
- New messages to a contact or a typed number.
- Works as the phone's default SMS app.
- Ours: updates from GitHub Releases, the look chosen in the phone's first-time setup.

## Installation

Download the APK from [Releases](https://github.com/NineTailsFlip/m9/releases/latest) and
install it and make it the default SMS app. After that M9 checks for a new release once a day and offers it; Android asks
you to confirm each update. Settings → Updates turns the check off or runs it now.

Our builds are `io.github.ninetailsflip.m9`, so they install next to the official M9
(`life.furrer.m9`) instead of replacing it.

## Usage

| Where | Key | Does |
|---|---|---|
| List | Any key | Type to filter |
| List | OK | Open the conversation |
| List | `#` | Unread only |
| List | Call | Call the conversation's number |
| Conversation | Up / Down | Move; Down past the last message goes to the reply |
| Conversation | Left / Right | Previous / next day |
| Conversation | OK | Message details; in the reply, send |
| Conversation | `#` | Copy the message's one-time code |
| New message | Up / Down | Recipient ↔ message |
| Anywhere | `*` / hold `*` | Menu / Settings |

The full key map is in Settings → About → Key mapping.

## Building

Requirements: JDK 17+, Android SDK (platform 34).

```bash
git clone --recursive https://github.com/NineTailsFlip/m9.git
cd m9
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:assembleDebug
```

Tests and lint: `./gradlew :app:testDebugUnitTest :app:lintDebug`.
`scripts/run.sh` installs the app on a connected phone and follows its log (it expects
`ANDROID_HOME=/opt/android-sdk`; edit it if your SDK lives elsewhere).

Releases are built by GitHub Actions from a `v<versionName>` tag — see
[RELEASING.md](https://github.com/NineTailsFlip/.github/blob/main/RELEASING.md).

## Project structure

| Path | What's there |
|---|---|
| `app/` | M9 itself (Kotlin package `life.furrer.m9`, kept from upstream so its changes merge cleanly) |
| `nine-core/` | Git submodule: [nine-core](https://github.com/NineTailsFlip/nine-core) — the Nine suite's shared `core/` and `contacts/`, plus our `update/` |
| `scripts/` | `sync-upstream.sh`, `run.sh` |

## Staying in sync with upstream

This repository holds upstream's full history of `m9/` (from `~timofurrer/nine` at
`ab73f97`) with our changes on top.

```bash
scripts/sync-upstream.sh
```

merges what changed upstream since the last sync. When upstream changes `core/` or
`contacts/`, update [nine-core](https://github.com/NineTailsFlip/nine-core) first, then bump the
submodule here.

## Contributing

Issues and pull requests are welcome; see
[CONTRIBUTING](https://github.com/NineTailsFlip/.github/blob/main/CONTRIBUTING.md). If a bug
also happens in the official M9, please report it upstream too:
<https://todo.sr.ht/~timofurrer/nine>.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE). M9 is © Timo Furrer and the Nine contributors; our
changes are under the same license.
