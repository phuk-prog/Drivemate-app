# DriveMate build and release delivery policy

The project owner requested on 2026-11-23: **“Always create the signed release.”**

For completed, owner-authorised implementation work, deliver a **privately signed, installation-ready release-variant APK** in addition to any isolated debug builds. Do not substitute an unsigned APK or debug APK for the requested signed output.

## Release signing safeguards

- Use the **existing** `tools/sign-release.sh` and the owner-provided **GitHub Actions signing secrets**. Never commit keystores, passwords, certificates' private material or secret values.
- Verify the signing certificate SHA-256 against the established DriveMate owner certificate before publishing an artifact. Fail the job if the fingerprint differs.
- Increase the release `versionCode` above the installed stable version to permit Android updates; set a visible version name.
- Compile and run the JVM regression suite, verify map asset/road tests and package identity, sign the **release** APK, and upload it as a clearly labelled GitHub Actions artifact.
- Use a narrowly scoped trusted branch-only signing workflow; **never expose signing secrets to pull-request events or arbitrary branches**.
- A signed review candidate is **not** an approved public stable release. State what device and road tests still need to be completed.

## Publishing and merging remain separate decisions

Building a signed APK does **not** authorise merging to `main`, creating a GitHub Release, tagging `main`, overwriting the stable branch, or representing an untested build as safe for sole navigation. Obtain explicit approval before any of those actions.

The current combined all-11 release candidate is built from `dev/all-approved-stage3-arrival-20261123`, version `1.7.0-rc1`, versionCode `100700`, stable Android application ID `com.drivemate.android`. It can update the installed stable app **if** the certificate verification passes; unlike the separate debug variants, it does not install alongside 1.6.1.

Always make clear that installing a release-candidate signed update replaces the currently installed application package; users who wish to preserve 1.6.1 should keep it installed and test the isolated debug variant first.

The consolidated main-only signing workflow uploads a certificate-verified signed APK as a temporary Actions artifact, with a one-hour cleanup target and a one-day native fallback. No password, permanent APK Release asset, or automatic stable publication. Configure the protected drivemate-release environment before using it. No PR event receives signing secrets. See PRIVATE-DISTRIBUTION.md for limitations.
