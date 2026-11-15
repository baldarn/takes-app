<!-- The title is the commit message: one short imperative English sentence saying what the change does. -->

## What this changes

<!-- What a person using the app, or a caller of the code, sees after it. Link the issue: "Closes #123". -->

## The original

<!-- The file and lines of the original app this follows, as paths in its repository (YouRec/…, Tests/…). If it differs from the original, say why. "Not applicable" for build, docs and the like. -->

## How it was checked

<!-- Host tests, emulator, phone (model and Android version). Say what you could not check. -->

## Checklist

- [ ] One change, and `./gradlew :core:test :rnnoise:testDebugUnitTest :speech:testDebugUnitTest :app:lintDebug :app:assembleDebug` passes
- [ ] Core logic has tests, with the original's file and lines where numbers come from it
- [ ] New or changed strings are in `values/` and `values-it/`; new controls have a label for screen readers
- [ ] Preview and export show the same thing
- [ ] No new permission, dependency or network use, and no change to the saved project format, or the issue where it was agreed is linked
- [ ] `docs/PARITY.md` is updated if a gap closed or narrowed
- [ ] No personal data, device serials or local paths in the code, the commits or this description
