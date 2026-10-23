# Football card: design

Date: 2026-10-23 · Status: draft for approval

## Why

The person this is for (a Galo fan in Belo Horizonte, TCL Smart TV Pro, Android 11, running Free My TV from the
fork `reidosbares/free-my-tv`) sits down in front of the TV and wants three answers without googling:

1. Is there anything on today?
2. Where is a given game on (e.g. Flamengo's: "ESPN and Disney+")?
3. When does Galo play next?

Today they google it and land on a ge (globoesporte.com) "onde assistir" page. The card puts ge's answer on the
TV's home screen, and pressing OK on a game opens the app that shows it.

## Goals

- A football card on the Free My TV home screen (Android) answering the three questions above.
- Today's list contains only these games, men's football only (no women's, no youth/sub-N):
  - any game with a current Brasileirão Série A club, in any competition;
  - every Libertadores and Copa Sul-Americana game;
  - every Champions League game;
  - every game of the Brazilian men's national team (Seleção).
- The person's team (Galo by default) always shows its next match, even when it is days away, in the team's own
  colors (taken from ge).
- Each game shows the teams, the kickoff time, the channels, and the score once it starts / when it ends.
- OK on a game opens the app of the first of its channels that is installed on the TV.
- Two placements, switchable on the TV: beside the clock on the home screen (A), or in the bottom drawer in place of
  the prices card (C); plus off.

## Non-goals

- No Cravada: the service does not read Cravada's database, code or API. ge is the only source.
- No hand-entered channel overrides. Channels come only from scraping ge.
- No deep links into the exact match inside a streaming app (ge doesn't give them); OK opens the app's home. If
  Globoplay's live sports section turns out to be reachable by a known intent, opening it is welcome but optional.
- No knowledge of subscriptions: a Premiere game opens Globoplay even without Premiere.
- Not for LG/Samsung (`web/`) in this round. *(Builder's note: CLAUDE.md says design changes on Android belong in
  `web/home.js` too; this is a personal-fork feature on an Android TV, so the web port is deferred. My call.)*
- Not proposed upstream to `simbasjungles/free-my-tv` in this round.

## Anti-goals (would make it a failure even if it "works")

- Showing women's or youth games, or Série B-only games, in today's list.
- Breaking the home screen when ge changes its page or is down. The TV must keep working and show the last good data
  with its age.
- Dropping frames on the TCL (Android 11 is `Ui.LEGACY`): no allocations or network on the UI thread, text cached
  like the existing drawer cards.
- Losing the way back (Original mode, watchdog) or touching anything outside the home screen.

## The pieces

### 1. The service (on the person's VPS)

- Its own small project and its own container on the person's Hostinger VPS (the one that also hosts Cravada),
  sharing nothing with Cravada but the machine and the edge (Cloudflare → Caddy).
- Reads two kinds of ge page (plain HTML, data embedded as JSON; no browser needed):
  - **Agenda** `https://ge.globo.com/agenda/`: about two days back to two days ahead, all sports. Per game:
    `match.firstContestant` / `secondContestant` (`popularName`, `badgePng`, `badgeSvg`, ge team `id`),
    `startDate` + `startHour` (Brasília time), `phase.championshipEdition.championship.name`, `moment`
    (`PAST`/`NOW`/`FUTURE`), `scoreboard` (`home`/`away`, `penalty`), and `liveWatchSources[]` (`name`,
    `officialLogoUrl`, `transmissionId`). Games carry a category ("Masculino" / "Feminino") and the page also lists
    basketball, NFL, F1, volleyball etc. that must be dropped.
  - **Team page** `https://ge.globo.com/futebol/times/<slug>/` (Galo: `atletico-mg`): `externalData.jogos` holds the
    next three games with `data_realizacao`, `hora_realizacao`, `campeonato` (incl. `genero` "M"/"F"),
    `equipe_mandante` / `equipe_visitante` (names, `cores.primaria/secundaria/terciaria`, `escudos`) and
    `transmissao.plataformas[]` (channel `nome`, `logo_oficial`). This covers the team's next match beyond the
    agenda's window, channels included, and gives the team colors. Skip women's games (`genero: "F"`).
- Filters to the Goals list. Men's only = drop women's categories and championships whose name marks women or
  youth ("Feminina"/"Feminino", "Sub-17", "Sub-20", …). The Série A club list must be complete (all 20 clubs), not
  only those playing in the agenda's window: *builder picks the source, e.g. ge's Série A table page; my call.*
  The Seleção = the men's "Brasil" team.
- Drops channels that aren't broadcasts (ge lists "Cartola", a fantasy game, as a source).
- Refresh: about every 30 minutes; every 2–3 minutes while a listed game is `NOW`. Serves the last good result if a
  scrape fails or parses to nothing, with the time it was fetched. Polite to ge: one fetch per page per cycle, a
  normal User-Agent, no parallel hammering. (`robots.txt` allows `/agenda/` and `/futebol/times/` for ordinary
  agents; it blocks only AI crawlers. Globo's terms of use were not reviewed: the person accepted this.)
- Serves one JSON document over HTTPS, read-only, public data, cache headers so Cloudflare absorbs repeats.
- **One team per request:** the TV sends its team (`?team=<ge slug>`); the service accepts only the 20 Série A
  clubs' slugs and the Seleção, and caches each team page like the agenda (one fetch per team per cycle). Anything
  else gets a 400. *(My call: it makes `football_team` on the TV meaningful and keeps the public endpoint bounded.)*
- **Where it runs and how it's reached:** the VPS's Caddy is Cravada's edge (`infra/proxy` in the Cravada repo, one
  container for every site, behind Cloudflare with Authenticated Origin Pulls; see Cravada's `infra/README.md`). The
  service gets a site block there like the others (`import origin_cert`, `cloudflare_only`), on a subdomain of
  cravada.net, e.g. `tv.cravada.net`. The person accepted that this edits Cravada's `infra/` (server config, not app
  code). The DNS record is created by the person in Cloudflare. Deployment over SSH (`thiago@200.141.21.114`)
  happens only with the person's go-ahead at that step; reload Caddy gracefully, never restart Cravada's containers.
  Memory: cap the container at ~256 MB. *(Subdomain name and memory cap are my calls.)*
- Language/stack, repo location (its own repo) and cache: *left to the builder.*

**Response shape** (a sketch; the builder may refine names):

```json
{
  "fetchedAt": "2026-10-24T18:42:00Z",
  "team": {
    "name": "Atlético-MG",
    "colors": ["#000000", "#FFFFFF"],
    "next": { "...one game..." }
  },
  "today": [ { "...game..." } ]
}
```

A game: `id` (ge's), `kickoff` (ISO with offset), `competition`, `home` and `away` (`name`, `crest` URL),
`status` (`scheduled` / `live` / `finished`), `score` (`home`, `away`, optional `penalties`) or null, and
`channels[]` (`name` as ge spells it, `logo` URL). "Today" means the TV's local day (São Paulo time).

### 2. The card on the TV (this repo)

- **Settings** in `config.properties` (same rules as the other keys; generated into `Config`):
  - `football_url`: the service's address. Empty = feature off and invisible.
  - `football_team`: ge team slug, default `atletico-mg`.
- **On the TV**, a "Futebol" row in Home Settings with three values: "Na tela inicial" (A), "Na gaveta" (C),
  "Desligado". Stored in `Prefs` like the other widget toggles; the TV's choice wins over the default (A).
- **Placement A — beside the clock.** Without a TMDB key the home screen shows the clock layout (`drawClock`: date,
  big clock, weather line on the left) and the right half above the shelf is empty. The card fills that area: the
  team card on top, then "Hoje" and today's games, one row each (time, teams, channels), "+N jogos" when they don't
  fit. **With a TMDB key** (`picksOn()` true) the picks need that space, so the card shows in the drawer (C)
  instead, and the Home Settings row reads "Na gaveta" while "Na tela inicial" isn't offered. Removing the key brings
  A back if the stored choice was A. *(Decided by the person.)*
- **Placement C — in the drawer.** The card takes the prices card's slot (`quotesCard`, right of the weather card,
  ~740×300 px in the 1920×1080 space): team card on top, then as many of today's games as fit, then "+N jogos
  hoje". When C is on, the prices card isn't shown (they share the slot).
- **Team card:** only the fixture and the date/time, e.g. "Flamengo x Atlético-MG" / "seg 26/10 · 20:00", plus the
  channels where room allows (A has room, C may not). Background and text in the team's ge colors (Galo: black with
  white, a striped accent). No "Próximo do Galo" or similar heading. While ge has no channels for it yet: "Transmissão
  a confirmar". *(The striped accent is from the mockup; exact treatment is the builder's, within the existing glass
  look.)*
- **Game rows:** live games first with the live score; then upcoming by kickoff; games that ended today stay until
  midnight with the final score. *(Ordering and keep-until-midnight are my call; the person didn't object.)*
- **Empty day:** the team card alone plus one quiet line such as "Nenhum jogo hoje". *(My call.)*
- **Stale data:** if the last good data is older than ~2 hours, a small "atualizado há 3 h" note. *(My call.)*
- **Focus and OK:** the rows are focusable with the remote (like the drawer's inputs and shortcuts; TalkBack reads
  them, as `widgetIndex` does for the cards today). OK on a game or the team card opens the app of the first channel
  (in ge's order) whose app is installed:

  | ge channel(s) | App (package seen on the person's TV) |
  |---|---|
  | sportv, Premiere, Globo, globoplay, Ge TV | Globoplay `com.globo.globotv` |
  | Disney+ (and ESPN) | Disney+ `com.disney.disneyplus` |
  | Prime Vídeo | Prime Video `com.amazon.amazonvideo.livingroom` |
  | HBO Max | Max `com.wbd.stream` |
  | CazéTV, YouTube | YouTube `com.google.android.youtube.tv` |
  | Paramount+ | Paramount+ (not installed here) |

  None installed: a short toast, "Paramount+ não está instalado nesta TV". Unknown channel names (e.g. SportyNet)
  map to nothing.
- **Fetching on the TV:** off the UI thread (as `HomeData` does for weather and prices); every ~5 minutes, every
  ~60 s while a listed game is live; crests downloaded once and cached at the size drawn.
- **Text:** all on-screen strings through `Ui.tr`/`Ui.trf`, added to the catalogues per CLAUDE.md
  (`python3 tools/translate.py`); Portuguese is the reference for this feature. Team and channel names are shown as
  ge spells them, untranslated.
- **Performance:** same rules as the existing drawer cards: no allocations in `onDraw`, text laid out once and cached,
  and on `Ui.LEGACY` the card's content drawn through a `Snapshot` keyed on data, settings, language, day/night and
  the minute.

## Constraints

- Free My TV code rules (CLAUDE.md): Java 8 level, minSdk 26, no libraries, `./build.sh` must end with
  `Built FreeMyTV.apk`, `tools/check.sh` before any push, never commit `config.properties` or the keystore.
- Updates to the person's TV must be signed with the existing `freemytv.keystore` (already in this clone).
- Work happens on `main` of the fork (the person chose not to branch).
- Installing on the TV: the builder can reach it with adb at `192.168.15.8:5555` (already authorized); the person is
  at the TV for anything that needs a tap. Verify on the real TCL (`Ui.LEGACY`), not only an emulator.
- The VPS also runs Cravada in production: the service must not affect it (separate container, modest memory,
  nothing published except through Caddy).

## Decided by the person

- ge only; no Cravada data; no overrides; scrape the web for channels.
- The service runs on the VPS, not on the TV.
- Today's games: Série A clubs (any competition), all Libertadores, Sul-Americana and Champions League, the Seleção;
  men's only.
- Placements A and C, switchable; with a TMDB key, A falls back to the drawer.
- Team card: fixture and date only, no heading, ge's team colors.
- OK opens the app of the broadcasting channel.

## Left to the builder

- The service's language, framework, scraping approach and cache.
- The exact JSON field names (keep the shape above).
- Visual details within the existing Free My TV look (fonts, spacing, glass, night mode).

## Open questions

None left.
