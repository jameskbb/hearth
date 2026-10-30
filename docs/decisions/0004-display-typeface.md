# 0004: Literata is the display typeface

**Status:** Accepted, 2026-10-22. Supersedes the Fraunces choice in
`docs/design.md` (Type), which was never recorded as a decision of its own.

## Context
Hearth's display face sets the clock (44, light), the focused app name above
the favorites row (30) and the screen titles (30). It was Fraunces. A design
linter (the impeccable skill's detector) flagged Fraunces as an overused face:
it is one of the handful of display faces that new interfaces, generated ones
especially, keep converging on, so it no longer reads as chosen for this
product. The owner asked for a real replacement, not a suppressed warning.
The impeccable skill's typography guidance drove the selection and its
detector checked the result.

What the face has to do:
- Sit well on both painted wallpapers (amber against deep blue ridges in Dusk,
  emerald and violet in Aurora), in cream on dark, saturated art with only
  localized shading behind it.
- Read from a sofa about 3 m from a 55 to 65 inch panel at 1920x1080. The
  focused name changes on every D-pad press, so it must scan at once: even
  word shapes, no shimmer or aliasing in light and regular weights at 30 to
  44 dp.
- Stable digits for the clock, so the time does not jitter as it changes.
- Latin Extended coverage: app names come from any third-party app.
- Pair with Instrument Sans, which stays for all other text.
- SIL OFL, a small file, and warm, quiet character rather than novelty.

## Options compared
Proofs of the clock ("8:42"), a focused name ("Marquee Nine"), a title ("All
apps") and an accented name were rendered with headless Chrome over both
wallpapers at the real dp sizes, at 2x.

- **Literata** (TypeTogether, OFL). A serif drawn for long reading on screens,
  with an optical size axis from 7 to 72 and weights from 200. Large x-height,
  open counters and moderate contrast: the clearest of the set at distance,
  and its display optical sizes sharpen the wedge serifs so the light clock
  still has crafted detail. Tabular figures, broad Latin.
- **Piazzolla** (Huerta Tipografica, OFL). Angular and compact, with real
  character, but its optical size stops at 30, so the 44 clock gets a text
  cut that looks heavy and blunt, and the narrower letters read slightly less
  well across the room.
- **Petrona** (OFL). Warm and calligraphic, but the small x-height and
  slanted, uneven rhythm made names harder to scan at 30 dp, and the thin
  light weight is the one most likely to shimmer on a TV panel.

Rejected without a proof: anything on the linter's list or in the same
saturated set (Playfair Display, Cormorant, Lora, Crimson, Newsreader, DM
Serif, Instrument Serif), faces without light weights (Young Serif, Gelasio,
Besley, Vollkorn), and display faces too high in contrast for 10-foot light
weights.

## Decision
Literata, as a variable font, for the clock, the focused app name and screen
titles. Instrument Sans stays for everything else.

- Clock: weight 300 at optical size 44, tabular figures (Fraunces had no
  `tnum` feature, so the clock now really uses fixed-width digits).
- Focused name and titles: weight 400 (300 kept for a light variant) at
  optical size 30.
- Sizes, line heights and the hierarchy in `docs/design.md` are unchanged;
  Literata's larger x-height makes the 30 dp name read bigger at the same
  size, which helps across the room.

## Consequences
- The bundled file is trimmed with `scripts/trim-font.py` to weight 300 to
  400, optical size 24 to 72 and Latin (Basic, Latin-1, Extended-A and -B,
  Extended Additional, punctuation, currency): 343 KB, against 955 KB for the
  full upstream file and 360 KB for the Fraunces it replaces. Wider weights
  or other scripts mean re-running the script from the upstream file.
- The OFL travels with it in `third_party/fonts/Literata-OFL.txt`. Literata
  declares no Reserved Font Name, so the trimmed file keeps its name.
- The Hearth banner, the demo app banners, the README title card and the
  concept page set their serif in Literata too, and the banners were
  regenerated. The sunset concept render (`design/concepts/concept-sunset.png`)
  keeps Fraunces on purpose: it is the record behind decision 0001. Literata sets wider than Fraunces,
  so the wordmarks are a few pixels smaller to keep their footprint, and the
  demo wordmarks that used heavier Fraunces weights now use 400, the heaviest
  the trimmed file has.
- The README screenshots, tour and title card show the app's type, so they
  are re-recorded with `docs/recording-media.md` whenever this changes. When
  this decision landed they still showed Fraunces: the emulator could not
  start (no KVM in WSL after a host update), so the re-recording is the first
  item in `docs/follow-ups.md`.
