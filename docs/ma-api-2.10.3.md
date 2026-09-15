# Music Assistant 2.10.3 - kontrakt API użyty przez Heliosa

Źródło: `http://<ma>:8095/api-docs`, `api-docs/commands.json` i `api-docs/schemas.json` odczytane z instalacji docelowej 2026-09-15 (serwer 2.10.3, schema 65). Kopie w `.local/ma-commands.json` i `.local/ma-schemas.json` (niewersjonowane).

## WebSocket `ws://<ma>:8095/ws`

- Pierwsza wiadomość: `{"message_id":"1","command":"auth","args":{"token":"<token>"}}` → `{"message_id":"1","result":{"authenticated":true,"user":{...}}}`.
- Komenda: `{"message_id":"<unikalny>","command":"players/all","args":{}}` → `{"message_id":"<ten sam>","result":...}`; błąd niesie `error_code`/`details` zamiast `result`.
- Zdarzenia bez `message_id`: `{"event":"player_updated","object_id":"<player_id>","data":{...Player}}`, analogicznie `queue_updated`.
- HTTP RPC: `POST /api` z `Authorization: Bearer <token>` i tym samym `{"command","args"}`.

## Komendy używane przez Heliosa

| Komenda | Argumenty | Zwraca |
| --- | --- | --- |
| `players/all` | `return_unavailable?` | `Player[]` |
| `players/get` | `player_id` | `Player` |
| `music/search` | `search_query`, `media_types` (`track`, `album`, `playlist`, `radio`), `limit` (na typ), `providers=["library"]` | `SearchResults {tracks, albums, playlists, radio, ...}` |
| `player_queues/get_active_queue` | `player_id` | `PlayerQueue` (`queue_id`, `state`, `current_item.name`, `current_item.image`) |
| `player_queues/play_media` | `queue_id`, `media` (uri lub obiekt), `option` (`replace` dla albumu/playlisty, `play` dla utworu) | `null` |
| `players/cmd/play`, `pause`, `stop`, `next`, `previous` | `player_id` | `null` |
| `players/cmd/volume_set` | `player_id`, `volume_level` 0-100 | `null` |
| `players/cmd/volume_mute` | `player_id`, `muted` | `null` |

`library_only` w `music/search` jest przestarzałe. Wszystkie komendy wymagają uwierzytelnienia i zakresów `players.read/control`, `queues.read/control`, `library.read`.

## Pola modelu

- `Player`: `player_id`, `name`, `available`, `playback_state` (`idle|paused|playing|unknown`), `volume_level`, `volume_muted`, `group_members`, `active_group`, `current_media {title, artist, album, image_url}`, `hide_in_ui`, `type`.
- Elementy wyszukiwania (`Track`, `Album`, `Playlist`, `Radio`, `ItemMapping`): `item_id`, `provider`, `name`, `uri`, `media_type`, `metadata.images[]` (`type`, `path`, `provider`, `remotely_accessible`) lub `image` w `ItemMapping`.
- Okładka: `GET /imageproxy?path=<path>&provider=<provider>&size=256` na hoście MA (token nie jest wysyłany do obcych hostów; obrazy `remotely_accessible` też idą przez proxy MA).

Sendspin (rola lokalnego odtwarzacza) jest osobnym kanałem `ws://<ma>:8927/sendspin`, wersja 1 (legacy), patrz `SendspinClient`. Lenovo pojawia się w `players/all` jako gracz providera `sendspin`; jego `player_id` Helios znajduje po `device_info`/nazwie zgłoszonej w `client/hello` i używa do `players/cmd/volume_set`.
