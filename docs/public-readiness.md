# Public repository preparation

Hearth can be shared as an early source project. Making the repository public
does not publish an APK release or establish compatibility with every TV.
Only the maintainer changes repository visibility or publishes releases.

## Publication plan

Prepare a separate private candidate from an audited source snapshot, with
fresh Git history. Keep the original repository, its commits, and its Actions
records private and intact. Do not reset the original history or force-push
the candidate over it.

Verify the candidate's exact revision on CI and review its own retained logs
and artifacts before publication. Repository settings are configured separately;
exporting source does not transfer issues, stars, signing keys, or security
settings. README links use the intended public name, `jameskbb/hearth`.

The maintainer performs the final name swap and visibility change: retain the
original under a distinct private archive name, give the verified candidate
the public project name, then make the candidate public. Check repository
identity, remotes, links, and security settings after the swap. The original
repository remains private.

## What is in place

- A README with a build-and-install path, an honest compatibility summary,
  a theme gallery, and links to detailed guides.
- The MIT license, font notices, weather attribution, and demo trademark notes
  in [third_party](../third_party/README.md). Legal copyright and third-party
  author credits are retained.
- [Contribution guidance](../CONTRIBUTING.md), issue forms, a pull request
  template, [community guidelines](../CODE_OF_CONDUCT.md), a
  [security policy](../SECURITY.md), and [privacy details](../PRIVACY.md).
- CI on hosted runners with read-only repository permissions, no persisted
  checkout credentials, pinned action commits and SDK container, and a
  checksum for the Gradle distribution and secret-scanner download.
- Manual dependency review with pinned versions and the existing minimum-API
  compatibility requirements. No update bot or automatic merging is configured.

## Automated publication checks

The **Public repository safety** CI job uses a full Git checkout and fails on
private network identifiers, hardware addresses, personal machine paths,
signing material or other private/generated tracked files. It checks binary
metadata as well as text, and scans all reachable Git blobs, including files
removed from the current tree. Source text also follows the no-em-dash rule.
Current Markdown relative file links and the two agent rule files are checked.
Regression tests cover deleted history and ensure matched content is withheld.

[Gitleaks](https://github.com/gitleaks/gitleaks), pinned to version 8.30.1 with
its published archive checksum, scans reachable history and an exported tracked
snapshot for secrets. On pushes and manual runs, CI also downloads retained
Actions logs for privacy and secret scans. Logs and scan exports stay in the
temporary hosted runner directory; secret-scanner output is fully redacted.
The checks do not upload private findings as artifacts. No findings baseline
or blanket project allowlist is used.

The log audit normalizes specific hosted toolchain home directories. Only
GitHub's dynamic Dependabot jobs also normalize the updater's own home
directory and Java HTTP proxy-host fields. Private identifiers elsewhere in
those logs still fail. These exceptions never apply to repository content.

Builds, lint, ordinary unit tests, APK compatibility checks, and the Android
API 21 through 36 framework matrix remain part of
[CI](https://github.com/jameskbb/hearth/actions/workflows/ci.yml).
Checks run on CI; this preparation does not require a local emulator or TV.

## Visibility and platform settings

Review a successful CI run for the exact revision you intend to share. GitHub
makes Actions history and logs visible with the repository, so retained logs
in the candidate are included in the audit. The original repository's records
remain private. No release is published by these workflows. Their
APK artifacts use temporary debug signing identities and are development
builds, as explained in [installation](install.md).

GitHub's private vulnerability reporting is available for public repositories.
Enable **Report a vulnerability** in the Security settings after changing
visibility. Until it is available, the security policy provides a way to ask
for private contact without disclosing a vulnerability. This setup does not
assume that the private reporting form is already enabled.

Protected branches may also require a public repository or a paid plan.
After changing visibility, consider requiring the CI checks on `main` before
merging contributor changes. This preparation does not change visibility,
account plans, or branch protection.

## Limits of the review

Automated scanners cannot prove that content is safe or software secure.
They do not recognize every household detail, secret format, or sensitive
item visible inside an image or video. The checked-in media uses emulator
demo content; captions identify it. Keep future screenshots and reports free
of personal content. Commit author credits remain public Git metadata.

The history scan covers fetched reachable refs. Expired or deleted Actions
logs cannot be retrieved. GitHub account secrets, variables, draft releases,
issue attachments, and other service-side data are outside the Git content
scan. Repository visibility does not expose encrypted Actions secret values;
workflows must still avoid printing them. Device-performance and Home-button
limitations remain documented in [testing](testing.md) and
[follow-ups](follow-ups.md).

## GitHub references

- [What changes when a repository becomes public](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility)
- [Configure private vulnerability reporting](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository)
