# `ci/opennav-ci.keystore`

**This is not a release key. Its password is published below. Treat any APK signed with
it as untrusted.**

    alias:    opennav
    password: opennav-ci   (both store and key)
    SHA-256:  38:8D:E4:52:66:DA:0E:30:B1:96:10:0E:CF:91:AF:BC:0E:74:C7:26:D0:28:D7:8E:22:EF:E3:E7:F5:DE:8F:6C

## Why it is committed

Android will not install an APK over one signed by a different key. A CI job that
generates a keystore on the fly produces a different key on every run, so every build
would have to be uninstalled before the next could be installed — which also wipes the
boat profile and any downloaded charts.

Committing one fixed key makes every CI artifact an in-place upgrade of the last, which
is exactly what you want while iterating on a phone. The password has no security value,
so hiding it in a secret would only make the build harder to reproduce without making
anything safer.

## What it must never be used for

Publishing. Anyone who clones the repository can sign an APK that Android will accept as
an upgrade to yours. That is fine for `opennav-ci` builds handed round by URL; it is not
fine for Google Play, F-Droid, or any build you invite strangers to install.

## Using a real key instead

The Gradle config prefers a key supplied through the environment, so a release build only
needs these set:

    OPENNAV_KEYSTORE_PATH      absolute path to the keystore
    OPENNAV_KEYSTORE_PASSWORD  store password
    OPENNAV_KEY_ALIAS          defaults to "opennav"
    OPENNAV_KEY_PASSWORD       defaults to OPENNAV_KEYSTORE_PASSWORD

In GitHub Actions, add the keystore as a base64 secret and decode it in a step before
the build. `.github/workflows/android.yml` does this automatically when the
`RELEASE_KEYSTORE_BASE64` secret exists, and silently falls back to the CI key when it
does not — so the workflow works in a fork with no secrets configured.

Check which key a build used with:

    ./gradlew :app:printSigningReport
