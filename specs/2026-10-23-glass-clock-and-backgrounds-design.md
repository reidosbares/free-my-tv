# Glass clock and photo backgrounds: design

Date: 2026-10-23 · Status: draft for approval

## Why

The person this is for runs Free My TV on their own TCL TV, with no TMDB key and no plan to ever add one. Without
picks, the home screen's main element is the clock, drawn in Inter's thinnest weight over a plain light gradient.
Next to the Apple TV–style glass shelf it reads as bare and "default": the one thing on screen that doesn't look like
it belongs.

They want two things, and the second is what makes the first work:

1. **The clock of the iOS 26 lock screen:** bold numbers made of Liquid Glass. Calmer than full Liquid Glass, and
   frosted.
2. **A real background:** high-quality photos from Unsplash, in themes they choose, rotating, with a way to keep one
   they like. Glass needs something behind it to bend and frost; over a flat gradient the glass clock would be a faint
   gray outline.

Success: they turn on the TV and the home screen looks like an iPhone lock screen on a big screen: a beautiful photo,
a bold frosted-glass clock, and the glass shelf, all of one piece.

## The one TV it's for

A personal project: build and tune for **their TCL Smart TV Pro (G05, Android 11, 1080p UI, at 192.168.15.8)**
and nothing else. No work for other brands, other Android versions, LG or Samsung (`web/`), or the release builds.
Anything that would only matter elsewhere is out. *(Builder's note: the existing code paths for other TVs must still
compile and `tools/check.sh` must still pass; they just get no new work.)*

What Android 11 means here (`Ui.LEGACY`, SDK < 31): **no AGSL** (`RuntimeShader` is Android 13+), no GPU layers on
the home screen, shadows from cached bitmaps, images rounded with a BitmapShader. Today on this TV the shelf glass is
the plain translucent fallback, the screensaver without photos is a static gradient (the aurora is AGSL), and
`DreamView`'s glass clock never runs.

So **the glass clock can't be a shader on this TV.** It is made on the CPU instead: whenever the minute, the
background or the day/night/drawer state changes, a worker thread renders the clock as glass into a bitmap (the
digits' mask and edge slopes, the blurred background under the clock's box bent by those slopes, lifted toward white,
plus the rim, the highlight and the shadow; the same math as `Shaders.GLASS_TEXT`), and the UI thread only draws that
bitmap. The clock's box is about 700 × 300 px, so this is a fraction of a second of work once a minute, never per
frame. During a background crossfade, the old and new clock bitmaps crossfade too. *(My call on the technique;
they asked for the look, not the method.)*

## Goals

### The clock

- The home screen's big clock (the one shown when there are no picks) becomes **bold Liquid Glass**, matching the
  mockup they chose ("C2 + more frost"):
  - Inter Bold (weight 700), bigger than today: about 250 px in the 1920×1080 layout (today 176 / 200), with tight
    letter spacing (about −3% of the size).
  - **Gentle bend:** at the digits' edges the background is refracted by about half as much as the screensaver's
    glass clock does today.
  - **Clearly frosted inside:** the background's colors come through the digits, its details don't. Read from a
    heavily blurred copy of the background (`Backdrop` already builds one: `frost` / `glass`), then lifted toward
    white (roughly `bg × 0.72 + 0.25`).
  - A soft highlight on the edges facing the top-left light, a thin bright rim around each digit, and a soft drop
    shadow under the digits so they lift off the photo.
- The date above the clock is already semibold (`Ui.semibold`); it and the weather line stay as they are.
- **The screensaver's clock becomes the same bold frosted glass** (their choice). On their TV the screensaver has
  no photos (no TMDB key): it draws a thin solid-white clock (`Ui.thin`, 280) over a static gradient. That clock is
  made the same CPU way, reading a blurred copy of the gradient. The gradient itself stays. (On Android 11 the
  screensaver runs as `ScreensaverAlone`, in the main process.)
- **Night:** the glass reads the darkened background the night look uses (smoked glass).

The reference mockups (browser approximations with SVG filters) are in
`.superpowers/brainstorm/87515-1792785292/content/`: `glass-frost.html`, option "C2 + more frost".

### The backgrounds

- When there are no picks, the home screen's background is a photo from **Unsplash** instead of the plain gradient.
  When there are picks (a TMDB key), the pick's photo takes over exactly as today.
- **A new optional key in `config.properties`: `unsplash_key`** (Unsplash's free "Access Key"). Same rules as the
  TMDB key in CLAUDE.md: the person creates it and pastes it in themselves; never asked for in chat, never printed,
  never committed. Added to `config.example.properties`, the README's settings table and a short "The Unsplash key"
  section, and to the generated `Config.java` (`UNSPLASH_KEY`). Without it, everything works as today (the gradient).
- **Five themes:** Nature, Aerials, Abstract, Architecture, Travel. Each is a set of Unsplash photos in landscape
  orientation. Nature, Travel and Architecture map to Unsplash's curated topics; Aerials to a search (aerial / drone
  photography); Abstract to Unsplash's abstract photos (fluid art, gradients, 3D renders). The exact topics and
  queries are the builder's to pick and check against the API; the bar is "every photo looks like a wallpaper."
- **Three new rows in Home Settings** (after Theme):
  - **Background:** Nature · Aerials · Abstract · Architecture · Travel · Plain (today's gradient).
  - **Change background:** Every hour · Every day.
  - **Keep this background:** Off / On. On keeps the photo that's on the home screen now and stops the rotation; Off
    goes back to rotating. Changing the theme turns it Off.
  - **New background now:** an action row (OK on it): the next photo goes up right away, for when they don't like the
    one showing. With Keep On, the new photo becomes the kept one. *(It's in Home Settings, next to the other rows:
    my call, since that's where they chose to pin.)* The next-next photo is then fetched in the background, so a
    second press is instant too.
  - Home Settings shows a small preview of the current photo with its photographer's name, so they see what they're
    keeping.
  - Defaults the first time there's a key: Nature, Every hour, Off.
- **Rotation:** a new photo only appears when they come back to the home screen (Home pressed, an app closed, the TV
  turned on) and the interval has passed; it never changes while they're looking at it. It fades in with the same
  crossfade the pick photos use (`bgFrom` / `bgTo` in `HomeView`).
- **Never waits on the internet:** the current photo and the next one are kept on the TV (app storage), already sized
  for the screen. The next one is fetched in the background after a photo goes up. No internet, or Unsplash failing:
  the current photo stays. No photo yet at all (first start, no network): the plain gradient.
- **Sized for the TV:** photos are requested at the UI's size (1920×1080; 1280×720 on TVs that draw their UI at
  720p, as `Backdrop` already does) and built into a `Backdrop` like a pick's photo, so the shelf glass, the drawer
  and the clock all read from it the same way.
- **The photo is shown sharp, and the home screen takes a look made for it** (their choice, mockup `day-look.html`
  option B). This is the biggest part of the work: today the home screen always uses its light look
  (`recs.setLight(true)`: dark `L_INK` text, white glass, the background photo blurred and washed almost to white in
  `Backdrop.lightImage`). With an Unsplash photo up:
  - By day the photo is shown as it is, full screen, like an iPhone lock screen. All text on the home screen is
    white with the soft shadow: date, clock, weather, tabs, the names under the top buttons, the drawer, the credit.
  - The shelf, the top buttons and the drawer use clear glass over the photo (the night look's glass, lighter),
    not today's white glass.
  - **Readable on bright photos:** the left side is darkened behind the date, clock and weather, stronger when the
    photo is bright there. `Backdrop.make(forLight == false)` has this logic from the old dark design; it doesn't
    build the day and night glass versions the current UI reads, so a photo backdrop needs its own variant.
  - **At night** (and with Theme set to Dark): the same sharp photo, darkened, with the smoked glass the night look
    uses. *(My call; Theme keeps its meaning: Light = the photo as it is all day and night, Dark = always darkened,
    Automatic = darkened from sunset to sunrise.)*
  - Opening the drawer frosts the photo (blurred, a little darker) instead of lightening it. *(My call: lightening
    would put white text on white.)*
  - With **Plain** (or no key, or no photo yet), the home screen is exactly today's light look. Picks, if a key is
    ever added, also keep today's look.
- **Unsplash's rules** (API guidelines), all required:
  - The photographer is credited on the home screen: small, quiet text in a corner ("Photo: Name, Unsplash",
    translated as a pattern, `Ui.trf`).
  - Photos are shown from Unsplash's own image URLs (`urls.raw` with size parameters), not re-hosted. Saving the
    current and next file locally for the TV is a cache of those URLs.
  - When a photo goes on screen, the app calls its `links.download_location` once (Unsplash counts that as a use).
  - Their free "demo" key allows 50 requests an hour; the app needs one or two an hour. The builder should keep it
    far under that.
- All new on-screen text (row names, values, the credit pattern) through `Ui.tr` / `Ui.trf`, then
  `python3 tools/translate.py`, with Portuguese, Spanish (and the other catalogues the build ships) filled in.

## Non-goals

- No browsing or searching for a specific photo on the TV. Themes only, plus "keep this one".
- No pin button on the home screen itself; pinning is in Home Settings.
- No backgrounds in the screensaver this round (it keeps its aurora or pick photos). The screensaver only gets the
  new clock.
- No app-drawn abstract backgrounds (the aurora shader): Abstract is Unsplash photos.
- No LG / Samsung (`web/`), no AGSL versions, no other TVs (see "The one TV it's for"). *(CLAUDE.md asks for design
  changes to reach `web/home.js` too; skipped on purpose: their call, a personal project.)*
- The small clock in the top bar (shown when there are picks) stays as it is.
- Not for a release build: the generic builds have no keys, so nothing changes there.

## Anti-goals (it fails even if it works)

- **The clock looks cheap or busy:** wobbly, rainbow-edged, or hard to read. They asked for less liquid than full
  Liquid Glass; when in doubt, calmer.
- **The home screen stutters.** It must stay at 60 fps on a weak TV GPU: no allocations in `onDraw`, no work on the UI
  thread, and the clock's glass masks built once per minute (or per size change), not per frame. Decoding and
  downloading photos happens on a worker, as with picks.
- **The home screen waits on, or breaks without, the internet or the key.**
- **Text disappears on a bright photo**, anywhere on the home screen (tabs and button names too, not only the clock).
- **The Unsplash key leaks:** printed in logs, committed, or put in a URL that ends up in a log.
- Memory growing on 1 GB TVs: only the current and next photo are held, at the UI's size.

## Constraints

From CLAUDE.md and the code as it is:

- Java 8, minSdk 26, no libraries, built by `build.sh`; `./build.sh` must end with `Built FreeMyTV.apk` and
  `tools/check.sh` must pass.
- Android 11 on the target TV: no AGSL, and nothing newer than API 30 at runtime (compile-check against
  `android-26` as CLAUDE.md says).
- The home screen is in the `:home` process; Home Settings is `SettingsActivity`. What Home Settings changes lives in
  `Prefs` (read from `:home`). "Keep this background" needs to know which photo is on screen now, so the current photo
  (its id, file and photographer) must be stored where both can read it.
- The clock today: `clockPaint` (`Ui.thin`, 176) and `lClock` (`Ui.light`, 200) in `HomeView`, drawn in `drawClock`;
  Inter is the bundled variable font (`Ui.font(a, opticalSize, weight)`), so weight 700 is one more `Typeface`.
- `DreamView` already builds the digits' `slope` and `cover` bitmaps for `Shaders.GLASS_TEXT`; the CPU glass clock
  should reuse that mask-building code (shared by the home screen and the screensaver) rather than write it twice.

## Left to the builder

- How the glass clock is wired into `HomeView`'s layers, and how its bitmaps are made, cached and recycled.
- The exact glass numbers, tuned on the TV against the mockup.
- The exact Unsplash topics and queries per theme, and the request details.
- How the current/next photo files and their metadata are stored, and how `:home` learns that a setting changed.
- Where the credit sits and how big it is (it must not fight with the shelf or the top buttons).
- How the Home Settings preview is drawn.
