# DriveMate: enable GitHub to produce APKs that update in place

No key, password, APK, or credentials should be committed to this public GitHub repository.

The native Android app is already in `main`. Its stable app ID is `com.drivemate.android`. The DriveMate v1.2 signed release certificate SHA-256 is
`9F:11:80:4F:0C:28:F6:1B:E2:58:8F:C6:E1:66:35:14:CF:CC:A6:AD:4D:A4:E3:1E:19:E4:18:6C:02:9B:1A:38`.

## Configure two private signing-environment Actions secrets

1. Open: https://github.com/phuk-prog/Drivemate-app/settings/secrets/actions
2. Configure the protected `drivemate-release` environment restricted to `main`; add the two signing secrets there.
3. Name: `DRIVEMATE_KEYSTORE_BASE64`. Value: the entire contents of your **private** file `DriveMate-KEYSTORE-BASE64.txt`, which was derived from `DriveMate-PRIVATE-SIGNING-KEY.p12`. This is not a password: it is your signing key encoded into text. Keep it secret.
4. Add the second environment secret.
5. Name: `DRIVEMATE_SIGNING_PASSWORD`. Value: the exact keystore/key password from your private `DriveMate-EXACT-PASSWORDS.txt` file.
6. Double-check that both values were pasted into **Actions secrets**, not into repository files, variables, issues, code, README, discussion or GitHub Actions run inputs.

## Produce the next signed APK

1. Use `python3 tools/bump_release_version.py VERSION --apply` on a reviewed branch.
2. The main-only signing workflow is [signed-release.yml](https://github.com/phuk-prog/Drivemate-app/actions/workflows/signed-release.yml).
3. Merging the version change triggers signing, or manually run the workflow on main.
4. After all tests and certificate checks pass, download `DriveMate-VERSION-signed-apk-1h` promptly. Extract the standard artifact ZIP; no ZIP password is needed.
5. Verify the separate SHA256 evidence and install the signed APK. The one-hour deletion target is best effort; GitHub's native retention fallback is one day.

## One-time incompatibility warning

The original Drivemate Android APK may use an unknown certificate, and older DriveMate GPS APKs used a **different** app ID `com.drivemate.app`. Android will not silently replace or uninstall these. If v1.2 cannot replace the original, uninstall the original **once**, then install the privately signed v1.2 APK. Remove the separate DriveMate GPS app if present. Existing local data in uninstalled apps is lost.

The v1.2 private signing key and its password must be kept safe in an offline backup. GitHub Actions secrets are an additional copy, **not** a replacement for secure backups. If you lose the signing key, you may be unable to update installed APKs in place.

The signed APK workflow runs on approved main version changes or manual main dispatch, not on pull requests. Do not modify this restriction or run untrusted workflow code with access to the secrets.
