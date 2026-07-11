#!/bin/sh
# Pre-commit gate: run the full Maven test suite before allowing a commit.
# Install: symlink or copy this file to .git/hooks/pre-commit (see README).

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT" || exit 1

echo "Running test suite before commit (./mvnw test -q)..."

./mvnw test -q
STATUS=$?

if [ $STATUS -ne 0 ]; then
  echo ""
  echo "Commit aborted: test suite failed (./mvnw test -q, exit code $STATUS)."
  echo "Fix the failing tests before committing."
  exit 1
fi

exit 0
