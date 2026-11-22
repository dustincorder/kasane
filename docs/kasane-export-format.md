# Kasane export format (`kasane-library`, version 1)

Plain, versioned JSON. It carries no account data, cookies, stream URLs or audio.

```json
{
  "format": "kasane-library",
  "version": 1,
  "app": "Kasane 0.1.1-alpha",
  "exportedAt": 1700000000000,
  "favorites": [ { "title": "...", "artist": "...", "album": "...", "durationSeconds": 215.0, "videoId": "dQw4w9WgXcQ" } ],
  "history":   [ { "track": { "title": "...", "artist": "..." }, "playedAt": 1700000000000 } ],
  "playlists": [ { "name": "Night drive", "tracks": [ { "title": "...", "artist": "..." } ] } ]
}
```
- `videoId` is the stable identity when known; otherwise importers match by title, artist and duration.
- A reader must reject `version` greater than it supports and ignore unknown fields.

## Importers (`shared/.../library/`, `android/.../library/`)
| Source | Adapter | Evidence |
|---|---|---|
| Kasane JSON | `KasaneExport` | this document |
| M3U / M3U8 | `PlaylistFormats.parseM3u` | `#EXTINF`, `#PLAYLIST`, YouTube links |
| PLS | `PlaylistFormats.parsePls` | `FileN/TitleN/LengthN` |
| CSV | `PlaylistFormats.parseCsv` | RFC 4180, Spotify/Exportify columns, `;` and tab delimiters |
| Generic JSON | `PlaylistFormats.parseJson` | arrays or `{playlists:[{tracks|songs|items}]}` |
| InnerTune-family `.backup` (InnerTune, OpenTune by Arturo254, Metrolist) | `InnerTuneBackupAdapter` | ZIP with `song.db`; tables `song`, `artist`, `song_artist_map`, `playlist`, `playlist_song_map`, `event` (InnerTune entities; OpenTune issue #463 confirms `event` and `song`) |

Not implemented: the JSON export of the justshivv/Opentune fork. Its schema was not verified, so it is not guessed; add an explicit adapter once a real sample file is available. Backup settings are never read because they can contain the account cookie.

## Matching and safety
`TrackMatcher`: stable video id first (confidence 1.0); otherwise 0.55 title + 0.30 artist + 0.15 duration after stripping "(Official Video)", "feat.", "- Topic". >= 0.85 auto-accepts, 0.60-0.85 needs review, below is unresolved; a recording more than 20 s off cannot auto-match.
`ImportPlanner` is pure: it never writes. Clashing playlist names become "Name (imported)"; tracks already present are reported as duplicates; `report()` lists unresolved tracks.

## Status
Parsers, adapter, matcher, planner and export encoder are implemented and tested. Not done: the import UI (file picker, preview, confirmation), applying a plan to Room, and local playlists, which Kasane does not store yet.
