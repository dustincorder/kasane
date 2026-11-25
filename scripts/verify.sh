#!/usr/bin/env bash
# Safe local verification: builds and unit tests only. Starts no app, touches no device, needs no network
# beyond dependency downloads. Usage: scripts/verify.sh [rust] [web] [kotlin]   (default: all three)
set -uo pipefail
cd "$(dirname "$0")/.."
parts=("$@"); [ ${#parts[@]} -eq 0 ] && parts=(rust web kotlin)
failed=()
run() { echo; echo "== $*"; "$@" || failed+=("$*"); }
for part in "${parts[@]}"; do
  case "$part" in
    rust)
      run cargo test --workspace
      run cargo clippy --workspace --all-targets -- -D warnings ;;
    web)
      [ -d node_modules ] || run npm ci
      run npm run build:verifier
      run npm run check ;;
    kotlin)
      [ -f verifier/dist/kasane-verifier.js ] || run npm run build:verifier
      (cd android && ./gradlew --console=plain --continue \
        :shared:jvmTest :desktop:test :app:testDebugUnitTest :app:assembleDebug) \
        || failed+=("gradle :shared:jvmTest :desktop:test :app:testDebugUnitTest :app:assembleDebug") ;;
    *) echo "unknown part: $part (rust|web|kotlin)"; exit 2 ;;
  esac
done
echo
if [ ${#failed[@]} -eq 0 ]; then echo "verify: all checks passed (${parts[*]})"; else
  echo "verify: FAILED:"; printf '  %s\n' "${failed[@]}"; exit 1; fi
