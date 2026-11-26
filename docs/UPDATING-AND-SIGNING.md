# Updating one DriveMate installation

## Package identity

DriveMate v1.2 sets `applicationId 'com.drivemate.android'`, matching the earlier Drivemate research app, and uses the visible label **DriveMate**. This prevents the v1.2 and old Drivemate app packages from coexisting under different labels. The separate older **DriveMate GPS** builds used `com.drivemate.app` and are not updated by changing the new package ID: Android cannot uninstall a distinct app package for you.

## One-time migration from older builds

Android requires **both the same package ID and the same signing certificate** before replacing an installed APK. The former `com.drivemate.android` Drivemate app and v1.2 share the ID, but their signing key has not been verified to match. **Uninstall the previous Drivemate app if Android reports a signing conflict** and then install the new signed APK. Likewise, manually uninstall the separate `com.drivemate.app` DriveMate GPS app if present. Uninstalling deletes local app preferences and saved destinations: copy any needed settings first. Do not promise seamless replacement of unrelated package IDs or unknown signatures.

## v1.2 owner signing certificate (public fingerprint only)

The signed v1.2 APK is issued under this **SHA-256 certificate fingerprint** (safe to share): `9F:11:80:4F:0C:28:F6:1B:E2:58:8F:C6:E1:66:35:14:CF:CC:A6:AD:4D:A4:E3:1E:19:E4:18:6C:02:9B:1A:38`. Later signed release APKs must show the **same** fingerprint and a higher version code. Keep the private `DriveMate-Signing-Key.p12` and its password outside GitHub and back them up securely. Losing the signing key prevents normal in-place updates. A private key is never a GitHub file or Action artifact.

## Stable updates from v1.2 onward

The reproducible **source** and **applicationId** are public; the private signing key must never be committed, logged or published as a GitHub Actions artifact.

1. Store the v1.2 signing keystore securely and offline, with a separate password. Back it up. Every v1.3+ APK must use **that exact same keystore alias and certificate**, and a higher `versionCode`. Otherwise Android rejects the update.
2. The GitHub workflow compiles/tests debug and unsigned release intermediates only on isolated runners; neither is downloadable.
3. Use the main-only verified signing workflow, which checks the owner certificate and uploads only the signed release APK with a one-hour deletion target. No ZIP password is needed; no permanent APK Release asset is published.
4. Verify the SHA256 evidence and install the signed APK on a phone already running an owner-signed DriveMate. Confirm Android offers **Update**. Keep the private keystore outside the repository.

The **debug** APKs produced by hosted GitHub runners are temporary test builds and may have inconsistent signing certificates across runs. They must not be relied upon for in-place updates. Only the specifically signed release APK can use the stable update path.

## Safety

This is a foreground-only online navigation prototype. Routes and instructions have not been road-validated and must not replace a trusted navigator. No Android Auto, lock-screen navigation, offline map routing, speed-camera guarantees, lane guidance or vehicle restrictions are promised.
