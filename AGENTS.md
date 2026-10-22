# Luma TV project guidance

- Read `README.md`, `docs/design.md`, and `docs/validation.md` before continuing development.
- Kotlin / Compose, API 26+, JDK 17. Use the Gradle Wrapper and `scripts/build.ps1`; do not run concurrent Gradle writers.
- Keep UI, application discovery/launching, and local configuration responsibilities separate. Do not add network, telemetry, accounts, services, or fake content without a new product decision.
- Default language is Chinese. All core actions must have a direction / confirm / Back path; long press is only a shortcut.
- Validate focus targets, viewport, clipping, scroll, and return restoration with actual remote events. Build, install, launch, and interaction results are separate evidence tiers.
- Use the existing `BILI_TV_API_34` emulator with an explicit ADB serial. Never clear its data, uninstall existing apps, or change BILI source/data. The `fixture` module is only for new disposable acceptance packages.
- Physical television installation/testing requires separate user authorization. Do not connect to remembered physical TV addresses proactively.
- Before any default Home test, record the original component and recovery command. Keep the original launcher enabled. Restore its role and display configuration after temporary tests.
- This emulator grants HOME role to third-party apps but still resolves/launches the higher-priority original launcher. Report this limitation; do not disable the original launcher or add accessibility interception to bypass it.
- Project-local dependency cache and evidence live under ignored `.gradle-user/` and `artifacts/`. Do not commit APKs, caches, local paths, keys, or device dumps.
