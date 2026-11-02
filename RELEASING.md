# Releasing to Maven Central

Published coordinate: `io.github.ltslab:orafit-jdbc`.

One release is one deployment bundle:

```
io/github/ltslab/orafit-jdbc/<version>/orafit-jdbc-<version>.pom
                                       .jar              # shaded driver + analyzer CLI
                                       -sources.jar
                                       -javadoc.jar
                                       -runtime.tar.gz   # native/ + plain/ SQL
```

Each file carries a detached PGP signature (`.asc`) and checksums. The bundle is
built, tested, and signed by `.github/workflows/central.yml`; the Maven profile
`central` supplies sources, Javadoc, signing, and the Central publisher.

`<version>` is the project version in `pom.xml`. `orafit.extension.version` (the
runtime SQL version) only moves when `extension/src/` changes.

## One-time setup

### 1. Namespace

The repository lives in the GitHub organization `ltslab`, so the namespace is
`io.github.ltslab`. Log in to <https://central.sonatype.com>, add the namespace
under **View Namespaces**, and verify it as the Portal instructs: create a
temporary public repository in `ltslab` named after the verification key, then
press **Verify** and delete that repository once the namespace shows verified.

Do not use `io.github.orafit`: <https://github.com/Orafit> is an unrelated
account, so that namespace cannot be verified. The Java package
`io.github.orafit.*` (and the documented driver class
`io.github.orafit.jdbc.OrafitDriver`) is a separate concern and stays unchanged;
only the Maven coordinate differs.

### 2. Signing key: master stays offline, CI gets one signing subkey

Maven Central accepts a signature only from a key it can fetch from a public
keyserver. CI must not hold the master key, so add a subkey dedicated to
signing, export only that subkey, and store the export as a GitHub secret.

Run this in your own terminal. Passphrases are entered at the pinentry prompt;
they are never written to a file, a log, or the repository.

```bash
gpg --list-secret-keys --keyid-format long
#   note the MASTER fingerprint (the key you keep offline/backed up)

gpg --quick-add-key <MASTER_FPR> rsa4096 sign 2y      # prompts for the master passphrase
gpg --list-secret-keys --keyid-format long --with-keygrip
#   note the new subkey fingerprint (the "ssb" line created just now) as CI_SUBKEY_FPR

# Give the CI copy its own passphrase, so the secret never reveals the master's.
tmp="$(mktemp -d)"; chmod 700 "$tmp"
gpg --armor --export-secret-subkeys <CI_SUBKEY_FPR>! | GNUPGHOME="$tmp" gpg --import
GNUPGHOME="$tmp" gpg -K --with-keygrip <MASTER_FPR>  # keygrip of CI_SUBKEY_FPR
GNUPGHOME="$tmp" gpg-connect-agent "PASSWD <CI_SUBKEY_KEYGRIP>" /bye  # master -> CI passphrase
GNUPGHOME="$tmp" gpg --armor --export-secret-subkeys <CI_SUBKEY_FPR>! > orafit-ci-subkey.asc
GNUPGHOME="$tmp" gpgconf --kill gpg-agent; rm -rf "$tmp"
```

The export holds the primary key with no secret material plus exactly one secret
signing subkey, so a credential leak cannot be used to certify other keys, add
subkeys, or change the key's expiry. Its passphrase differs from the master's,
so the GitHub secret does not unlock the offline backup either.

Back up the master again after adding the subkey (`gpg --armor --export` and
`--export-secret-keys`), and keep the previous backup until the new one has been
imported into an empty keyring and shows the master and every subkey.

Publish the updated public key (it now contains the new subkey) and confirm
propagation from a clean keyring before releasing. `keyserver.ubuntu.com` is
load-balanced and its nodes sync over several minutes, so check repeatedly until
every fetch carries the new subkey; `keys.openpgp.org` serves the UID only after
the address confirms the emailed link:

```bash
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <MASTER_FPR>
gpg --keyserver hkps://keys.openpgp.org     --send-keys <MASTER_FPR>

fresh="$(mktemp -d)"
GNUPGHOME="$fresh" gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys <MASTER_FPR>
GNUPGHOME="$fresh" gpg --verify target/orafit-jdbc-<version>.jar.asc \
                                 target/orafit-jdbc-<version>.jar
rm -rf "$fresh"
```

The UID on the certificate is `ltslab <ltslab@yukkes.com>`, so signatures made
for Orafit also carry that UID. Central does not care; start a separate master
key if you want a distinct publisher identity.

Keep `orafit-ci-subkey.asc` until the local preflight below has verified the
signed bundle, then delete it.

### 3. Central token and GitHub secrets

Create a user token at <https://central.sonatype.com/account>. Publishing needs
two credential pairs, which is four secrets in total:

| Secret | Value | Purpose |
|---|---|---|
| `CENTRAL_USERNAME` | user-token username | Maven `settings.xml` server `central` (via `actions/setup-java`) |
| `CENTRAL_PASSWORD` | user-token password | same |
| `MAVEN_GPG_KEY` | contents of `orafit-ci-subkey.asc` | `maven-gpg-plugin` armor input (`keyEnvName`) |
| `MAVEN_GPG_PASSPHRASE` | passphrase of the signing subkey | `maven-gpg-plugin` (`passphraseEnvName`) |

Store them as **environment secrets** of an environment named `central`
(Settings → Environments → New environment), not as repository secrets: the
workflow declares `environment: central`, so only that job can read them.
Configure the environment with a required reviewer and deployment
branches/tags restricted to the tag pattern `v*`, so every run needs a human
approval and can only start from a release tag before any secret is handed out.

```bash
gh secret set MAVEN_GPG_KEY        --env central -R ltslab/orafit < orafit-ci-subkey.asc
gh secret set MAVEN_GPG_PASSPHRASE --env central -R ltslab/orafit   # prompts
gh secret set CENTRAL_USERNAME     --env central -R ltslab/orafit   # prompts
gh secret set CENTRAL_PASSWORD     --env central -R ltslab/orafit   # prompts
```

`absent` values fail loudly: without `CENTRAL_*` the upload is rejected as
unauthenticated, and without `MAVEN_GPG_KEY` the `verify` phase fails before
the publisher runs.

## Per release

1. Bump `<version>` in `pom.xml`.
2. Run the gate: `./mvnw verify`.
3. Commit, push to `main`, and let CI pass on that exact commit. The workflow
   refuses to publish when no successful `ci.yml` run exists for the commit.
4. Tag and push; `release.yml` creates the GitHub Release and its `SHA256SUMS`:

   ```bash
   git tag v<version> && git push origin v<version>
   ```

5. Actions → **Maven Central** → *Run workflow*, choose the tag `v<version>`
   under **Use workflow from** (the `central` environment rejects runs from a
   branch), enter the version, and leave `publish` unchecked. From the CLI:

   ```bash
   gh workflow run central.yml -R ltslab/orafit --ref v<version> -f version=<version> -f publish=false
   ```

   Approve the pending `central` deployment on the run page. The job builds
   from the tag, runs the full suite, refuses to continue unless the built JAR
   and runtime archive match the `SHA256SUMS` entries of the GitHub Release
   (both builds are byte-reproducible), signs every file, and uploads a
   `USER_MANAGED` deployment that waits for a human.
6. Open <https://central.sonatype.com/publishing/deployments>, confirm the
   validation result, and press **Publish**.
7. Confirm the artifacts are downloadable:

   ```bash
   for f in orafit-jdbc-<version>.pom orafit-jdbc-<version>.jar \
            orafit-jdbc-<version>-sources.jar orafit-jdbc-<version>-javadoc.jar \
            orafit-jdbc-<version>-runtime.tar.gz; do
     printf '%s %s\n' "$(curl -s -o /dev/null -w '%{http_code}' \
       "https://repo1.maven.org/maven2/io/github/ltslab/orafit-jdbc/<version>/$f")" "$f"
   done
   ```

   Propagation to `repo1.maven.org` takes a few minutes; search indexes lag
   longer.

`publish: true` on the workflow skips step 6 and publishes as soon as Central
validates. Use it only when you are watching the run.

## Local preflight (no upload)

Build the exact bundle without Central credentials or uploads. Before the
GitHub Release exists, the files from `./mvnw verify` stand in for its hashes
(both builds are reproducible):

```bash
./mvnw verify
jar_sha256="$(sha256sum target/orafit-jdbc-<version>.jar | cut -d' ' -f1)"
runtime_sha256="$(sha256sum target/orafit-jdbc-<version>-runtime.tar.gz | cut -d' ' -f1)"
read -rs MAVEN_GPG_PASSPHRASE && export MAVEN_GPG_PASSPHRASE
MAVEN_GPG_KEY="$(cat orafit-ci-subkey.asc)" \
  ./mvnw -B -Pcentral -DskipTests -DskipUpload=true \
  -Dorafit.release.jar.sha256="$jar_sha256" \
  -Dorafit.release.runtime.sha256="$runtime_sha256" deploy
unset MAVEN_GPG_PASSPHRASE
unzip -l target/central-publishing/central-bundle.zip
```

A healthy bundle lists 30 files under `io/github/ltslab/orafit-jdbc/<version>/`:
five artifacts, five `.asc`, and the `md5`/`sha1`/`sha256`/`sha512` checksums.

## Constraints

- A published version is immutable. Never reuse it; bump and release again.
- An un-published deployment (`VALIDATED` or `FAILED`) can be withdrawn in the
  Portal and re-uploaded under the same version.
- The publish step is the only irreversible action in this procedure, and it is
  always a human decision unless `publish: true` was chosen explicitly.
