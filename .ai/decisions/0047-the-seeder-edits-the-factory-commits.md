# ADR-0047: The defect-seeder edits the seed run's change and nothing else, and a factory script makes the commit; the hooks read the run from `.ai/runs/SEED`

- **Status:** Accepted (2026-10-30)
- **Backlog:** #0-172
- **Date:** 2026-10-30
- **Author:** human (owner chose option C of the research; drafted by Claude Code in the owner's session)
- **Reversible:** yes. The two hooks and the record come out, and `seed-check.sh`/`seed-fold.sh` take a commit again.

## Context

`defect-seeder` had Bash and Edit and no guard in either layer of ADR-0041: no hook in its frontmatter and no
`--agent defect-seeder` twin in the settings files. Every other agent that writes or runs Bash has one.
`/seed-bugs` runs in the owner's interactive session, which loads `.claude/settings.json` only, so the autopilot's
denies and `guard-write-in-repo.sh` did not apply. The seeder's writes were held by nothing but that session's
permission mode. `scripts/factory/seed-check.sh` looked only afterwards, at `git status`, so it could not see:

- a hook or a rule file the seeder changed before the check ran, which the session reads from disk;
- a row written to the gitignored `.ai/runs/benchmark/results.md` (#0-177's Also);
- the calls a seeder made on another branch between a `git switch` and the check. The ban on switching was only a
  sentence in its prompt (#0-173's Also).

Migrations did not fit either. `guard-migrations.sh` compares with `origin/main`, and every migration of a merged
change is there. P-19 (`mig-after-p19`) failed: the seeder's Edit of V31 was refused. P-13 measured only because the
seeder appended to V8 with `printf … >>`, which no hook watches. Which patterns could be measured depended on the
tool the seeder picked.

The seeder's commit carried nothing of its own. `seed-fold.sh` folded it into the change's commit under the change's
subject (#0-177), so its message was thrown away.

## Decision

1. **The seeder commits nothing.** Its Bash runs the plain `readonly-bash.sh`, the reviewers' guard: read-only git,
   with no branch, no commit, no redirect and no chaining. It is in the frontmatter and, as a twin, on the reviewers'
   `--agent` list in both settings files. The seeder leaves its edit uncommitted.
2. **A run's record.** `seed-branch.sh` writes `.ai/runs/SEED` (`<branch> <base> <head>`), and `return-to-main.sh`
   removes it once the tree is back on `main`. `seed_record` (`.claude/hooks/_lib.sh`) reads the record only when
   HEAD is on the `review/<8 hex>` branch it names. A record a crashed run left behind therefore speaks for its own
   branch only.
3. **The seeder's Edit scope is the change's files.** `write-scope.sh --seed` is in the frontmatter and in a
   `--agent defect-seeder --seed` twin in both settings files. It allows exactly the files
   `git diff --name-only --no-renames --diff-filter=d <base> <head>` lists, the list `seed-check.sh` holds the edit
   to afterwards. No record, an unreadable one, or one for another branch refuses every write. `.ai/runs/` is never a
   change's file.
4. **The migration guard's base on a seed branch is the run's base.** `migration_base_ref` (`_lib.sh`), which only
   `guard-migrations.sh` calls, answers the record's `<base>` when no autopilot LOCK exists, and `base_ref` otherwise.
   A migration the change added is new there, as it was on the change's own branch. The shared `base_ref` does not
   change, so `factory_scripts_intact` and every other check still compare with the lock's commit or with `main`
   (architecture review round 1). If the record cannot be read, or names a commit the repository does not hold, the
   guard falls back to `base_ref`, the stricter answer, and `write-scope.sh --seed` refuses (security review
   round 1).
5. **A factory script makes the commit.** `seed-check.sh <branch> <base> <head>` requires HEAD at `<head>` (a seeder
   commit is refused), no untracked file, and changes only in the change's files. `seed-fold.sh <branch> <base>
   <head>` writes the working tree through an index of its own and commits it on `<base>` under the change's subject.
   It requires exactly seed-branch.sh's two commits in the HEAD reflog above the run's switch, so any git that ran
   on the branch since is refused. The reflog trimming of ADR-0044 decision 6 stays.

## Alternatives considered

- **A fixed scope** (the modules' `src/main/`, `pom.xml`, `k8s/`) and a `readonly-bash.sh --seeder` mode that allows
  `git add` and `git commit`, as `--architect` does. It is wider than the change and goes stale with a new module.
  It also leaves migrations as they were: the guard would still compare with `origin/main`, so the run's base is
  needed anyway.
- **A record, but the seeder still commits** (a `--seeder` Bash mode). It works, but keeps a write surface, `git add`
  and `git commit`, that carries nothing the fold keeps, and adds a guard mode that has to be tested.
- **Run seeds only in an autopilot session.** Its allow-list has `git switch *` and `git checkout -b *`, its denies
  leave `.ai/runs/` open, and migrations stay as they are. It also does not match where the series actually run.
- **Reuse `.ai/runs/LOCK`.** The lock means "an autopilot run holds the repository". `guard-git-push.sh`,
  `guard-factory-scripts.sh` and `seed-branch.sh` itself read it that way.

## Consequences

- AC1 to AC4 of #0-172 are cases in `.claude/hooks/test-hooks.sh` (the scope, the twin, the migration base, the
  seeder's Bash) and in `.github/scripts/test-seed-branch.sh` (the record's life, a seeder commit refused, the fold of
  an uncommitted edit). Factory guards' `check-hook-entries.rb` (rule 4) fails when one layer of either guard is
  missing.
- `.ai/runs/SEED` is not on the protected list. An autopilot agent that writes it changes nothing, because
  `migration_base_ref` reads LOCK first and `seed-branch.sh` refuses to start while LOCK exists. In the owner's session the file is as
  open as every other path is there.
- The P-13 and P-19 migration patterns can be measured with the seeder's Edit. P-19 waited for this.
- Left open: `check-hook-entries.rb` cannot see an agent with Bash or Edit and no guard in either layer, which was
  this item's case. That check is its own backlog item.
