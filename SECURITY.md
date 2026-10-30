# Security policy

Hearth is in early development. Security fixes target the latest source on
`main`; older snapshots do not have separate maintenance branches.

## Report a vulnerability

Please do not post exploit details, credentials, or private device information
in a public issue or pull request.

Use [GitHub's private reporting form](https://github.com/jameskbb/hearth/security/advisories/new)
when **Report a vulnerability** is available in the Security tab. If it is
unavailable, open an issue titled **Private security contact requested**, with
no vulnerability details. A maintainer can arrange a private channel before
you share the report. No response time is guaranteed.

In the private report, include the affected commit or version, Android or
Fire OS version, steps to reproduce, expected impact, and a suggested fix if
you have one. Use a minimal example without real credentials or household data.

## Scope

Hearth's app, build scripts, theme loading, and dependency configuration are
in scope. Home-button helpers and vendor firmware are separate projects;
report problems in their code to their maintainers. A problem in how Hearth
interacts with them can still be reported here.

The app's network and local-storage behavior is described in
[PRIVACY.md](PRIVACY.md). A passing automated scan is not a security audit or
a guarantee that the software has no vulnerabilities.
