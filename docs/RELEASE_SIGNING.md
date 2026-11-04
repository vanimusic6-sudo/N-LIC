# N-LIC: independent release certificate (Codespaces)

## Why a new certificate?

- The original ByeByeDPI certificate belongs only to the upstream developer.
- N-LIC **DEV** already has a unique RSA-3072 certificate, which stays unchanged for its own development updates.
- N-LIC **RELEASE** uses another, newly generated, long-lived RSA-3072 certificate and a different package ID (`io.github.vanimusic6sudo.nlic`). Official releases will never be signed by the upstream author or by the N-LIC DEV certificate.
- **This is software provenance, not a workaround for Play Protect.** Do not publish blocked artifacts until security and classification have been reviewed.

## Generate key and upload protected secrets

1. GitHub repository **Settings → Environments → New environment**: create `nlic-release`. Set required reviewers / deployment branch restrictions to `master` where your plan supports them.
2. Open **Code → Codespaces** on the trusted N-LIC branch containing the new script.
3. Authorize GitHub CLI under your own account if necessary, not the limited Codespaces integration token:
   `env -u GH_TOKEN -u GITHUB_TOKEN gh auth login --hostname github.com --web --scopes repo`.
4. Run `bash scripts/setup-codespaces-release-key.sh`. It creates a **new distinct certificate**, without touching `nlic-dev.p12`. If an existing release signing secret is present but the local backup is absent, it refuses to rotate the certificate.
5. Download both files from Codespaces Explorer → **Download**:
   - `.nlic-private/nlic-release.p12`
   - `.nlic-private/nlic-release-credentials.txt`
6. Place them in encrypted storage on two independent physical devices. Never upload them to a public release, Git commit, or chat. Verify they are accessible before deleting Codespaces.
7. In **Settings → Environments → nlic-release**, confirm four environment-level secrets exist:
   `NLIC_RELEASE_KEYSTORE_BASE64`, `NLIC_RELEASE_STORE_PASSWORD`, `NLIC_RELEASE_KEY_PASSWORD`, `NLIC_RELEASE_KEY_ALIAS`.
8. Revoke temporary GitHub CLI OAuth authorization if you no longer need it and delete the Codespace **after** backing up the key. This does not affect GitHub Actions environment secrets.

## Build

Only after the security review is approved, on the protected `master` branch run:

**Actions → N-LIC Release APK (manual, protected) → Run workflow** and explicitly confirm `security_review_approved`.

The action verifies keystore availability, builds `:app:assembleRelease`, checks APK signature, confirms application ID and debuggability, and uploads a **private Actions artifact**, not an automatic GitHub Release.

The workflow requires access to the `nlic-release` environment. Restrict who can edit `master`, Gradle build scripts, native submodules, dependencies, and workflow files: a malicious CI change can access secrets granted to the job. Hosted signing is less secure than an off-GitHub offline release key.

**Updates must use the same release certificate, package ID and an increasing `versionCode`.** DEV and RELEASE packages are intentionally separate. Rotating either key does not resolve Play Protect warnings and prevents existing installs with that package ID from updating in place.

The official release workflow is **not ready to run until the production key/environment are created manually**.
