# 0005: Opt-in weather from Open-Meteo, off by default

**Status:** Accepted, 2026-10-22. Amends the offline promise in
`docs/BRIEF.md` (Engineering) and the "No INTERNET permission" rule in
`CLAUDE.md`/`AGENTS.md`; it does not withdraw it.

## Context
Hearth was offline by design: no INTERNET permission at all, and the brief
listed weather as out of scope. The owner asked for the weather on Home, as a
glance from the sofa, without giving up what made the offline promise worth
making: no accounts, no tracking, nothing sent anywhere the user did not ask
for, and a launcher that starts and works with the network unplugged.

## Decision
- **Source: Open-Meteo** (https://open-meteo.com). Free, no API key, no
  account, and its data is CC BY 4.0. Two endpoints only: the forecast API
  (`api.open-meteo.com/v1/forecast`) and the geocoding API
  (`geocoding-api.open-meteo.com/v1/search`). No other service.
- **Off by default.** While weather is off, Hearth makes no network request of
  any kind and Home looks exactly as it did before. Turning it on in Settings
  is the only thing that ever uses the network.
- **The user picks the city.** They type a few letters with the remote (the
  system keyboard) and choose from Open-Meteo's geocoding matches. Name,
  region, country and coordinates are stored locally in DataStore. There is no
  location permission and no IP geolocation.
- **What is sent:** the chosen city's coordinates, rounded to four decimals,
  to the forecast API; and, while searching, the letters typed, to the
  geocoding API. Nothing else: no identifiers, no cookies, and no headers
  beyond `Accept` and those the platform's HTTP client adds.
- **INTERNET permission** is declared for this feature alone; cleartext
  traffic is disabled, so every request is HTTPS.
- **Refresh:** on launch or return to Hearth when the last result is 30
  minutes old or more, then at most every 30 minutes while Hearth is in front,
  as one scheduled delay (no ticking loop). After a failure Hearth waits 5,
  then 15, 30 and 60 minutes. Nothing runs while Hearth is in the background.
- **Never stale as current:** the last good result is kept with its time
  (also in a small cache file, so a recent result shows at once on launch).
  Older than three hours, or never fetched, Home shows no weather at all, and
  Settings says why and when Hearth will try again.
- **Units:** Fahrenheit or Celsius, defaulting from the locale (Fahrenheit in
  the US and the few other places that use it); a Settings choice overrides.
  Values are converted locally, so changing units sends nothing.
- **Attribution:** "Weather data by Open-Meteo.com, CC BY 4.0" in Settings,
  About, and in `third_party/README.md`.
- **No new dependencies:** the platform's `HttpsURLConnection` and `org.json`.
  The JVM unit tests use `org.json:json` (test scope only, pinned) because
  `android.jar` ships only stubs of it.

## Why Open-Meteo
It is the only well-known source that needs no key and no account, which
keeps the repository free of secrets and the user free of sign-ups, and its
geocoding API makes a typed city search possible without a location
permission. Its free tier is for non-commercial use, which fits an
open-source launcher; a household refreshing every 30 minutes is a few dozen
requests a day.

## Consequences
- The offline promise now reads: no internet use unless you turn on weather,
  which sends only your chosen city's coordinates to Open-Meteo. README, brief
  and agent rules say so.
- Hearth still starts and works fully offline; with weather on and no
  network, Home simply shows no weather and Settings shows the reason.
- The design (placement beside the clock, Hearth's own condition glyphs, the
  Settings rows and the city search) is specified in `docs/design.md`,
  Weather.
- Device checks for weather are in the `docs/testing.md` checklist; the
  README media must show weather once they are re-recorded
  (`docs/follow-ups.md`).
