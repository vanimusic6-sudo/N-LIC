#!/usr/bin/env bash
# N-LIC: create and back up a persistent *development* Android signing key
# in your own GitHub Codespace, then upload it to GitHub Actions Secrets.
set -Eeuo pipefail
umask 077

REPO="vanimusic6-sudo/N-LIC"
ROOT="$(git rev-parse --show-toplevel)"
BACKUP_DIR="$ROOT/.nlic-private"
KEY_FILE="$BACKUP_DIR/nlic-dev.p12"
RECOVERY_FILE="$BACKUP_DIR/nlic-dev-credentials.txt"
ALIAS="nlic-dev"

for dependency in gh keytool openssl base64 git; do
  if ! command -v "$dependency" >/dev/null 2>&1; then
    echo "Missing command: $dependency" >&2
    exit 1
  fi
done

# Codespaces injects an integration token which overrides the user OAuth login,
# but that token often lacks permission to manage repository Actions Secrets.
gh_user() { env -u GH_TOKEN -u GITHUB_TOKEN gh "$@"; }

if ! gh_user auth status --hostname github.com >/dev/null 2>&1; then
  echo "Authorize your GitHub account first (the default Codespaces token cannot set secrets):" >&2
  echo "  env -u GH_TOKEN -u GITHUB_TOKEN gh auth login --hostname github.com --web --scopes repo" >&2
  echo "Then rerun this script. Existing keys are preserved." >&2
  exit 1
fi
if ! gh_user api "repos/$REPO/actions/secrets/public-key" > /dev/null 2>&1; then
  echo "Your GitHub CLI login cannot manage Actions Secrets in $REPO." >&2
  echo "Try: env -u GH_TOKEN -u GITHUB_TOKEN gh auth refresh --hostname github.com --scopes repo" >&2
  echo "Then rerun this script. Existing keys are preserved." >&2
  exit 1
fi

if ! git remote -v | grep -Fq "vanimusic6-sudo/N-LIC"; then
  echo "Run this script inside your vanimusic6-sudo/N-LIC Codespace." >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

if [[ -e "$KEY_FILE" || -e "$RECOVERY_FILE" ]]; then
  if [[ ! -s "$KEY_FILE" || ! -s "$RECOVERY_FILE" ]]; then
    echo "Incomplete signing backup found. Refusing to overwrite any key." >&2
    exit 1
  fi
  NLIC_DEV_GENERATED_PASSWORD="$(sed -n 's/^STORE_PASSWORD=//p' "$RECOVERY_FILE" | head -n 1)"
  if [[ -z "$NLIC_DEV_GENERATED_PASSWORD" ]]; then
    echo "Cannot read password from the existing recovery file." >&2
    exit 1
  fi
  echo "Reusing the existing dev key (no key rotation)."
else
  NLIC_DEV_GENERATED_PASSWORD="$(openssl rand -hex 32)"
  export NLIC_DEV_GENERATED_PASSWORD
  keytool -genkeypair -noprompt \
    -storetype PKCS12 \
    -keystore "$KEY_FILE" \
    -storepass:env NLIC_DEV_GENERATED_PASSWORD \
    -keypass:env NLIC_DEV_GENERATED_PASSWORD \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 3072 \
    -sigalg SHA256withRSA \
    -validity 10950 \
    -dname "CN=N-LIC Development, OU=Development, O=N-LIC" >/dev/null

  {
    printf 'N-LIC DEVELOPMENT SIGNING BACKUP — KEEP PRIVATE\n'
    printf 'KEY_FILE=nlic-dev.p12\n'
    printf 'KEY_ALIAS=%s\n' "$ALIAS"
    printf 'STORE_PASSWORD=%s\n' "$NLIC_DEV_GENERATED_PASSWORD"
    printf 'KEY_PASSWORD=%s\n' "$NLIC_DEV_GENERATED_PASSWORD"
  } > "$RECOVERY_FILE"
fi

export NLIC_DEV_GENERATED_PASSWORD
trap 'unset NLIC_DEV_GENERATED_PASSWORD' EXIT
chmod 600 "$KEY_FILE" "$RECOVERY_FILE"
keytool -list -keystore "$KEY_FILE" \
  -storepass:env NLIC_DEV_GENERATED_PASSWORD -alias "$ALIAS" >/dev/null

echo "Uploading dev signing secrets to $REPO..."
base64 -w0 "$KEY_FILE" | gh_user secret set NLIC_DEV_KEYSTORE_BASE64 --repo "$REPO" --app actions
printf '%s' "$NLIC_DEV_GENERATED_PASSWORD" | gh_user secret set NLIC_DEV_STORE_PASSWORD --repo "$REPO" --app actions
printf '%s' "$NLIC_DEV_GENERATED_PASSWORD" | gh_user secret set NLIC_DEV_KEY_PASSWORD --repo "$REPO" --app actions
printf '%s' "$ALIAS" | gh_user secret set NLIC_DEV_KEY_ALIAS --repo "$REPO" --app actions

echo ""
echo "SUCCESS: dev signing key is in GitHub Actions Secrets."
echo "BACK UP THESE TWO FILES FROM THE CODESPACES EXPLORER:"
echo "  .nlic-private/nlic-dev.p12"
echo "  .nlic-private/nlic-dev-credentials.txt"
echo ""
echo "These files are gitignored. Download both and keep them encrypted/offline."
echo "Do not commit them or upload them to GitHub Releases/Actions artifacts."
echo "Codespaces can be deleted: verify your local backups before deleting one."
echo ""
echo "Installed secrets:"
gh_user secret list --repo "$REPO" --app actions
