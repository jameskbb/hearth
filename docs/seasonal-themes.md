# Seasonal themes

Six occasion packs extend the existing scene collection: Harvest Evening
(uncarved fall harvest), Halloween, Christmas, New Year, Easter and July Fourth.
Their editable SVG layers live in `design/art/<id>/`, with rendered packs in
`app/src/main/assets/themes/<id>/`. Each source folder records its artwork
provenance and motion choices in `notes.txt`.

## Direction contract

**Thesis:** seasonal atmosphere within Hearth's quiet landscape collection.
Holiday details belong in the environment, with uncarved pumpkins for harvest,
friendly carved smiles for Halloween, steady Christmas lights, champagne
accents for New Year, spring colors for Easter and restrained red, cream and
blue for July Fourth.

**Own world:** retain the layered SVG art, fine anti-banding grain, deep blue
or green shadows, warm light, existing type and focus treatment. No new UI
surfaces, artwork text or personalized labels.

**Story:** choose a seasonal scene in Appearance and return to the same Home
layout. All six are manual by default, with explicit opt-in to custom
rotation pools or schedule periods.

**First viewport:** full-bleed 1920x1080 art, quiet clock and weather space
at upper left, title space at upper right, and dark lower foreground behind
the favorites and actions. Holiday details sit toward the right near the
horizon, where they can be enjoyed above the app tiles.

**Form:** extend the established landscape composition from Dusk, Misty Forest
and Golden Prairie. The existing manifest drives slow, sustained atmospheric
motion; terrain and decorations remain still. No concept seed is needed for
this extension of the existing design.

**Finish:** review every rest render and motion sheet, verify the shipped packs
and document their limits. Preserve the existing design system and record
provenance for all new artwork.

## Behavior

Every pack sets `autoRotationEligible: false` and has no recommended dayparts.
The seven everyday scenes and recommended schedule keep their existing
behavior. Seasonal packs can be used at any time of year; there is no date
detection, holiday scheduler or network access.

Only atmospheric layers move. Reduce motion draws the shared renderer's rest
state, and leaving Hearth suspends animation through the existing lifecycle
handling. No flashing lights or rapid fireworks are part of these packs.

| Pack | Sustained motion | Decoded layers |
|---|---|---|
| Harvest Evening | Clouds drift at 21.3 dp/min | 13.3 MiB |
| Halloween | Clouds drift at 21.3 dp/min | 13.2 MiB |
| Christmas | Clouds and powder drift at about 20 dp/min | 14.2 MiB |
| New Year | Haze drifts at 18 dp/min; reflections glide and breathe slowly | 9.1 MiB |
| Easter | Clouds drift at 21 dp/min | 13.7 MiB |
| July Fourth | Clouds drift at 20.3 dp/min; water reflections glide slowly | 8.8 MiB |

All six are below the 32 MB decoded limit. Their rendered layer files and
gallery previews add about 6.8 MiB to the APK before ZIP overhead. Artwork is
authored locally as SVG, with no third-party images or additional dependencies.

## Verification

Final comparison against Dusk, Golden Prairie and Misty Forest found no
material drift from the existing landscape system. The rest renders and Home
captures retain quiet header space, layered terrain, a dark foreground and
the existing typography and focus treatment. All six source manifests match
their shipped copies and use the existing palette roles and motion format.
This extension adds artwork and pack palettes without changing UI components,
fonts, shared tokens or renderer behavior.

`scripts/check.sh` passes, including all 388 app JVM tests and fixture/demo
builds. `./gradlew assembleDebug` passes. Bundled-pack tests validate all fourteen
packs, source/shipped manifest parity, layer coverage throughout motion, and
sustained movement at 0, 240 and 600 seconds. Rotation tests verify seasonal
exclusion from automatic pools and explicit selection of every special pack.

Each theme's base passes the existing banding check. Rest renders and initial
motion sheets were inspected; late-motion frames check that the scene continues
moving after ten minutes. At 1080p, sampled date, hint and action text regions
keep at least 4.5:1 contrast through the tested motion phases, even without
counting the glyph shadow. The shared renderer and lifecycle handling are
unchanged. Physical-panel frame pacing, memory and sofa-distance readability
still require the [device checklist](testing.md).

## Halloween review

Halloween is a separate pack. Harvest Evening's original source, bundled
images, preview and captures are preserved byte for byte. Halloween uses
new authored SVG layers and the collection's existing landscape language.
Carved faces have rounded eye openings, upturned smiles and a single square
tooth, with no angry brows or fangs. Smooth ribs and simple layered hills
keep the scene illustrated. Three larger pumpkins sit above the app row;
small unlit pumpkins make the patch recede into the landscape.

Creative review covers the rest render, thumbnail, native Home and Settings,
and motion sheets at startup and after ten minutes. Broad text-region
contrast sampling at 0, 60, 240, 600 and 840 seconds keeps small Home and
Settings lettering above 4.5:1 and large lettering above 3:1 without relying
on glyph shadows. Only the faint cloud strip moves; candlelight is steady.
The same manual rotation policy, palette roles and renderer are retained.
Physical-panel color, long-duration frame pacing and sofa-distance judgment
remain separate device checks.

## TV captures

Captured at 1920x1080 on the API 28 Android TV emulator with
`scripts/screenshots.sh`. Reduce motion is on to show the rest state. The
clock is the emulator's local time, weather is off, and the app banners are
the repository's offline demos. Harvest Evening, Halloween and July Fourth
were set through temporary local preference fixtures. Christmas, New Year and Easter
were selected with D-pad and Select through Appearance. The original
preferences were restored after capture.

This run used software emulation without hardware acceleration. During the
long navigation sequence, both Hearth and the system launcher reported input
timeouts. July Fourth was captured separately after restarting Hearth. These
captures validate rendering, with no claim of sustained remote responsiveness
or frame pacing. Repeat those checks on an accelerated emulator or physical TV.

| Harvest Evening | Halloween |
|---|---|
| ![Harvest Evening on Home](images/home-harvest-evening.png) | ![Halloween on Home](images/home-halloween.png) |

![Christmas on Home](images/home-christmas.png)

| New Year | Easter |
|---|---|
| ![New Year on Home](images/home-new-year.png) | ![Easter on Home](images/home-easter.png) |

![July Fourth on Home](images/home-july-fourth.png)

## Reproduce

Use the documented JDK 17 and Android SDK, Chrome, and a Python interpreter
with Pillow and numpy:

```bash
REVIEW_DIR=build/seasonal-review scripts/render-theme.sh \
  harvest-evening halloween christmas new-year easter july-fourth
scripts/check.sh
./gradlew assembleDebug
```

For a late-motion sheet, use `scripts/theme-compose.py` with
`--frames 6 --start-seconds 600 --seconds 240 --contact-sheet <output.png>`.
The README shows the packs' rest renders. Device checks for readability,
frame pacing, lifecycle and memory remain in [testing.md](testing.md).
