#!/usr/bin/env bash
# Create a permanent, distinct N-LIC PRODUCTION signing identity in your own Codespace.
# The key is never committed and only the private release environment can access it.
set -Eeuo pipefail
umask 077
REPO="vanimusic6-sudo/N-LIC"
ENVIRONMENT="nlic-release"
ROOT="$(git rev-parse --show-toplevel)"
DIR="$ROOT/.nlic-private"
KEY_FILE="$DIR/nlic-release.p12"
RECOVERY_FILE="$DIR/nlic-release-credentials.txt"
ALIAS="nlic-release"
gh_user() { env -u GH_TOKEN -u GITHUB_TOKEN gh "$@"; }

for cmd in gh keytool openssl base64 git; do
  command -v "$cmd" >/dev/null || { echo "Missing: $cmd" >&2; exit 1; }
done
if ! git remote -v | grep -Fq "$REPO"; then
  echo "Run inside the $REPO Codespace" >&2; exit 1
fi
if ! gh_user auth status --hostname github.com >/dev/null 2>&1; then
  echo "Authorize user account first:" >&2
  echo "env -u GH_TOKEN -u GITHUB_TOKEN gh auth login --hostname github.com --web --scopes repo" >&2
  exit 1
fi

# The production signing secrets must NOT be repository-wide secrets.
if ! gh_user api -X PUT "repos/$REPO/environments/$ENVIRONMENT" >/dev/null 2>&1; then
  echo "Could not create restricted release environment. Create '$ENVIRONMENT' in GitHub Settings > Environments first." >&2
  exit 1
fi
mkdir -p "$DIR"; chmod 700 "$DIR"

if [[ -s "$KEY_FILE" && -s "$RECOVERY_FILE" ]]; then
  PASS="$(sed -n 's/^STORE_PASSWORD=//p' "$RECOVERY_FILE" | head -n 1)"
  [[ -n "$PASS" ]] || { echo "Backup password missing" >&2; exit 1; }
  echo "Reusing existing RELEASE certificate (no rotation)."
elif [[ -e "$KEY_FILE" || -e "$RECOVERY_FILE" ]]; then
  echo "Partial release backup found; refusing to overwrite" >&2
  exit 1
else
  # Never silently rotate release keys already installed in GitHub Secrets.
  if gh_user secret list -e "$ENVIRONMENT" -R "$REPO" | grep -q '^NLIC_RELEASE_KEYSTORE_BASE64[[:space:]]'; then
    echo "Release secret already exists but local backup is absent. Recover from your offline backup; no rotation." >&2
    exit 1
  fi
  PASS="$(openssl rand -hex 32)"
  export PASS
  keytool -genkeypair -noprompt -v \
    -storetype PKCS12 -keystore "$KEY_FILE" \
    -storepass:env PASS -keypass:env PASS \
    -alias "$ALIAS" -keyalg RSA -keysize 3072 -sigalg SHA256withRSA \
    -validity 10950 -dname "CN=N-LIC Release, OU=Development, O=N-LIC" >/dev/null
  {
    printf 'N-LIC PRODUCTION SIGNING BACKUP - KEEP OFFLINE\n'
    printf 'KEY_FILE=nlic-release.p12\nKEY_ALIAS=%s\n' "$ALIAS"
    printf 'STORE_PASSWORD=%s\nKEY_PASSWORD=%s\n' "$PASS" "$PASS"
  } > "$RECOVERY_FILE"
fi
export PASS
trap 'unset PASS' EXIT
chmod 600 "$KEY_FILE" "$RECOVERY_FILE"
keytool -list -keystore "$KEY_FILE" -storepass:env PASS -alias "$ALIAS" > /dev/null

base64 -w0 "$KEY_FILE" | gh_user secret set NLIC_RELEASE_KEYSTORE_BASE64 -e "$ENVIRONMENT" -R "$REPO"
printf '%s' "$PASS" | gh_user secret set NLIC_RELEASE_STORE_PASSWORD -e "$ENVIRONMENT" -R "$REPO"
printf '%s' "$PASS" | gh_user secret set NLIC_RELEASE_KEY_PASSWORD -e "$ENVIRONMENT" -R "$REPO"
printf '%s' "$ALIAS" | gh_user secret set NLIC_RELEASE_KEY_ALIAS -e "$ENVIRONMENT" -R "$REPO"

echo "RELEASE signing key uploaded to '$ENVIRONMENT'."
echo "DOWNLOAD AND ENCRYPT BOTH FILES BEFORE DELETING CODESPACES:"
echo "  .nlic-private/nlic-release.p12"
echo "  .nlic-private/nlic-release-credentials.txt"
echo "Configure an environment reviewer if available to prevent unauthorized releases."
