# User stories

Inbox for problems and wishes Novica reports while using the app day to day ("žalim se"). Claude writes them
down; implementation happens later, when agents group stories into features.

## Rules

- **One file per story**: `YYYY-MM-DD-<slug>.md` (date the problem was reported, slug in English kebab-case).
- Writing a story is **not** a request to implement it. Record it, commit it, stop.
- Write the story in Serbian (Novica's words), the technical notes in English.
- Record evidence while it is fresh: times, boiler state from the history server (`boiler_*` MCP tools),
  relevant code paths. Mark guesses as guesses.
- Do not delete stories. When a feature ships, set `status: done` and link the PR.

## Format

```markdown
---
id: 2026-10-29-boost-cancel
reported: 2026-10-29
status: new          # new | grouped | in-progress | done | wontfix
feature: null        # slug of the feature in ../features/, filled in when grouped
kind: ux             # ux | bug | wish
---

# <Kratak naslov>

**Kao** <ko>, **želim** <šta>, **da bih** <zašto>.

## Šta se desilo
## Prihvatni kriterijumi
## Tehničke beleške
```

## Grouping into features

When asked to plan work, an agent reads every story with `status: new`, groups related ones into
`../features/<slug>.md` (problem, stories covered, proposed scope, open questions) and sets `status: grouped`
plus `feature: <slug>` in each story. Features are then designed and implemented one PR at a time.
