# Security policy

## Reporting a vulnerability

Please do not open a public issue for a security problem.

Report it privately through GitHub: on the repository's **Security** tab choose
**Report a vulnerability**, or go to
<https://github.com/baldarn/takes-app/security/advisories/new>. Only the maintainer sees
the report.

Say what is affected and how to reproduce it: the app version, where the app came from
(Google Play, a GitHub release, or built from source), the device and Android version,
and the steps or a file that shows the problem. If the file is one of your own projects,
remember that it contains your recordings.

What is in scope, for example:

- a project ZIP, a video, a sound, a `.cube` LUT or another imported file that makes the
  app run code, write outside its own storage, or destroy other projects;
- anything that makes the app send data off the device, or use the network for more than
  downloading the speech model the person asked for;
- a download of the speech model, or of a build dependency, that is accepted without
  matching its checksum;
- another app reading Takes' private files through what Takes exposes.

## Supported versions

Fixes are made on `main` and shipped in the next release. Only the latest release, on
Google Play and on the [releases page](https://github.com/baldarn/takes-app/releases),
is supported; older versions do not receive fixes.

## Privacy

What the app collects (nothing) and when it uses the network is described in
[`PRIVACY.md`](PRIVACY.md).
