---
description: Mandatory safety controls for destructive filesystem, Git, database, deployment, and infrastructure operations
alwaysApply: true
---

# Destructive Operations Safety Rule

Never execute, approve, construct for immediate execution, or indirectly trigger a destructive or potentially destructive operation without explicit confirmation from the user immediately before execution.

This rule applies even when:
- the operation appears necessary to complete the task;
- the user previously gave general permission to make changes;
- the operation is described as cleanup, reset, synchronization, migration, repair, backup, refactoring, or reorganization;
- the target appears to contain generated, temporary, cached, ignored, or obsolete files;
- the command is invoked through a script, package manager, build tool, IDE task, subprocess, MCP tool, extension, or another program.

## Operations requiring explicit confirmation

Treat all of the following as destructive:

- Deleting any file or directory.
- Recursive deletion, cleanup, pruning, or removal.
- Moving or renaming multiple files or directories.
- Replacing, truncating, or overwriting existing files outside ordinary targeted source-code edits.
- Commands using wildcards that may affect multiple files.
- Commands targeting an absolute path, drive root, share root, parent directory, home directory, or any location outside the current workspace.
- Commands containing `..` path traversal.
- Commands whose target depends on an environment variable, interpolated variable, command substitution, glob, symlink, junction, mount point, or dynamically calculated path.
- Git operations that discard or rewrite work, including `git clean`, `git reset --hard`, forced checkout, forced restore, forced branch deletion, rebase of shared history, or forced push.
- Database drops, truncations, destructive migrations, bulk deletes, or resets.
- Destructive cloud, deployment, infrastructure, storage, or synchronization operations.
- Mirroring or synchronization commands that can delete destination files.
- Any operation that could affect files outside the current project directory.

Examples include, but are not limited to:

- `rm`, `rm -rf`
- `del`, `erase`
- `rd`, `rmdir`, `rmdir /s`
- `Remove-Item`, especially with `-Recurse` or `-Force`
- `robocopy /MIR`, `/PURGE`, or `/MOVE`
- `git clean`
- `git reset --hard`
- scripts using `shutil.rmtree`, `fs.rm`, `rimraf`, recursive unlinking, or equivalent APIs

## Required procedure

Before requesting confirmation:

1. Stop before executing the operation.
2. State exactly what will be deleted, moved, overwritten, reset, synchronized, or otherwise changed.
3. Show the exact command or operation.
4. Resolve and display every affected path as an absolute path.
5. Verify the current working directory.
6. Verify that every affected path is inside the intended project workspace.
7. Identify whether the workspace is on a mapped drive, UNC path, network share, cloud-synchronized folder, symlink, junction, or mounted filesystem.
8. Explain whether the operation can affect parent directories, sibling directories, remote files, or files outside the workspace.
9. Run a non-destructive preview, dry run, listing, or status check whenever available.
10. Ask for explicit confirmation for that exact operation.

Do not treat silence, previous approval, general approval, approval of a plan, or a request to "fix," "clean," "reset," "sync," or "finish" the project as authorization.

The confirmation request must contain:

- the exact command;
- the resolved absolute target paths;
- the estimated scope or number of affected files;
- whether recovery is available through Git, snapshots, recycle bin, backups, or version history.

Only proceed after the user explicitly confirms the exact operation.

## Workspace boundary

The current project directory is the maximum permitted filesystem boundary.

Never modify, move, rename, overwrite, or delete:

- the project's parent directory;
- sibling directories;
- the root of a mapped drive;
- the root of a UNC or network share;
- user profile directories;
- another repository;
- files outside the current workspace.

If work outside the workspace is required, stop and request explicit permission naming the exact absolute path.

## Shared-drive restrictions

When the workspace is located on a mapped drive or network share:

- Never run recursive delete, recursive move, mirror, purge, cleanup, or synchronization operations.
- Never operate on the share root or project parent directory.
- Never assume a server-side recycle bin or local Recycle Bin exists.
- Treat deletion as potentially immediate and unrecoverable.
- Require a verified backup or snapshot before any bulk filesystem operation.
- Prefer editing a local clone and synchronizing through version control.

## Safer alternatives

Prefer, in order:

1. Git-tracked, targeted edits.
2. Moving files to a project-local quarantine directory.
3. Renaming files rather than deleting them.
4. Generating a list for user review.
5. Dry-run or preview modes.
6. Creating a backup or snapshot.
7. Asking the user to perform the destructive action manually.

When uncertain whether an operation is destructive, treat it as destructive and ask.
