# Signed releases and the exposed development key

## Security status

On November 27, 2026, the development signing keystore and its password were removed from the active repository. The old key was publicly exposed and **must never be used again**. Legacy beta releases and the old Android Actions debug artifacts were retired. The original `1.0` release is marked immutable by GitHub and still carries `app-debug.5.zip`. **Do not install or redistribute this old ZIP.**

The active branch is protected against accidentally checking in common keystore formats. Debug APKs are test-only, are signed with build-environment debug keys, and should not be distributed as production updates.

## Create and protect a new signing key

Generate a NEW key on your own trusted computer, outside the repository. With JDK `keytool` installed:

```bash
keytool -genkeypair -v -keystore "$HOME/SetEditLocker-NEW-release.jks" -alias seteditlocker-new -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=SetEditLocker Release"
```

Choose a new strong password when prompted. Make an offline backup of the keystore and passwords, stored separately from the code. Never upload the keystore or passwords to GitHub issues, source files, releases, or chat messages.

In the repository, open **Settings > Secrets and variables > Actions** and create four repository secrets:

- `SETEDIT_RELEASE_KEYSTORE_B64`: the entire base64-encoded contents of the NEW JKS file, without line breaks. On Linux use `base64 -w 0 "$HOME/SetEditLocker-NEW-release.jks"`; on macOS use `base64 "$HOME/SetEditLocker-NEW-release.jks" | tr -d '\\n'`. Put the result directly into the secret field, not in a commit.
- `SETEDIT_RELEASE_STORE_PASSWORD`: the keystore password.
- `SETEDIT_RELEASE_KEY_ALIAS`: `seteditlocker-new`.
- `SETEDIT_RELEASE_KEY_PASSWORD`: the key password (often the same as the keystore password).

Then use **Actions > Build signed Android release (manual) > Run workflow**. This workflow fails closed when secrets are missing, builds a signed release only with private environment-injected signing material, verifies its APK signature, and uploads a short-lived artifact for manual review. It does NOT publish a release automatically.

You can verify the downloaded APK yourself:

```bash
apksigner verify --verbose --print-certs app-release.apk
```

### Install/update implications

Signing certificates determine Android update compatibility. A package previously signed by the exposed key generally **cannot** be updated in-place using the replacement key. Preserve and export app data before uninstalling anything. The debug variant currently uses the `.debug` application ID suffix, so it is not the same package ID as a production release.

### Historical Git exposure and the immutable 1.0 asset

Deleting the file from the current branch does not erase older commits, pull request refs, forks or archived downloads. Fully rewriting the repository would change commit hashes and disrupt prior pull requests. Follow [GitHub's sensitive-data removal procedure](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository), using `git-filter-repo --sensitive-data-removal` after closing or merging open pull requests and coordinating all clones. GitHub Support must be involved to address internal PR refs and caches.

The old `1.0` release is **immutable** and GitHub rejected an attempt to delete its APK asset with HTTP 422. Contact [GitHub Support](https://support.github.com/) about removing the immutable asset and outstanding GitHub-hosted cached data. Do not claim the old key is fully removed until GitHub confirms the remaining exposure has been addressed.
