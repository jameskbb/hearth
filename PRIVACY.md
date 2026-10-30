# Privacy

This describes Hearth's application behavior. GitHub, your TV platform,
installed apps, and any separate Home-button helper have their own policies.

## Stored on your TV

Hearth keeps favorites, app order, hidden apps, input names, theme choices,
motion preferences, and any Home label in its private app storage. App artwork
is read from installed apps and cached locally. If you use weather, the chosen
city and coordinates are saved locally, along with a cached weather result.

There is no Hearth account, cloud sync, advertising, analytics, or crash-report
service. The app disables Android's standard app backup in its manifest.
Fonts and bundled themes load from the APK; installed theme packs are local.

Uninstalling Hearth or clearing its app storage removes its preferences and
cache. Turning weather off stops its requests but does not erase the saved
city or cached result. Clearing app storage also resets your favorites and
other settings.

## Optional weather

Weather is off by default. With it off, Hearth makes no network requests.
When you enable it and search for a city, the search text goes to
`geocoding-api.open-meteo.com`. Forecast requests send the selected city's
coordinates, rounded to four decimal places, to `api.open-meteo.com`.
Requests use HTTPS. Open-Meteo also receives the network address needed for
the connection and ordinary HTTP client information.

Hearth requests no device location permission and adds no account, tracking
identifier, or analytics payload. It refreshes forecasts at most every
30 minutes while in the foreground, with longer waits after failures.
It does not schedule weather requests while in the background. Weather older
than three hours is hidden from Home. Changing temperature units is local.

See [Open-Meteo's privacy policy](https://open-meteo.com/en/privacy) for how
that service handles requests, and the
[weather design decision](docs/decisions/0005-opt-in-weather.md) for details.

## Bug reports and screenshots

Review anything you share on GitHub. Screenshots can reveal a Home label,
city, or installed apps; logs can reveal addresses or other device details.
Remove those details before posting. Do not attach signing keys or passwords.
For sensitive reports, use the [security policy](SECURITY.md).
