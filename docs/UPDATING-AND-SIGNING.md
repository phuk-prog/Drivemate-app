# Updating one DriveMate installation

## Package identity

DriveMate v1.2 sets `applicationId 'com.drivemate.android'`, matching the earlier Drivemate research app, and uses the visible label **DriveMate**. This prevents the v1.2 and old Drivemate app packages from coexisting under different labels. The separate older **DriveMate GPS** builds used `com.drivemate.app` and are not updated by changing the new package ID: Android cannot uninstall a distinct app package for you.

## One-time migration from older builds

Android requires **both the same package ID and the same signing certificate** before replacing an installed APK. The former `com.drivemate.android` Drivemate app and v1.2 share the ID, but their signing key has not been verified to match. **Uninstall the previous Drivemate app if Android reports a signing conflict** and then install the new signed APK. Likewise, manually uninstall the separate `com.drivemate.app` DriveMate GPS app if present. Uninstalling deletes local app preferences and saved destinations: copy any needed settings first. Do not promise seamless replacement of unrelated package IDs or unknown signatures.

## Stable updates from v1.2 onward

The reproducible **source** and **applicationId** are public; the private signing key must never be committed, logged or published as a GitHub Actions artifact.

1. Store the v1.2 signing keystore securely and offline, with a separate password. Back it up. Every v1.3+ APK must use **that exact same keystore alias and certificate**, and a higher `versionCode`. Otherwise Android rejects the update.
2. The GitHub workflow compiles/tests both a debug APK and an **unsigned release APK**. It intentionally does **not** upload a privately signed release automatically.
3. Download the unsigned release artifact and use Android SDK Build Tools `apksigner` locally with the private keystore:
   ```bash
   export DRIVEMATE_SIGNING_PASSWORD='your-secret'
   bash tools/sign-release.sh path/to/app-release-unsigned.apk \
     /private/path/DriveMate-signing-keystore.p12 \
     path/to/DriveMate-v1.3.apk
   ```
4. Install the signed APK on an Android phone already running the privately signed DriveMate v1.2. Confirm the phone offers **Update**, not a separate installation. Keep the private keystore outside the repository.

The **debug** APKs produced by hosted GitHub runners are temporary test builds and may have inconsistent signing certificates across runs. They must not be relied upon for in-place updates. Only the specifically signed release APK can use the stable update path.

## Safety

This is a foreground-only online navigation prototype. Routes and instructions have not been road-validated and must not replace a trusted navigator. No Android Auto, lock-screen navigation, offline map routing, speed-camera guarantees, lane guidance or vehicle restrictions are promised.
