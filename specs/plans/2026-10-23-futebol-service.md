# Futebol service (VPS) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A small service on the VPS that scrapes ge and serves one JSON document with today's wanted games and a team's next match, channels included.

**Architecture:** One Python process, standard library only: a scraper module per ge page, a pure rules module (filters, status, "today"), an in-memory store refreshed by a background thread and persisted to disk as the last good copy, and an `http.server` endpoint. It runs in its own container on the VPS's `web` Docker network, behind Cravada's box-wide Caddy at `tv.cravada.net`.

**Tech Stack:** Python 3.12 in Docker (code stays 3.9-compatible so `python3 -m unittest` runs on the Mac), `unittest`, no third-party packages.

**Spec:** `specs/2026-10-23-football-card-design.md` in `reidosbares/free-my-tv`.

## Global Constraints

- New repo `~/Projetos/futebol-tv`, GitHub `reidosbares/futebol-tv`, **private** (the test fixtures are saved ge pages). Create it on GitHub only with the person's go-ahead.
- ge is the only source. No Cravada data, no hand-entered channels.
- Men's football only: drop every championship whose name matches `/femin|sub-?\d+/i`.
- Wanted games: either side is a current Série A club (by ge team id), OR the championship is `Taça Conmebol Libertadores`, `Copa Sul-Americana` or `Champions League`, OR either side's `popularName` is `Brasil`.
- Drop the channel `Cartola` (a fantasy game, not a broadcast).
- Times are Brasília time: a fixed `-03:00` offset (Brazil has no DST since 2019; avoids needing tzdata in the image). "Today" = the São Paulo calendar day at request time.
- Refresh the agenda about every 30 min; every 150 s while any wanted game is `live`. Série A table once every 24 h. Each team page at most once per agenda cycle, and only after it has been asked for.
- One fetch per page per cycle, sequential, `User-Agent: Mozilla/5.0 (compatible; futebol-tv; +https://tv.cravada.net)`, 10 s timeout.
- A failed or empty scrape never replaces good data. The last good state is written to `$STATE_DIR/state.json` (default `/data`) and loaded at start.
- `?team=` accepts only the slugs of the 20 current Série A clubs (from ge's Série A page); anything else → HTTP 400 `{"error":"unknown team"}`. **Deviation from the spec:** the Seleção can't be a team: ge's Seleção page has no upcoming games (it still shows the 2026 World Cup final). Its games still appear in "today".
- Container memory limit `256m`. Never restart Cravada's containers; Caddy changes are a graceful `caddy reload`.

## Review Focus

- ge changes a field name or the page fails to load → the endpoint keeps serving the last good copy with its old `fetchedAt`, it never sends a 500 or an empty list. Test in Task 4.
- A game that runs past midnight (21:30 kickoff, live at 00:10) → still in "today" while live. Test in Task 2.
- The team's next game is today → it shows in `team.next` with the agenda's live score, and isn't listed twice inside `team`. Test in Task 4.
- The team page's next entries include a women's game (Galo's `Copa do Brasil F` on 1 Nov) → skipped. Test in Task 3.
- The TV's clock or the VPS's clock is behind UTC−3 midnight → "today" is computed on the service's clock in `-03:00` only. Test in Task 2.

---

### Task 1: Repo, fixtures and the agenda scraper

**Files:**
- Create: `~/Projetos/futebol-tv/futebol/__init__.py` (empty), `futebol/ge_agenda.py`, `tests/test_ge_agenda.py`
- Create: `tests/fixtures/agenda.html`, `tests/fixtures/serie-a.html`, `tests/fixtures/team-atletico-mg.html` (saved with `curl -s --compressed -A "Mozilla/5.0" <url> -o <file>` from `https://ge.globo.com/agenda/`, `https://ge.globo.com/futebol/brasileirao-serie-a/`, `https://ge.globo.com/futebol/times/atletico-mg/`)
- Create: `.gitignore` (`__pycache__/`, `state.json`)

**Interfaces:**
- Produces: `Game` dataclass in `futebol/model.py`: `id: int`, `kickoff: datetime` (aware, `-03:00`), `competition: str`, `home: Side`, `away: Side`, `status: str` (`"scheduled" | "live" | "finished"`), `score: Optional[Score]`, `channels: list[Channel]`. `Side(id: int, name: str, crest: str)`, `Score(home: int, away: int, penalties: Optional[tuple[int, int]])`, `Channel(name: str, logo: str)`. Plus `to_json(game) -> dict` with the field names of the spec's response sketch.
- Produces: `parse_agenda(html: str) -> list[Game]` in `futebol/ge_agenda.py`.

How the page holds the data (verified 2026-10-23): a `<script>` sets `window.dataSportsSchedule = { sport: {...}, multisport: ..., ... }`. The value after `sport:` is strict JSON (decode it with `json.JSONDecoder().raw_decode` from the first `{`). `sport` is football only: `{ "<YYYY-MM-DD>": { "championshipsAgenda": [ { "championship": {"name": ...}, "past": [...], "now": [...], "future": [...] } ] } }`. Each event has `match` with `id`, `firstContestant` / `secondContestant` (`id`, `popularName`, `badgePng`), `startDate`, `startHour` (`"21:30:00"`), `moment` (`PAST`/`NOW`/`FUTURE`), `scoreboard` (`home`, `away`, `penalty` or null) and `liveWatchSources[]` (`name`, `officialLogoUrl`). `firstContestant` is the home side.

- [ ] **Step 1: Save the three fixtures and write the failing tests**

```python
class ParseAgendaTest(unittest.TestCase):
    def setUp(self):
        self.games = parse_agenda(read_fixture("agenda.html"))

    def test_finds_games_on_several_days(self):
        days = {g.kickoff.date() for g in self.games}
        self.assertGreaterEqual(len(days), 4)

    def test_cruzeiro_remo_has_its_channels_and_time(self):
        g = next(g for g in self.games if g.home.name == "Cruzeiro" and g.away.name == "Remo")
        self.assertEqual(g.kickoff.isoformat(), "2026-10-23T20:00:00-03:00")
        self.assertEqual([c.name for c in g.channels], ["sportv", "Premiere", "globoplay"])
        self.assertEqual(g.competition, "Campeonato Brasileiro")

    def test_past_game_is_finished_with_penalties(self):
        g = next(g for g in self.games if g.home.name == "Palmeiras" and g.away.name == "Fluminense")
        self.assertEqual(g.status, "finished")
        self.assertEqual((g.score.home, g.score.away, g.score.penalties), (0, 1, (5, 3)))

    def test_garbage_page_gives_empty_list(self):
        self.assertEqual(parse_agenda("<html>nothing</html>"), [])
```

The expected values match the fixture saved on 2026-10-23. If the fixture is re-saved on another day, pick games from it and update the values: the tests pin parsing, not that day's schedule.

- [ ] **Step 2: Run and see it fail**

Run: `cd ~/Projetos/futebol-tv && python3 -m unittest -v tests.test_ge_agenda`
Expected: ERROR, `No module named 'futebol.ge_agenda'`

- [ ] **Step 3: Implement `futebol/model.py` and `parse_agenda`**

Status: `NOW` → `live`, `PAST` → `finished`, else `scheduled`. Score only when `scoreboard` has both numbers. Never raise on a malformed page; return what parsed.

- [ ] **Step 4: Run, expect PASS**

Run: `python3 -m unittest -v tests.test_ge_agenda`

- [ ] **Step 5: Commit** (`git init` first; local only, no remote yet)

```bash
git add . && git commit -m "Parse ge's agenda into games"
```

### Task 2: Rules: wanted games and "today"

**Files:**
- Create: `futebol/rules.py`, `tests/test_rules.py`

**Interfaces:**
- Consumes: `Game`, `Side`, `Channel` (Task 1).
- Produces: `BRT = timezone(timedelta(hours=-3))`; `is_mens(competition: str) -> bool`; `is_wanted(game: Game, serie_a_ids: set[int]) -> bool`; `clean_channels(channels: list[Channel]) -> list[Channel]`; `today(games: list[Game], serie_a_ids: set[int], now: datetime) -> list[Game]`.

`today` keeps wanted men's games whose kickoff falls on `now`'s BRT date, plus any wanted game that is `live` whatever its date. Order: live first, then by kickoff, then by id. Channels already cleaned.

- [ ] **Step 1: Write the failing tests** (build `Game`s inline with a small `game(...)` helper; ids 282 = Atlético-MG, 262 = Flamengo)

```python
def test_womens_and_youth_are_not_mens(self):
    for name in ["Copa do Brasil Feminina", "Taça Libertadores Feminina", "Copa do Mundo Feminina Sub-17",
                 "Copa do Brasil Sub-20", "Campeonato Brasileiro sub-17"]:
        self.assertFalse(is_mens(name), name)
    self.assertTrue(is_mens("Campeonato Brasileiro"))

def test_wanted(self):
    a = {282, 262}
    self.assertTrue(is_wanted(game(home=282, away=999, comp="Copa do Brasil"), a))
    self.assertTrue(is_wanted(game(home=1, away=2, comp="Copa Sul-Americana"), a))
    self.assertTrue(is_wanted(game(home=1, away=2, comp="Champions League"), a))
    self.assertTrue(is_wanted(game(home_name="Japão", away_name="Brasil", comp="Amistoso"), a))
    self.assertFalse(is_wanted(game(home=1, away=2, comp="Campeonato Brasileiro Série B"), a))
    self.assertFalse(is_wanted(game(home_name="Gana", away_name="Brasil", comp="Copa do Mundo Feminina Sub-17"), a))
    self.assertFalse(is_wanted(game(home=282, away=2, comp="Copa do Brasil Feminina"), a))

def test_cartola_is_not_a_channel(self):
    out = clean_channels([Channel("Premiere", ""), Channel("Cartola", ""), Channel("Globo", "")])
    self.assertEqual([c.name for c in out], ["Premiere", "Globo"])

def test_today_orders_live_first_then_kickoff(self):
    now = datetime(2026, 10, 24, 17, 0, tzinfo=BRT)
    g1 = game(id=1, home=282, at="2026-10-24T18:30", status="scheduled")
    g2 = game(id=2, home=282, at="2026-10-24T16:30", status="live")
    g3 = game(id=3, home=282, at="2026-10-24T16:30", status="finished")
    g4 = game(id=4, home=282, at="2026-10-25T11:00", status="scheduled")
    self.assertEqual([g.id for g in today([g1, g2, g3, g4], {282}, now)], [2, 3, 1])

def test_live_game_past_midnight_stays(self):
    now = datetime(2026, 10, 25, 0, 10, tzinfo=BRT)
    late = game(id=9, home=282, at="2026-10-24T21:30", status="live")
    self.assertEqual([g.id for g in today([late], {282}, now)], [9])

def test_today_uses_brasilia_date_not_utc(self):
    now = datetime(2026, 10, 25, 1, 30, tzinfo=timezone.utc)   # 22:30 on the 24th in BRT
    g = game(id=5, home=282, at="2026-10-24T21:00", status="finished")
    self.assertEqual([x.id for x in today([g], {282}, now)], [5])
```

- [ ] **Step 2: Run, expect FAIL** — `python3 -m unittest -v tests.test_rules` → `No module named 'futebol.rules'`
- [ ] **Step 3: Implement `futebol/rules.py`** with the signatures above.
- [ ] **Step 4: Run, expect PASS**
- [ ] **Step 5: Commit** — `git commit -am "Rules for wanted games and today"` (add the new files first)

### Task 3: Série A table and team page scrapers

**Files:**
- Create: `futebol/ge_teams.py`, `tests/test_ge_teams.py`

**Interfaces:**
- Consumes: `Game`, `Side`, `Score`, `Channel`, `BRT`, `is_mens`.
- Produces:
  - `Club` dataclass: `id: int`, `slug: str`, `name: str`, `url: str`.
  - `parse_serie_a(html: str) -> list[Club]`: the 20 clubs.
  - `TeamPage` dataclass: `team_id: int`, `name: str`, `colors: list[str]`, `next_games: list[Game]` (men's only, not finished, by kickoff).
  - `parse_team_page(html: str, team_id: int) -> Optional[TeamPage]`.

Where the data is (verified 2026-10-23):
- Série A page: `"classificacao":[{..., "equipe_id": 262, "nome_popular": "Flamengo", ...}, ...]` (20 entries; ids match the agenda's contestant ids). Slugs and page URLs come from the crest links on the same page: `<a href="https://ge.globo.com/.../times/<slug>/" title="..." class="mosaico__escudo" data-id-equipe-sde="<id>" data-slug-equipe-sde="<slug>">`; keep only ids in `classificacao`. Note the regional prefixes (`/rs/futebol/times/internacional/`).
- Team page: `"jogos":[...]` (next three games), decode with `raw_decode` after `"jogos":`. Each has `jogo_id`, `data_realizacao`, `hora_realizacao`, `campeonato.nome` and `campeonato.genero` (`"M"`/`"F"`), `equipe_mandante` / `equipe_visitante` (`equipe_id`, `nome_popular`, `escudos["60x60"]` or the largest PNG, `cores.primaria/secundaria`), `jogo_ja_comecou`, `placar_oficial_*`, `placar_penaltis_*`, `transmissao.plataformas[]` (`nome`, `logo_oficial`). The team's colors are the `cores` of whichever side has `equipe_id == team_id`.

- [ ] **Step 1: Write the failing tests**

```python
def test_serie_a_has_twenty_clubs_with_pages(self):
    clubs = parse_serie_a(read_fixture("serie-a.html"))
    self.assertEqual(len(clubs), 20)
    galo = next(c for c in clubs if c.slug == "atletico-mg")
    self.assertEqual((galo.id, galo.url), (282, "https://ge.globo.com/futebol/times/atletico-mg/"))
    inter = next(c for c in clubs if c.slug == "internacional")
    self.assertEqual(inter.url, "https://ge.globo.com/rs/futebol/times/internacional/")

def test_galo_page_next_games_men_only(self):
    page = parse_team_page(read_fixture("team-atletico-mg.html"), 282)
    self.assertEqual(page.colors[:2], ["#000000", "#ffffff"])
    self.assertEqual([(g.home.name, g.away.name) for g in page.next_games],
                     [("Flamengo", "Atlético-MG"), ("Atlético-MG", "Cruzeiro")])   # Grêmio x Galo (F) dropped
    first = page.next_games[0]
    self.assertEqual(first.kickoff.isoformat(), "2026-10-26T20:00:00-03:00")
    self.assertEqual([c.name for c in first.channels], ["sportv", "Premiere", "globoplay", "Cartola"])

def test_broken_team_page_is_none(self):
    self.assertIsNone(parse_team_page("<html></html>", 282))
```

(`Cartola` is still there: cleaning happens when serving, Task 4.)

- [ ] **Step 2: Run, expect FAIL** — `python3 -m unittest -v tests.test_ge_teams`
- [ ] **Step 3: Implement `futebol/ge_teams.py`.** Status from the team page: finished if `jogo_ja_comecou` and both scores set and the kickoff is more than 3 h ago; live if `jogo_ja_comecou`; else scheduled (the agenda's status wins when the game is in both, Task 4).
- [ ] **Step 4: Run, expect PASS**
- [ ] **Step 5: Commit** — `git add futebol tests && git commit -m "Parse Série A clubs and team pages"`

### Task 4: Store, refresher and the response

**Files:**
- Create: `futebol/store.py`, `tests/test_store.py`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `class Store(fetch: Callable[[str], str], state_dir: str, clock: Callable[[], datetime])`.
  - `Store.refresh_agenda() -> None`, `Store.refresh_clubs() -> None`, `Store.refresh_team(slug: str) -> None`. Each fetches, parses, and replaces stored data only when the parse gives a non-empty result; it never raises (a failure is logged and keeps the old data).
  - `Store.response(slug: str) -> dict`: `{"fetchedAt": <ISO UTC of the last good agenda>, "team": {"slug", "name", "colors", "next": game|None}, "today": [game...]}`. Raises `UnknownTeam` for a slug not in the clubs.
  - `Store.next_delay() -> float`: `150` if any game in `today` is live, else `1800`.
  - `Store.save()` / `Store.load()`: JSON in `state_dir/state.json` with the raw parsed data needed to rebuild a response.

`team.next` = the first of the team page's `next_games` that hasn't finished; if the same `id` is in the agenda, use the agenda's copy (fresher status and score). Channels pass through `clean_channels` in the response.

- [ ] **Step 1: Write the failing tests** (a fake `fetch` returning fixture pages by URL, or raising `OSError`; `clock` fixed at `2026-10-23T15:00-03:00`; `state_dir` from `tempfile.mkdtemp()`)

```python
def test_response_for_galo(self):
    s = store_with_fixtures()
    r = s.response("atletico-mg")
    self.assertEqual(r["team"]["name"], "Atlético-MG")
    self.assertEqual(r["team"]["next"]["home"]["name"], "Flamengo")
    self.assertNotIn("Cartola", [c["name"] for c in r["team"]["next"]["channels"]])
    self.assertIn(("Cruzeiro", "Remo"), [(g["home"]["name"], g["away"]["name"]) for g in r["today"]])

def test_failed_refresh_keeps_last_good(self):
    s = store_with_fixtures()
    before = s.response("atletico-mg")
    s.fetch = failing_fetch
    s.refresh_agenda(); s.refresh_team("atletico-mg")
    self.assertEqual(s.response("atletico-mg"), before)

def test_empty_parse_keeps_last_good(self):
    s = store_with_fixtures()
    before = s.response("atletico-mg")["today"]
    s.fetch = lambda url: "<html>changed layout</html>"
    s.refresh_agenda()
    self.assertEqual(s.response("atletico-mg")["today"], before)

def test_unknown_team(self):
    with self.assertRaises(UnknownTeam):
        store_with_fixtures().response("brasil")

def test_survives_restart(self):
    s = store_with_fixtures(); s.save()
    t = Store(fetch=failing_fetch, state_dir=s.state_dir, clock=s.clock); t.load()
    self.assertEqual(t.response("atletico-mg"), s.response("atletico-mg"))

def test_team_next_today_uses_agenda_copy(self):
    s = store_with_fixtures()
    nxt = s.team_pages["atletico-mg"].next_games[0]
    live = dataclasses.replace(nxt, status="live", score=Score(1, 0, None))
    s.set_agenda(s.agenda + [live])          # the same setter refresh_agenda uses
    r = s.response("atletico-mg")
    self.assertEqual(r["team"]["next"]["status"], "live")
    self.assertEqual((r["team"]["next"]["score"]["home"], r["team"]["next"]["score"]["away"]), (1, 0))
```

(So `Store` also exposes `agenda: list[Game]`, `team_pages: dict[str, TeamPage]` and `set_agenda(games: list[Game]) -> None`.)

- [ ] **Step 2: Run, expect FAIL** — `python3 -m unittest -v tests.test_store`
- [ ] **Step 3: Implement `futebol/store.py`.** Write `state.json` atomically (temp file + `os.replace`).
- [ ] **Step 4: Run all tests, expect PASS** — `python3 -m unittest -v`
- [ ] **Step 5: Commit** — `git add futebol tests && git commit -m "Store with last-good data and the response"`

### Task 5: HTTP server and the container

**Files:**
- Create: `futebol/server.py`, `tests/test_server.py`, `Dockerfile`, `compose.yaml`, `README.md`

**Interfaces:**
- Consumes: `Store` (Task 4).
- Produces: `make_handler(store: Store) -> type[BaseHTTPRequestHandler]`; `main()` in `futebol/server.py` (`python -m futebol.server`), listening on `0.0.0.0:8080`.

Routes: `GET /futebol?team=<slug>` → 200 JSON (`Content-Type: application/json; charset=utf-8`, `Cache-Control: public, max-age=60`), unknown team → 400 `{"error":"unknown team"}`, missing team → 400; `GET /health` → 200 `ok`; anything else → 404. A slug asked for the first time triggers `store.refresh_team` once before answering (later refreshes happen in the background loop). `main()` loads state, refreshes clubs then agenda, starts one daemon thread looping `refresh_agenda`, the requested teams, `refresh_clubs` when 24 h old, `save()`, then `sleep(store.next_delay())`; serves with `ThreadingHTTPServer`.

- [ ] **Step 1: Write the failing tests** (start the server on port 0 in a thread with a fixture-backed store; request with `urllib.request`)

```python
def test_futebol_ok(self):
    status, headers, body = get("/futebol?team=atletico-mg")
    self.assertEqual(status, 200)
    self.assertEqual(headers["Cache-Control"], "public, max-age=60")
    self.assertEqual(json.loads(body)["team"]["slug"], "atletico-mg")

def test_unknown_and_missing_team(self):
    self.assertEqual(get("/futebol?team=brasil")[0], 400)
    self.assertEqual(get("/futebol")[0], 400)

def test_health_and_404(self):
    self.assertEqual(get("/health")[0], 200)
    self.assertEqual(get("/")[0], 404)
```

- [ ] **Step 2: Run, expect FAIL** — `python3 -m unittest -v tests.test_server`
- [ ] **Step 3: Implement `futebol/server.py`**, then the `Dockerfile` (`FROM python:3.12-slim`, copy `futebol/`, `ENV STATE_DIR=/data`, non-root user, `CMD ["python","-m","futebol.server"]`) and `compose.yaml`: service `futebol-tv`, `restart: unless-stopped`, `mem_limit: 256m`, volume `futebol_data:/data`, network `web` (external) with alias `futebol-tv`, no published ports. `README.md`: what it is, the endpoint, how to run tests, how to deploy (Task 6).
- [ ] **Step 4: Run all tests and a local container smoke test**

Run: `python3 -m unittest -v` → all PASS.
Run: `docker build -t futebol-tv . && docker run --rm -d -p 8080:8080 --name ftv futebol-tv && sleep 20 && curl -s 'localhost:8080/futebol?team=atletico-mg' | head -c 400; docker stop ftv`
Expected: JSON with `"team"` and `"today"` from live ge data.

- [ ] **Step 5: Commit** — `git add . && git commit -m "HTTP endpoint and container"`

### Task 6: Publish and deploy (needs the person at each outward step)

**Files:**
- Modify (Cravada repo): `~/Projetos/cravada/infra/proxy/Caddyfile` — add a site block.

- [ ] **Step 1: GitHub repo.** Ask the person, then: `GH_TOKEN=$(gh auth token --user reidosbares) gh repo create reidosbares/futebol-tv --private --source . --push` (remote `https://reidosbares@github.com/reidosbares/futebol-tv.git`).
- [ ] **Step 2: Caddy site block** in the Cravada Caddyfile, after `umami.cravada.net`, following the other blocks:

```
tv.cravada.net {
	import origin_cert
	route {
		import cloudflare_only
		encode zstd gzip
		reverse_proxy futebol-tv:8080
	}
}
```

Check first that the Origin Certificate covers `*.cravada.net` (Cravada `infra/README.md`, Analytics step 1). Commit in Cravada on a branch and show the person the diff. **Do not push Cravada's `main` yourself** — a push deploys production; the person decides when.
- [ ] **Step 3: DNS.** The person adds `tv` → `200.141.21.114`, proxied, in Cloudflare.
- [ ] **Step 4: On the VPS, with the person's go-ahead:** `ssh thiago@200.141.21.114`, clone into `/srv/futebol-tv` (read-only deploy key, as Cravada's runbook describes), `docker compose up -d --build`. After the Caddyfile is deployed: `docker compose -f /srv/cravada/infra/proxy/compose.yaml exec caddy caddy reload --config /etc/caddy/Caddyfile`.
- [ ] **Step 5: Verify.** `curl -s 'https://tv.cravada.net/futebol?team=atletico-mg' | head -c 300` returns JSON; `https://www.cravada.net` still answers 200; `docker stats --no-stream futebol-tv` shows under 256 MB.
