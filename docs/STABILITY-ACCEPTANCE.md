# DriveMate coordinated stability acceptance

App integration: PR #36. Map integration: Drivemate-map PR #21.
Last signed engineering candidate: 2.0.1 (run 45689920209). Main before
integration: 1.7.7. Candidate: 2.0.2. No new public stable release approved.

## Mandatory gates
- [ ] Review exact generated dependency lock; zero OSV findings, wrapper check.
- [ ] Unit tests, Wells navigation replay and Stockport alignment.
- [ ] GPS stale/loss/recovery, stationary drift, missed turn, roundabout,
  motorway, day/night, landscape and accessibility on a physical device.
- [ ] Map graph checksums and all five national journeys with exact anchors;
  alternative anchors are diagnostic only. Local routing remains preview-only.
- [ ] Match source snapshot, map tiles, graph, intelligence and evidence hashes
  before moving any experimental pin. Do not substitute mutable aliases.
- [ ] Owner configures protected signing environment and approves signed
  candidate; verify package ID, version code, signer and install over 2.0.1.
- [ ] Preserve previous APK and map pins. An APK downgrade is not guaranteed;
  recovery may require a forward-version rebuild or reinstall.
- [ ] Physical-device acceptance and separate approval before stable promotion.

## Ownership / workflow
Use main as integration only after reviewed PRs; retain historical branches
until their unique changes are reconciled. One issue per failure, link both
repos and exact test evidence; avoid parallel implementations of the same fix.
Use debug side-by-side for tests and a signed candidate only after all gates.
Do not claim a simulator result as road validation. No driving interaction.
