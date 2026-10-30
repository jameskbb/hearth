# Hearth design spec

The source of truth for how Hearth looks and behaves. Concept renders live in
`design/concepts/`; theme art sources in `design/art/`; the theme pack format
in [`theme-packs.md`](theme-packs.md).

## Identity

A home screen for watching TV in a dark room. Warm light against deep blue
shadow: the theme's art does the showing off, the interface stays quiet, and the
installed apps are the stars. Restraint borrowed from good home-theater UI,
without copying any one product.

Chosen concept: **Dusk** (cinematic sunset), with **Aurora** as the second
scene. The gallery concept was dropped: drawn landscapes read as clip art,
and real photography would need licensed images. See
`docs/decisions/0001-dusk-concept.md`. Every scene is now a theme pack with
its own palette and gentle motion ([decision 0006](decisions/0006-theme-packs.md));
see Themes below.

## Canvas and safe area

- Design in dp on the 960x540 dp canvas a 1080p Fire TV reports (320 dpi). The
  UI is rendered at the device's UI resolution; do not assume 4K.
- Safe margins: 48 dp left and right, 32 dp top and bottom. Nothing focusable
  or readable outside them. The theme's art bleeds to the edges.

## Layout (home)

```
 clock (Literata 44 light)     [glyph] 64° (Literata 30 light)       theme art
 date (Instrument Sans 13)     Partly cloudy, high 70°, low 52°

                     upper ~55% of the screen: art, unobstructed

 Focused app name (Literata 30)
 Press ☰ or hold Select for options (Instrument Sans 12, muted)
 [ focused ][ tile ][ tile ][ tile ][ ti...      favorites row, scrolls horizontally
 All apps   Arrange   Settings                   action row (Instrument Sans 14)
```

- The clock and dock align to the 48 dp left margin. An optional Home label
  aligns to the 48 dp right margin (see Home label).
- Favorites: tiles 184x104 dp, 20 dp gap, 10 dp radius. The row scrolls; the
  focused tile is kept fully in view with at least the left margin of lead.
- With few favorites the row simply ends; no placeholders or empty boxes.
- With no favorites: the row band collapses, the name area reads "Add apps you
  watch" with the hint "Open All apps, then press ☰ or hold Select on an app to
  add it", and the action row's All apps is focused.
- When an action (All apps, Arrange, Settings) has focus, the name line is
  empty (it keeps its height) and the hint line says what the action does.

## Home label

A reusable, optional title on Home only, off and empty by default. Configure it
in Settings, Theme (Appearance), Home label. Show on Home toggles visibility
without discarding text. Edit text opens the existing TV keyboard/underline
field pattern; Done or Save commits it, Back cancels the draft. Clear label
removes the text and turns visibility off. The setting survives restarts.

The title uses Literata regular at 26 sp, 32 sp line height, the active pack's
ink and the shared glyph shadow. It sits 12 dp below the header's top, aligned
to the right safe margin. It has no backing surface, animation, or focus stop.
Maximum width is 300 dp, one line with ellipsis. Measure clock and optional
weather first; leave 32 dp between them and the title, narrowing it as needed.
Hide the title if less than 80 dp remains. Long dates or weather cannot collide
with it. Disabled or blank text produces no label. The wallpaper always works
without it, and the title never follows the user into other screens.

Text is trimmed, limited to 40 Unicode code points, and control, directional
formatting and line separators become spaces. A supplementary character is
never split. Store this only in launcher preferences, never in a theme pack.

## Text over artwork

The landscape is unobscured: no full-screen dimming, gradient pools, or gray
backing panels under text. Restrained shadows follow the glyphs themselves.
Secondary text remains bright enough to read from the sofa. Check lettering
against every theme's bright and dark regions, including moving clouds.

## Type

- **Literata** (variable, OFL): clock, focused app name, screen titles. Light
  to regular weights only (300 to 400), each role pinned to its optical size
  (44 for the clock, 30 for names and titles). The clock uses tabular figures
  so the digits do not shift as the time changes. Chosen in
  `docs/decisions/0004-display-typeface.md`.
- The temperature beside the clock is Literata light (300) at 30, optical
  size 30, with tabular figures: the clock's voice, one step down.
- **Instrument Sans** (variable, OFL): everything else.
- Sentence case everywhere. No all-caps labels, no eyebrow labels, no
  middle-dot meta strings.

## Tiles and artwork

- TV apps: draw the app's banner. Banners are 16:9; draw them filling the tile.
  If a banner is not 16:9, fit it (no cropping, no stretching) on a backdrop
  sampled from the artwork.
- No banner: the app icon centered at ~56 dp on a surface tinted from the
  icon's dominant color, label bottom-left.
- TV inputs (HDMI): Hearth's own HDMI glyph (24-unit grid, one 1.5-unit
  stroke, round caps and joins, white, like the weather glyphs) drawn as an
  icon-only tile: centered at ~56 dp on a muted slate surface, label
  bottom-left.
- No artwork at all: a monogram fallback (first letter, large, low opacity)
  with the label, on a neutral surface derived from the theme's palette.
- Tiles show no text when a real banner is drawn (banners carry their own
  brand); the focused app's name above the row names it.

## Focus (must read from across the room)

- Focused tile: scale 1.08, a crisp 2 dp `accent` ring separated from the
  artwork by a 2 dp dark gap, and a small `glow` light beneath it (offset
  downward, never a halo all the way round).
- Unfocused tiles are not dimmed; artwork stays vivid.
- Text actions (All apps, Arrange, Settings, settings rows): focused = `ink`
  at full opacity with an accent underline 2 dp; unfocused = `inkMuted`.
- Motion: 160 ms, standard easing, only in response to the user. The one
  exception is the theme's art, which may move slowly on its own (see
  Themes). Reduced motion: no scaling, no crossfades; the ring alone shows
  focus, and every theme is a still image.
- Never let focus disappear: every screen has a defined initial focus, and
  removing or hiding the focused item moves focus to its neighbor (next, else
  previous, else the first action).

## Remote behavior

| Key | Home | All apps | Settings | Dialog | Arrange mode |
|---|---|---|---|---|---|
| D-pad | move | move (grid) | move | move | Left/Right move the tile |
| Select | open app / action | open app | activate | choose | place the tile (done) |
| Long-press Select | options dialog | options dialog | n/a | n/a | n/a |
| Menu (☰) | options dialog | options dialog | n/a | close | n/a |
| Back | see below | to Home | to Home (from the city search, Appearance or a rename: back to Settings) | close | cancel (restore order) |

- **Back at the root of Home never leaves Hearth** (leaving would reveal the
  Amazon launcher). If focus is not on the first favorite, Back moves it
  there; otherwise Back does nothing.
- Options dialog for an app: Open, Add to favorites / Remove from favorites,
  Move (enters arrange mode), Hide. Hide asks nothing; it can be undone from
  Settings, Hidden apps.
- Arrange: discoverable from the action row ("Arrange") and from the options
  dialog ("Move"). The moving tile lifts (accent ring + small up offset);
  hint text reads "◀ ▶ to move, Select to place, Back to cancel".
- Returning from a launched app restores the previously focused tile and
  scroll position.

## All apps

- Grid, 4 columns of 184x104 dp tiles, sections "System inputs" (the TV's
  HDMI inputs, only on TVs that have them), "TV apps", then "Other apps"
  (launchable but not built for TV). Hidden apps and inputs excluded. Hearth
  itself excluded.
- Inputs behave like apps: Select switches the TV to that input through the
  platform's own player, and the options dialog adds them to favorites,
  moves or hides them. They are listed only when the TV's input service
  lists HDMI inputs and the documented passthrough route resolves
  (`docs/decisions/0007-tv-inputs.md`); on a stick nothing input-related
  appears. A favorite input that disappears is not shown and returns in
  place when the input does.
- Inputs are named as the TV names them (its custom label, else its label).
  An input the TV gives no name is "HDMI n", numbered by its position among
  the HDMI inputs (the first is "HDMI 1"), never by the number in the
  framework's input id, which on some TVs does not match the port.
- Title "All apps" in Literata 30 at the top margin.

## Settings

Small and honest; every row does something.
- Theme: the theme in use ("Dusk" for Single Theme,
  "Follow the Day, Morning Glow now", or "Surprise Me, Aurora today"). Select
  opens Appearance (see Themes).
- Reduce motion: on / off.
- Weather: Show on Home, City, Units, and a status line (see Weather).
- Inputs (only on TVs with HDMI inputs): one row per input, its name and
  "Rename" (see Renaming an input).
- Hidden apps: list with "Show" to restore each.
- Device settings: opens the platform's settings through a route verified at
  runtime; hidden when no route resolves.
- About: a separate destination with version, font licenses, the weather data credit ("Weather data by
  Open-Meteo.com, CC BY 4.0"), and the display facts the device reports
  (resolution, refresh rate, wide color gamut, HDR types). Real values only.
  Back returns to the About row in Settings.

## Weather

Opt-in and off by default ([decision 0005](decisions/0005-opt-in-weather.md)).
A glance, not a widget: no card, no box, no focus stop.

### On Home

- A second column beside the clock, 40 dp to the right of the wider of the
  time and the date. Top line: Hearth's condition glyph (26 dp, 10 dp gap)
  and the temperature ("64°", Literata 30 light, tabular figures), sitting on
  the clock's baseline. Bottom line: the condition with today's range
  ("Partly cloudy, high 70°, low 52°", the date's style), on the date's
  baseline. The two columns read as one header: time and date, weather and
  sky.
- That is all it shows. The current temperature and the sky answer "what is
  it like out"; the high and low answer "what will it be like", which is the
  one forecast question worth a glance from the sofa. No city name (the
  household chose it), no unit letter (the household chose that too), no
  hourly strip, no "feels like".
- Ink for the glyph and temperature, muted ink for the caption, no accent:
  the same treatment as the clock and date.
- Not focusable and never animated. It changes only when a new result
  arrives (at most every 30 minutes) and disappears, without a transition,
  when the last result passes three hours old.
- When weather is off, has no city, has never fetched, or is stale, the column
  is absent and Home uses the weatherless layout.
- Unknown weather code: the temperature and range show without a glyph or
  condition. Missing high or low: the condition alone.

### Glyphs

Hearth's own vector drawables (`res/drawable/weather_*.xml`), generated by
`scripts/weather-glyphs.py`, never an icon font. A 24-unit grid, one 1.5-unit
stroke with round caps and joins, white in the file and tinted with the
palette's `ink`. Shapes are built from circles: a cloud is three circles on a
flat base; the moon is a circle minus a circle. Where the sun or moon sits
behind a cloud, its outline stops 1.4 units short of the cloud, so layering
reads without fills.

| Glyph | Conditions (WMO codes) |
|---|---|
| clear (sun by day, moon by night) | Clear (0), Mostly clear (1) |
| partly cloudy (sun or moon behind a cloud) | Partly cloudy (2) |
| cloudy | Cloudy (3) |
| fog (cloud over two broken lines) | Fog (45, 48) |
| drizzle (short dashes) | Drizzle (51, 53, 55) |
| rain (three slanted lines) | Rain (61, 63), Heavy rain (65), Showers (80, 81), Heavy showers (82) |
| sleet (lines and dots) | Freezing drizzle (56, 57), Freezing rain (66, 67) |
| snow (dots) | Snow (71, 73, 77), Heavy snow (75), Snow showers (85, 86) |
| thunderstorm (cloud and bolt) | Thunderstorm (95), with hail (96, 99) |

### In Settings

A "Weather" section between Device settings and Hidden apps, same rows as
the rest of Settings:

- **Show on Home**: On / Off. Turning it on without a city opens the city
  search straight away.
- **City**: the stored city ("Springfield, Illinois, United States"), or
  "Choose a city". Select opens the search. Shown only while weather is on.
- **Units**: Fahrenheit / Celsius, defaulting from the locale; Select
  switches. Shown only while weather is on.
- **Status**, plain text under the rows (Instrument Sans 12, never
  focusable). Off: "Hearth goes online only when this is on, and then sends
  just your city's location to Open-Meteo." On: "Choose a city to show the
  weather on Home.", "Getting the weather from Open-Meteo…", or "Updated at
  8:42 PM from Open-Meteo." A failure leads, in full ink: "Couldn't reach
  Open-Meteo. Hearth will try again at 9:05 PM." (also "Open-Meteo returned
  an error." and "Open-Meteo sent weather Hearth couldn't read."), followed by
  "Last updated at 6:12 PM, too long ago to show on Home." when that applies.
  The retry is an absolute time, so the line never needs to tick.

### City search

Replaces the Settings rows (title "Choose a city").

- A text field with Hearth's own underline (1 dp `inkMuted`, 2 dp `accent`
  when focused) and the placeholder "Type a city name". No caret: a caret
  blinks for as long as the field is focused, and text is only ever added at
  the end from the keyboard.
- The field takes focus when the search opens, which brings up the system
  keyboard. Matches appear while typing, from two letters and 450 ms after the
  last key. The keyboard's Search (or Done) key searches at once, closes the
  keyboard and focuses the first match.
- Matches are rows: the city name, then its region and country in the value
  position ("Springfield   Illinois, United States"), up to eight. Down moves
  from the field to the matches; Select on a match stores it, turns weather
  on, closes the search and focuses the City row.
- The line under the field says what to do or what happened: "Press Select
  to type. Matching cities appear as you type.", "Searching…", "Choose your
  city, or press Back to cancel.", "No cities match "Springfeld". Check the
  spelling, or try a larger town nearby.", "Couldn't reach Open-Meteo. Check
  the connection, then search again."
- Back closes the keyboard first (the system does that), then the search,
  returning focus to the City row. The Home button closes it too.

## Themes

Theme packs ([decision 0006](decisions/0006-theme-packs.md)); the format,
where packs live, validation and Auto are specified in
[`theme-packs.md`](theme-packs.md).

### The art on screen

- Layers are drawn on one canvas behind everything, full bleed. Each pack's
  palette replaces the colors above (ink, accent, glow, shades, window).
- The art may breathe: slow sway, drift, opacity and one-time fades, at most
  30 frames a second. It moves only while Hearth is in front and Reduce
  motion is off; otherwise the theme is drawn once, at rest, and nothing
  ticks. Tiles, text and focus still move only in response to the user.
- Time starts when a theme appears and again each time Hearth comes back to
  the front, so a fade (a sky turning to twilight) plays each time.
- Switching themes keeps the old one on screen until the new one is decoded,
  then crossfades over 160 ms (at once under Reduce motion). Until the first
  theme decodes, the window color shows; never a wrong image.
- Fallback: a chosen pack that is gone or broken shows Dusk; if Dusk cannot
  load either, the window color with Dusk's palette.

### Appearance

Select on the Theme row opens the full-width Appearance panel (title
"Appearance"). About is hidden while choosing or scheduling themes so it does
not compete with the live preview.

- One horizontal row chooses Single Theme, Follow the Day, or Surprise Me.
  Left and Right move between choices. The current mode is labeled Selected;
  one short explanation below describes it. Follow the Day is recommended.
- Single Theme shows the theme gallery. Each card is 184x104 dp with the
  tiles' radius and focus treatment (scale, ring, glow), showing the pack's
  preview; the name sits under it. Focus shows the real theme
  live behind the panel. Select saves it, returns to Settings, and restores
  focus to the Theme row.
- Follow the Day reveals Customize schedule without forcing the user into
  setup. Theme Schedule opens only when that row is selected. Each period row
  shows its name, local start and end, and either its fixed theme or the names
  it rotates daily. The recommended schedule is Morning 05:00 to 09:00,
  Daytime 09:00 to 17:00, Evening 17:00 to 20:30, and Night 20:30 to 05:00.
  Restore recommended schedule is a direct action on this screen.
- Selecting a period opens its compact editor with time adjustment first, so
  it never opens the keyboard unexpectedly. The user can move its start in 30
  minute steps and choose one theme or a shuffled pool. Rename period opens
  a separate text-entry page below the gallery. A period's end follows the next period's start, which prevents gaps
  and overlaps. Boundaries keep at least 30 minutes between adjacent periods.
  Back returns to the schedule with the edited period focused.
- Surprise Me shows the gallery as an enabled set. Select toggles a card.
  Hearth chooses once per local day and avoids yesterday's pack when another
  enabled pack is available.
- Home label opens its quiet editor below the theme controls in every mode.
- Surprise Me's default Included markers follow manifest `autoRotationEligible`
  (true when absent). Open Bay opts out. Toggling a card creates an explicit
  pool, including an intentional manual-pack choice; a full custom pool stays
  explicit. Existing theme modes and recommended day periods are unchanged.
- Reload installed themes rescans packs while keeping Appearance open. Changed
  artwork and previews replace stale cache entries even when the manifest is
  unchanged. Failed packs appear below it with a short reason.
- The backdrop is always the live preview: decoding runs off the main thread,
  the previous theme remains until the new one is ready, and the palette
  follows it. With Reduce motion on, the change is immediate and every scene
  is still.
- Back moves from renaming to the period editor, to Theme Schedule, to Appearance, then
  to Settings. Home closes the panel and returns to Home without losing the
  saved configuration.
### Renaming an input

Replaces the Settings rows (title "Rename input").

- The city search's field, holding the input's current name, takes focus and
  brings up the system keyboard. Up to 40 characters.
- Under it: "Type a name, then press the keyboard's Done or select Save. An
  empty name uses the TV's name.", then a "Save" row, and, while a name of
  the user's is stored, "Use the TV's name" with the TV's name as its value.
- Done or Save stores the trimmed name and returns focus to the input's row.
  An empty name, or the TV's own name, removes the custom name. Back closes
  the keyboard first, then the panel, keeping the old name.
- Names are Hearth's own preference and appear on the input's tile and in
  the name line. Hearth never renames inputs in the system.

## Failures

- Launch fails or app removed: an inline message in the name area, e.g.
  "Netflix isn't installed anymore." An input that does not switch says
  "HDMI 1 didn't open. Try again in a moment." If the app's tile disappears, focus and
  the message move to its neighbor so the message can be read; it clears on
  the next focus move.
- No launchable apps found: "No apps found" with the action row still usable.

## Color and display

- Theme base layers are sRGB, 1920x1080, with fine grain baked in to prevent
  banding.
- Wide gamut and HDR are enhancements only if the device reports support and
  the whole rendering path honors it; the default path is excellent SDR. Do
  not raise saturation to imitate wide gamut. See `docs/display.md`.

## Older Android typography compatibility

API 21 through 25 use generated static instances of the existing Literata and Instrument
Sans fonts, preserving the weights, optical sizes, sizes, and layout above.
API 26 and later retain the original variable-font path. This is a rendering
compatibility fallback, not a new visual theme or typography system.
