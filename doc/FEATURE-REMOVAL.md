# Removed features

## Trakt
- Removed phone/TV account preferences, authentication activities, OAuth dialog,
  relog broadcasts, startup/import notifications, synchronization and scrobbling.
- Removed the Trakt Java SDK, its OAuth client dependency and associated keep rules.
- Removed account artwork, translations and store-listing claims.
- SuperNova uses ../sn_libraries/MediaLib; the adjacent shared MediaLib and older Video
  project have not been edited.
- Local completion at 90%, watched/unwatched actions, local/NAS resume, playlists,
  and playback history remain. Existing database column names are retained for
  compatibility. Old account preferences are cleared on startup.

## Phone trailers
- Removed trailer fetching, rendering and bound views from VideoInfoActivityFragment
  and video_info2_scraper.xml. The TV details trailer implementation remains.

## Bottom last-played panel
- Removed MainActivity's panel worker, observer, view fields and bindings.
- Removed BrowserLayout/BrowserActivity panel APIs, layout stubs, obsolete panel
  layout, preferences and dimensions.
- Resume launcher/media-key actions and playback history remain.

Only project files were changed. No Gradle build, compilation or packaging was run.
