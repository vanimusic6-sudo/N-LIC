# N-LIC — Codespaces dev signing

This setup creates a *development* Android signing key inside your own Codespace and uploads it to **GitHub Actions Secrets**. The private key is never committed to the public Git repository.

## One command

Open **Code → Codespaces → Create codespace on master** and run:

```bash
bash scripts/setup-codespaces-dev-key.sh
```

The script generates a persistent PKCS12 RSA-3072 key and a random password, checks the key, and uploads four Actions secrets:
- `NLIC_DEV_KEYSTORE_BASE64`
- `NLIC_DEV_STORE_PASSWORD`
- `NLIC_DEV_KEY_PASSWORD`
- `NLIC_DEV_KEY_ALIAS`

On success, use the Codespaces Explorer file menu (**right click → Download**) to download both files:
- `.nlic-private/nlic-dev.p12`
- `.nlic-private/nlic-dev-credentials.txt`

Store them encrypted and separate from the repository. Download both **before deleting the Codespace**. The script reuses the same local key if rerun in the same Codespace, preventing accidental key rotation. A new Codespace will not be able to restore an existing key from GitHub Secrets (GitHub doesn't reveal secret values). For future runs, keep the backup offline.

## If GitHub CLI cannot save secrets

GitHub Codespaces tokens may not have permission to modify Actions Secrets. If you see `HTTP 403: Resource not accessible by integration`, authenticate your personal GitHub account, using the command below. The script explicitly ignores the injected integration token when uploading secrets:

```bash
env -u GH_TOKEN -u GITHUB_TOKEN gh auth login --hostname github.com --web --scopes repo
git pull --ff-only
bash scripts/setup-codespaces-dev-key.sh
```

You may need to authorize the request in your browser. This does not regenerate the key if the local files already exist.

## Build the signed development APK

After the four secrets exist, open **Actions → N-LIC Dev APK → Run workflow**.
The workflow automatically builds and signs an APK with package ID `io.github.vanimusic6sudo.nlic.dev`, then publishes **the APK only** as an Actions artifact.

Never publish the `.p12` or password files as artifacts or GitHub releases. Never copy them into an issue, chat, or commit. Never reuse the dev key as a production release key.

## Security tradeoff

Actions Secrets are encrypted at rest, but a workflow on a trusted branch can access them during a build. Any malicious change to build scripts, workflows, dependencies or Actions with access to these secrets could exfiltrate the dev key. Only grant write permissions to trusted collaborators. A separate production release key should be stored **off GitHub** for maximum protection; if you deliberately choose GitHub-hosted release signing later, restrict it with protected environments and explicit approvals.

The Android namespace is kept at the upstream value for source compatibility; package IDs are independent:
- N-LIC release package (when configured): `io.github.vanimusic6sudo.nlic`
- N-LIC debug package: `io.github.vanimusic6sudo.nlic.dev`

This signing setup is not yet proof of a passing CI build. Check the Actions run and inspect its logs if Gradle/NDK fails.
