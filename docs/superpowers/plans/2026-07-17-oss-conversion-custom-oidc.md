# Freebase OSS Conversion + Custom OIDC and Branding — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the `freebase` fork from an enterprise license bypass to a genuine OSS (AGPL) Metabase build, reimplementing the only two EE features actually in use — OIDC SSO and Waterloo branding — as original code against Metabase's OSS extension points.

**Architecture:** Three phases ship in a single cutover. Phase 1 reverts the bypass and flips the build to `MB_EDITION=oss`. Phase 2 adds an OIDC connector modeled directly on `metabase.sso.providers.slack-connect` — an existing OSS provider that already does `(derive :provider/slack-connect :provider/oidc)` — pointed at Authentik. Phase 3 defines our own `wc-brand-*` settings and feeds them into the AGPL theming pipeline, which already accepts colors as a parameter.

**Tech Stack:** Clojure (Toucan 2, Malli, Methodical, Ring, `api.macros/defendpoint`), TypeScript/React (Mantine, Redux), Docker Compose. Tests: `clojure.test` via `./bin/test-agent`; Jest for frontend.

**Spec:** `docs/superpowers/specs/2026-07-17-oss-conversion-custom-oidc-design.md`

## Global Constraints

Every task's requirements implicitly include this section.

- **REWRITE, NEVER BYPASS.** Never remove or weaken a Metabase license check to unlock a Metabase feature. Specifically: never delete `:feature :whitelabel` from `src/metabase/appearance/settings.clj`, never re-patch `src/metabase/premium_features/token_check.clj`, never re-gut `+require-premium-feature`. If a task seems to require it, stop and escalate — the task is wrong.
  - **Sole exception:** Task 13 Step 3 temporarily removes one `:feature :whitelabel` gate *locally and uncommitted*, solely to prove the anti-bypass test fails when it should, then restores it immediately. This is verification of the guard, not a bypass. It is the only sanctioned removal, it must never be committed, and Step 3 restores the file before Step 4 commits.
- **Never ADD an import from `metabase-enterprise.*`** to any file under `src/` or `frontend/src/`. No copying EE code.
  - **Do not remove upstream's existing conditional EE resolution.** `src/metabase/server/auth_wrapper.clj` already contains `(requiring-resolve 'metabase-enterprise.sso.api.routes/routes)` guarded by `config/ee-available?`. That is upstream AGPL code that gracefully degrades in OSS builds — **preserve it verbatim**. Task 6 adds an OSS route beside it; it does not touch that fallback. Deleting it would be an unrelated regression, not a licensing improvement.
- **Target upstream tag:** `v0.62.3.3`. Reverts go to that tag exactly.
- **Build edition:** `MB_EDITION=oss`. `enterprise/backend/src` must never be on the classpath.
- **`enterprise/` directory stays in the tree, untouched,** with `LICENSE.txt` restored to its upstream content.
- **Provider key:** `:provider/free-oidc`. Never `:provider/custom-oidc` (that is EE's).
- **Setting names:** the OIDC connector uses `free-oidc-*` (env `MB_FREE_OIDC_*`). This prefix is deliberate: enterprise's `metabase_enterprise/sso/settings.clj` defines settings literally named `oidc-enabled`/`oidc-configured`, and `defsetting` **throws** on a duplicate registration, so bare `oidc-*` names make the app unbootable whenever EE is on the classpath (including the default EE-inclusive `./bin/test-agent` alias). `free-oidc-*` avoids the collision. Consequence: `sso-source-enabled?` no longer picks our setting up for free — its `:oidc` case must be patched to read `free-oidc-enabled` (done in Task 3). Branding uses `wc-brand-*` (deliberate — must NOT collide with Metabase's gated `application-*`).
- **Provider key:** `:provider/free-oidc`. Never `:provider/custom-oidc` (EE's) or `:provider/oidc` (the AGPL base we derive from). The user-facing identity stays `oidc`: the route is `/auth/sso/oidc`, the stamped `sso_source` is `:oidc`, the login-button provider name is `oidc`. Only the internal setting keys and provider key carry `free-`.
- **IdP:** Authentik at `https://sso.waterloocap.com`. Issuer form: `https://sso.waterloocap.com/application/o/<slug>/`.
- **Branding values (verbatim from prod):** `application-name` = `"Waterloo"`; brand color `#3E90C5`.
- **Backend test command:** `./bin/test-agent :only '[metabase.some-test]'`. Never `clj -X:dev:test` — its progress-bar output is unparseable.
- **Toolchain (REQUIRED — tests will not run without it).** Clojure and OpenJDK 21 were installed via brew on 2026-07-17, but `openjdk@21` is **keg-only**, so it is not on the default `PATH`. Every backend test invocation must export these first, in the same shell command (shell state does not persist between tool calls):

  ```bash
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
  export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
  ./bin/test-agent :only '[metabase.some-test]'
  ```

  Without this you get `exec: clojure: not found` or `Unable to locate a Java Runtime`. **If tests cannot run, report BLOCKED — never report success on tests you did not execute.**
- **Never report a test result you did not observe.** "Expected: PASS" is not a result. If you cannot run a test, say so and report BLOCKED.
- **Commit after every task.** Never `--no-verify`.

---

## File Structure

**Phase 1 — Strip (revert only, no new files)**

| File | Responsibility |
|---|---|
| `src/metabase/premium_features/token_check.clj` | REVERT to upstream. Real token check, metering, `*token-features*`. |
| `src/metabase/premium_features/settings.clj` | REVERT. Remove forced `development-mode? => false`. |
| `enterprise/backend/src/metabase_enterprise/api/routes/common.clj` | REVERT. Restore `+require-premium-feature`. |
| `enterprise/LICENSE.txt` | RESTORE upstream commercial license text. |
| `src/metabase/analytics/settings.clj` | REVERT. Telemetry moves to env vars. |
| `src/metabase/version/settings.clj` | REVERT. Update check moves to env var. |
| `docker-compose.yml` | `MB_EDITION: oss` + telemetry env vars. |
| `test/metabase/premium_features/oss_build_test.clj` | NEW. Proves the bypass is gone. |

**Phase 2 — OIDC connector**

| File | Responsibility |
|---|---|
| `src/metabase/sso/settings.clj` | MODIFY. Add `oidc-*` settings; relax `ee-sso-configured?`. |
| `src/metabase/sso/providers/free_oidc.clj` | NEW. `(derive :provider/free-oidc :provider/oidc)` + config builder. |
| `src/metabase/sso/integrations/free_oidc.clj` | NEW. `sso-initiate` / `sso-callback`. |
| `src/metabase/sso/api/oidc.clj` | NEW. `/auth/sso/oidc` routes. |
| `src/metabase/sso/api/oidc_settings.clj` | NEW. `PUT /api/oidc/settings` admin API. |
| `src/metabase/sso/api.clj` | MODIFY. Expose `oidc-settings-routes`. |
| `src/metabase/api_routes/routes.clj` | MODIFY. Mount `/api/oidc`. |
| `src/metabase/server/auth_wrapper.clj` | MODIFY. Mount `/auth/sso/oidc` beside `/slack-connect`. |
| `src/metabase/sso/init.clj` | MODIFY. Require the provider ns. |
| `frontend/src/metabase/plugins/builtin/auth/oidc.ts` | NEW. Login button registration. |
| `frontend/src/metabase/auth/components/OidcButton/OidcButton.tsx` | NEW. The button. |

**Phase 3 — Branding**

| File | Responsibility |
|---|---|
| `src/metabase/branding/settings.clj` | NEW. `wc-brand-*` settings (ungated — ours). |
| `src/metabase/branding/api.clj` | NEW. `PUT /api/branding/settings`. |
| `resources/migrations/001_wc_brand_migration.clj` | NEW. Copy `application-*` → `wc-brand-*`. |
| `frontend/src/metabase/AppThemeProvider.tsx` | MODIFY. Seed colors from `wc-brand-colors`. |
| `frontend/src/metabase/ui/colors/colors.ts` | MODIFY. Static palette from `wc-brand-colors`. |
| `frontend/src/metabase/common/components/LogoIcon/LogoIcon.tsx` | MODIFY. Render `wc-brand-logo-url`. |
| `test/metabase/branding/anti_bypass_test.clj` | NEW. **The licensing line, executable.** |

---

## Phase 1 — Strip the bypass

### Task 1: Revert the license bypass to upstream

**Files:**
- Modify: `src/metabase/premium_features/token_check.clj` (revert)
- Modify: `src/metabase/premium_features/settings.clj:249-253` (revert)
- Modify: `enterprise/backend/src/metabase_enterprise/api/routes/common.clj` (revert)
- Modify: `enterprise/LICENSE.txt` (restore)
- Test: `test/metabase/premium_features/oss_build_test.clj`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: a build where `(premium-features/has-feature? :sandboxes)` is `false` and `*token-features*` returns `#{}` when no token is set. Later tasks rely on this being the real upstream behavior.

- [ ] **Step 1: Write the failing test**

Create `test/metabase/premium_features/oss_build_test.clj`:

```clojure
(ns metabase.premium-features.oss-build-test
  "Proves the enterprise license bypass is gone.

  These tests fail loudly if anyone re-patches token_check.clj. That is the point:
  they are the executable form of this fork's licensing posture."
  (:require
   [clojure.test :refer :all]
   [metabase.premium-features.core :as premium-features]
   [metabase.premium-features.token-check :as token-check]
   [metabase.test :as mt]))

(deftest no-bypass-features-test
  (testing "with no token configured, no premium features are available"
    (mt/with-temporary-setting-values [premium-embedding-token nil]
      (is (= #{} (token-check/*token-features*))
          "*token-features* must be empty without a token. If this returns a large set, the bypass is back."))))

(deftest premium-features-are-actually-gated-test
  (testing "representative premium features are unavailable without a token"
    (mt/with-temporary-setting-values [premium-embedding-token nil]
      (doseq [feature [:sandboxes :whitelabel :sso-oidc :serialization :audit-app]]
        (is (false? (boolean (premium-features/has-feature? feature)))
            (format "%s must be gated without a token" feature))))))

(deftest plan-alias-is-not-forged-test
  (testing "plan-alias is not hardcoded to enterprise-unlimited"
    (mt/with-temporary-setting-values [premium-embedding-token nil]
      (is (not= "enterprise-unlimited" (token-check/plan-alias))
          "plan-alias must not be forged. If this fails, the bypass is back."))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test-agent :only '[metabase.premium-features.oss-build-test]'`

Expected: FAIL. `*token-features*` returns the 58-element bypass set, not `#{}`. `plan-alias` returns `"enterprise-unlimited"`.

- [ ] **Step 3: Revert the four bypass files to upstream**

```bash
git checkout v0.62.3.3 -- \
  src/metabase/premium_features/token_check.clj \
  src/metabase/premium_features/settings.clj \
  enterprise/backend/src/metabase_enterprise/api/routes/common.clj \
  enterprise/LICENSE.txt
```

- [ ] **Step 4: Verify the revert removed the bypass**

```bash
# All three must print nothing.
grep -n "bypass-features\|bypass-token-response" src/metabase/premium_features/token_check.clj
grep -n "Bypass" enterprise/backend/src/metabase_enterprise/api/routes/common.clj
git diff v0.62.3.3 HEAD -- src/metabase/premium_features/ enterprise/backend/src/metabase_enterprise/api/routes/common.clj enterprise/LICENSE.txt
```

Expected: no output from any command. `enterprise/LICENSE.txt` is non-empty:

```bash
wc -c enterprise/LICENSE.txt   # expect ~400 bytes, not 0
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./bin/test-agent :only '[metabase.premium-features.oss-build-test]'`

Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/metabase/premium_features/ \
        enterprise/backend/src/metabase_enterprise/api/routes/common.clj \
        enterprise/LICENSE.txt \
        test/metabase/premium_features/oss_build_test.clj
git commit -m "Revert the enterprise license bypass to upstream v0.62.3.3

Restores the real MetaStore token check, metering, *token-features*, the
+require-premium-feature route gating, and the commercial license text
that the fork had emptied.

Adds oss_build_test as a standing guard: it fails loudly if the bypass
is ever reintroduced."
```

---

### Task 2: Move telemetry off source patches onto env vars, flip build to OSS

**Files:**
- Modify: `src/metabase/analytics/settings.clj` (revert)
- Modify: `src/metabase/version/settings.clj` (revert)
- Modify: `docker-compose.yml`
- Test: `test/metabase/premium_features/oss_build_test.clj` (extend)

**Interfaces:**
- Consumes: Task 1's reverted `token_check.clj`.
- Produces: `docker-compose.yml` with `MB_EDITION: oss`. Fork delta on the two telemetry files is zero.

**Why:** The bypass hardcoded telemetry getters to `false`. That was never necessary — `anon-tracking-enabled`, `snowplow-available` and `check-for-updates` are all standard settings with env-var backing. Reverting the source and setting env vars gets the same behavior with zero fork delta.

- [ ] **Step 1: Write the failing test**

Append to `test/metabase/premium_features/oss_build_test.clj`:

```clojure
(deftest telemetry-settings-are-upstream-test
  (testing "telemetry settings are plain upstream settings, not hardcoded getters"
    (testing "anon-tracking-enabled respects its stored value rather than forcing false"
      (mt/with-temporary-setting-values [anon-tracking-enabled true]
        (is (true? (analytics.settings/anon-tracking-enabled))
            "A hardcoded (fn [] false) getter would make this impossible to set.")))
    (testing "check-for-updates respects its stored value rather than forcing false"
      (mt/with-temporary-setting-values [check-for-updates true]
        (is (true? (version.settings/check-for-updates))
            "A hardcoded (fn [] false) getter would make this impossible to set.")))))
```

Add to the `:require` block:

```clojure
   [metabase.analytics.settings :as analytics.settings]
   [metabase.version.settings :as version.settings]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test-agent :only '[metabase.premium-features.oss-build-test/telemetry-settings-are-upstream-test]'`

Expected: FAIL. The forked getters return `false` regardless of the stored value.

- [ ] **Step 3: Revert the telemetry files**

```bash
git checkout v0.62.3.3 -- \
  src/metabase/analytics/settings.clj \
  src/metabase/version/settings.clj
```

Verify the fork delta is now zero on both:

```bash
git diff v0.62.3.3 HEAD -- src/metabase/analytics/settings.clj src/metabase/version/settings.clj
```

Expected: no output.

- [ ] **Step 4: Run test to verify it passes**

Run: `./bin/test-agent :only '[metabase.premium-features.oss-build-test]'`

Expected: PASS (4 tests).

- [ ] **Step 5: Flip the build to OSS and set telemetry env vars**

Edit `docker-compose.yml`. Change `MB_EDITION: ee` to `oss` and add the env vars:

```yaml
  freebase:
    build:
      context: .
      args:
        MB_EDITION: oss
        VERSION: freebase-1.0
    container_name: freebase-app
    ports:
      - "3000:3000"
    environment:
      MB_DB_TYPE: postgres
      MB_DB_DBNAME: metabase
      MB_DB_PORT: 5432
      MB_DB_USER: metabase_user
      MB_DB_PASS: metabase_password
      MB_DB_HOST: db
      # Telemetry off. These replace the fork's old hardcoded getters —
      # same behavior, zero fork delta. Do NOT re-patch the source.
      MB_ANON_TRACKING_ENABLED: "false"
      MB_SNOWPLOW_AVAILABLE: "false"
      MB_CHECK_FOR_UPDATES: "false"
      # OIDC (Authentik). Set the real values in the deploy environment.
      MB_FREE_OIDC_ENABLED: "true"
      MB_FREE_OIDC_ISSUER_URI: "https://sso.waterloocap.com/application/o/metabase/"
      MB_FREE_OIDC_CLIENT_ID: "${MB_FREE_OIDC_CLIENT_ID}"
      MB_FREE_OIDC_CLIENT_SECRET: "${MB_FREE_OIDC_CLIENT_SECRET}"
```

- [ ] **Step 6: Verify the OSS build excludes enterprise from the classpath**

```bash
grep -n "MB_EDITION" docker-compose.yml   # expect: oss
```

- [ ] **Step 7: Commit**

```bash
git add src/metabase/analytics/settings.clj src/metabase/version/settings.clj \
        docker-compose.yml test/metabase/premium_features/oss_build_test.clj
git commit -m "Move telemetry off source patches onto env vars; build OSS

Reverts analytics/settings.clj and version/settings.clj to upstream and
sets MB_ANON_TRACKING_ENABLED / MB_SNOWPLOW_AVAILABLE /
MB_CHECK_FOR_UPDATES instead. Same behavior, zero fork delta.

Flips MB_EDITION to oss so enterprise/backend/src never reaches the
classpath."
```

---

## Phase 2 — Custom OIDC connector

### Task 3: OIDC settings

**Files:**
- Modify: `src/metabase/sso/settings.clj` (add settings near the slack-connect block, ~line 232; patch the `:oidc` case of `sso-source-enabled?`)
- Test: `test/metabase/sso/free_oidc_settings_test.clj`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces, all in `metabase.sso.settings`:
  - `(free-oidc-enabled)` → `boolean`
  - `(free-oidc-configured)` → `boolean`
  - `(free-oidc-issuer-uri)` → `string?`
  - `(free-oidc-client-id)` → `string?`
  - `(free-oidc-client-secret)` → masked `string?`
  - `(unobfuscated-free-oidc-client-secret)` → raw `string?`
  - `(free-oidc-scopes)` → `string` (space-separated)

**Why `free-oidc-*` and a `sso-source-enabled?` patch:** enterprise's `sso/settings.clj` already defines `oidc-enabled`/`oidc-configured`, and `defsetting` throws on duplicate registration — so bare `oidc-*` names crash the app under any EE-inclusive build. We use `free-oidc-*` to avoid that. Upstream's `sso-source-enabled?` (`src/metabase/sso/settings.clj:312`) dispatches `:oidc → (setting/get :oidc-enabled)`, which in an OSS build refers to a setting that no longer exists — so this task patches that one case to read our `free-oidc-enabled` instead. The stamped `sso_source` stays `:oidc`, so this is the only line that needs to change.

- [ ] **Step 1: Write the failing test**

Create `test/metabase/sso/free_oidc_settings_test.clj`:

```clojure
(ns metabase.sso.free-oidc-settings-test
  (:require
   [clojure.test :refer :all]
   [metabase.settings.core :as setting]
   [metabase.sso.settings :as sso.settings]
   [metabase.test :as mt]))

(deftest free-oidc-configured-test
  (testing "free-oidc-configured is true only when all three mandatory settings are present"
    (mt/with-temporary-setting-values [free-oidc-client-id nil, free-oidc-client-secret nil, free-oidc-issuer-uri nil]
      (is (false? (sso.settings/free-oidc-configured))))
    (mt/with-temporary-setting-values [free-oidc-client-id "abc", free-oidc-client-secret nil, free-oidc-issuer-uri nil]
      (is (false? (sso.settings/free-oidc-configured)) "client-id alone is not enough"))
    (mt/with-temporary-setting-values [free-oidc-client-id     "abc"
                                       free-oidc-client-secret "shh"
                                       free-oidc-issuer-uri    "https://sso.waterloocap.com/application/o/metabase/"]
      (is (true? (sso.settings/free-oidc-configured))))))

(deftest free-oidc-enabled-requires-configured-test
  (testing "free-oidc-enabled cannot be true unless OIDC is configured"
    (mt/with-temporary-setting-values [free-oidc-enabled true, free-oidc-client-id nil, free-oidc-client-secret nil, free-oidc-issuer-uri nil]
      (is (false? (sso.settings/free-oidc-enabled))
          "enabled must be false when unconfigured, so a half-set config cannot lock anyone out"))))

(deftest client-secret-is-masked-test
  (testing "the client secret getter masks, and the unobfuscated accessor does not"
    (mt/with-temporary-setting-values [free-oidc-client-secret "super-secret-value"]
      (is (not= "super-secret-value" (sso.settings/free-oidc-client-secret))
          "the public getter must mask")
      (is (= "super-secret-value" (sso.settings/unobfuscated-free-oidc-client-secret))
          "the unobfuscated accessor returns the real value"))))

(deftest sso-source-enabled-recognises-oidc-test
  (testing "sso-source-enabled? :oidc reads our free-oidc-enabled setting (patched case)"
    (mt/with-temporary-setting-values [free-oidc-enabled       true
                                       free-oidc-client-id     "abc"
                                       free-oidc-client-secret "shh"
                                       free-oidc-issuer-uri    "https://sso.waterloocap.com/application/o/metabase/"]
      (is (true? (sso.settings/sso-source-enabled? :oidc))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test-agent :only '[metabase.sso.free-oidc-settings-test]'`

Expected: FAIL — settings don't exist yet.

- [ ] **Step 3: Add the settings**

In `src/metabase/sso/settings.clj`, immediately after the `slack-connect-enabled` block (~line 229) and before the `;;; Google Auth` comment, insert:

```clojure
;;;
;;; OIDC (Authentik) — the "free-oidc" connector
;;;
;;; Our own OIDC connector settings. Named `free-oidc-*` (NOT `oidc-*`) because the
;;; enterprise SSO module registers settings literally named `oidc-enabled`/`oidc-configured`,
;;; and defsetting throws on a duplicate registration -- bare `oidc-*` names would make the
;;; app unbootable under any EE-inclusive build. The stamped sso_source stays :oidc, so the
;;; `:oidc` case of `sso-source-enabled?` below is patched to read `free-oidc-enabled`.

(defsetting free-oidc-issuer-uri
  (deferred-tru "Issuer URI for your OIDC provider, e.g. https://sso.example.com/application/o/metabase/")
  :encryption :no
  :export?    false
  :audit      :getter)

(defsetting free-oidc-client-id
  (deferred-tru "Client ID for your OIDC application")
  :encryption :no
  :export?    false
  :audit      :getter)

(defsetting free-oidc-client-secret
  (deferred-tru "Client Secret for your OIDC application")
  :encryption :when-encryption-key-set
  :export?    false
  :audit      :no-value
  :getter     (fn []
                (-> (setting/get-value-of-type :string :free-oidc-client-secret)
                    (u.str/mask 4))))

(defn unobfuscated-free-oidc-client-secret
  "Get the unobfuscated value of [[free-oidc-client-secret]]."
  []
  (setting/get-value-of-type :string :free-oidc-client-secret))

(defsetting free-oidc-scopes
  (deferred-tru "Space-separated OAuth2 scopes to request from the OIDC provider.")
  :encryption :no
  :export?    false
  :default    "openid email profile"
  :audit      :getter)

(defsetting free-oidc-configured
  (deferred-tru "Are the mandatory OIDC settings configured?")
  :type    :boolean
  :export? false
  :default false
  :setter  :none
  :getter  (fn [] (boolean
                   (and (free-oidc-client-id)
                        (setting/get-value-of-type :string :free-oidc-client-secret)
                        (free-oidc-issuer-uri)))))

(defsetting free-oidc-enabled
  (deferred-tru "Is OIDC authentication configured and enabled?")
  :type       :boolean
  :export?    false
  :default    false
  ;; :public so the unauthenticated login page can decide whether to show the SSO button.
  ;; The client-id/secret/issuer settings above are intentionally NOT public.
  :visibility :public
  :audit      :getter
  :getter     (fn []
                (if (free-oidc-configured)
                  (setting/get-value-of-type :boolean :free-oidc-enabled)
                  false)))
```

Note `free-oidc-configured` reads the secret via `setting/get-value-of-type` rather than the masked `(free-oidc-client-secret)` getter — the mask of a nil value could otherwise read as truthy.

- [ ] **Step 4: Patch the `:oidc` case of `sso-source-enabled?`**

Because our setting is `free-oidc-enabled` (not `oidc-enabled`), upstream's `:oidc` case in `sso-source-enabled?` (`src/metabase/sso/settings.clj`, ~line 312) now points at a setting that does not exist in an OSS build. Change that one line so the stamped `sso_source :oidc` resolves to our connector:

```clojure
     ;; was: :oidc   (setting/get :oidc-enabled)
     :oidc   (free-oidc-enabled)
```

Leave every other case (`:google`, `:ldap`, `:saml`, `:jwt`, `:slack`, `:scim`) untouched. Do not touch `ee-sso-configured?` — Task 7 handles that.

- [ ] **Step 5: Run test to verify it passes**

Run: `./bin/test-agent :only '[metabase.sso.free-oidc-settings-test]'`

Expected: PASS (4 tests), including `sso-source-enabled-recognises-oidc-test`.

- [ ] **Step 6: Commit**

```bash
git add src/metabase/sso/settings.clj test/metabase/sso/free_oidc_settings_test.clj
git commit -m "Add free-oidc connector settings

Named free-oidc-* to avoid colliding with enterprise's oidc-enabled /
oidc-configured settings, which would make defsetting throw on duplicate
registration under any EE-inclusive build. Patches the :oidc case of
sso-source-enabled? to read free-oidc-enabled, since the stamped
sso_source stays :oidc.

Mirrors the slack-connect settings shape, including masked-secret
handling and the configured?/enabled? pair that prevents a half-set
config from being marked enabled."
```

---

### Task 4: OIDC provider

**Files:**
- Create: `src/metabase/sso/providers/free_oidc.clj`
- Modify: `src/metabase/sso/init.clj`
- Test: `test/metabase/sso/providers/free_oidc_test.clj`

**Interfaces:**
- Consumes: `metabase.sso.settings/{free-oidc-enabled,free-oidc-configured,free-oidc-client-id,unobfuscated-free-oidc-client-secret,free-oidc-issuer-uri,free-oidc-scopes}` from Task 3.
- Produces:
  - Provider key `:provider/free-oidc`, deriving from `:provider/oidc`.
  - `metabase.sso.providers.free-oidc/check-sso-redirect` → `(fn [redirect-url] redirect-url)`, throws 400 on open-redirect.
  - `authenticate` returns `{:success? true :user-data {... :sso_source :oidc}}` / `{:success? :redirect :redirect-url ...}` / `{:success? false :error kw :message str}`.

**Why this is tiny:** the OSS base `:provider/oidc` already implements the full authorization-code flow. We build a config map, `assoc` it as `:oidc-config`, and delegate via `next-method`. This mirrors `metabase.sso.providers.slack-connect` exactly.

- [ ] **Step 1: Write the failing test**

Create `test/metabase/sso/providers/free_oidc_test.clj`:

```clojure
(ns metabase.sso.providers.free-oidc-test
  (:require
   [clojure.test :refer :all]
   [metabase.auth-identity.core :as auth-identity]
   [metabase.sso.providers.free-oidc :as free-oidc]
   [metabase.test :as mt]))

(deftest provider-derives-from-base-oidc-test
  (testing "our provider inherits the AGPL base OIDC flow"
    (is (isa? :provider/free-oidc :provider/oidc)
        "must derive from the OSS base provider so we inherit discovery, token exchange, and ID-token validation")
    (is (isa? :provider/free-oidc :metabase.auth-identity.provider/create-user-if-not-exists)
        "must auto-provision: Authentik is the gatekeeper")))

(deftest authenticate-rejects-when-disabled-test
  (testing "authenticate refuses when OIDC is disabled"
    (mt/with-temporary-setting-values [free-oidc-enabled false]
      (let [result (auth-identity/authenticate :provider/free-oidc {})]
        (is (false? (:success? result)))
        (is (= :oidc-not-enabled (:error result)))))))

(deftest authenticate-rejects-when-unconfigured-test
  (testing "authenticate refuses when OIDC is enabled but not configured"
    (mt/with-temporary-setting-values [free-oidc-enabled true, free-oidc-client-id nil, free-oidc-client-secret nil, free-oidc-issuer-uri nil]
      (let [result (auth-identity/authenticate :provider/free-oidc {})]
        (is (false? (:success? result)))))))

(deftest check-sso-redirect-blocks-open-redirect-test
  (testing "relative redirects are allowed"
    (is (= "/dashboard/1" (free-oidc/check-sso-redirect "/dashboard/1"))))
  (testing "external hosts are rejected"
    (is (thrown? clojure.lang.ExceptionInfo (free-oidc/check-sso-redirect "https://evil.example.com/steal")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test-agent :only '[metabase.sso.providers.free-oidc-test]'`

Expected: FAIL — namespace does not exist.

- [ ] **Step 3: Write the provider**

Create `src/metabase/sso/providers/free_oidc.clj`:

```clojure
(ns metabase.sso.providers.free-oidc
  "Waterloo OIDC authentication provider (Authentik).

  Derives from the base OSS OIDC provider [[metabase.sso.providers.oidc]], which already
  implements the full authorization-code flow: discovery, token exchange, ID token
  validation against JWKS, encrypted state cookies, and claim extraction. This namespace
  only supplies configuration from settings and delegates.

  Modeled on [[metabase.sso.providers.slack-connect]], which is the existing OSS example
  of an OIDC-based SSO provider."
  (:require
   [clojure.string :as str]
   [metabase.auth-identity.core :as auth-identity]
   [metabase.sso.settings :as sso-settings]
   [metabase.util.i18n :refer [tru]]
   [methodical.core :as methodical]))

(set! *warn-on-reflection* true)

;;; -------------------------------------------------- Provider Registration --------------------------------------------------

;; Derive ONLY from :provider/oidc. The base :provider/oidc itself already derives from
;; :metabase.auth-identity.provider/create-user-if-not-exists (see providers/oidc.clj), so
;; we inherit auto-provisioning transitively — exactly as slack-connect does. Do NOT add an
;; explicit second derive to create-user-if-not-exists: it is redundant AND it creates a
;; diamond in Clojure's shared global hierarchy that breaks when unrelated code calls
;; `underive` (observed: metabase.transforms.models.transform blew up the whole suite).
(derive :provider/free-oidc :provider/oidc)

(def provider-name
  "Provider name for Waterloo OIDC authentication."
  "oidc")

;;; -------------------------------------------------- Configuration --------------------------------------------------

(defn- build-oidc-config
  "Build the OIDC configuration map the base provider expects.

  Authentik emits `email`, `given_name` and `family_name`, which match the base
  provider's defaults exactly, so no attribute mapping is needed."
  [request]
  (when (sso-settings/free-oidc-configured)
    {:client-id     (sso-settings/free-oidc-client-id)
     :client-secret (sso-settings/unobfuscated-free-oidc-client-secret)
     :issuer-uri    (sso-settings/free-oidc-issuer-uri)
     :scopes        (vec (remove str/blank? (str/split (or (sso-settings/free-oidc-scopes) "") #"\s+")))
     :redirect-uri  (get request :redirect-uri)}))

;;; -------------------------------------------------- Open Redirect Guard --------------------------------------------------

(defn check-sso-redirect
  "Check if open redirect is being exploited in SSO. If so, or if the redirect-url is
  invalid, throw a 400."
  [redirect-url]
  (try
    (let [redirect (some-> redirect-url (java.net.URI.))
          our-host (some-> ((requiring-resolve 'metabase.system.core/site-url)) (java.net.URI.) (.getHost))]
      (when-not (or (nil? redirect-url)
                    (and (nil? (.getHost redirect))
                         (nil? (.getScheme redirect)))
                    (= (.getHost redirect) our-host))
        (throw (ex-info (tru "Invalid redirect URL")
                        {:status-code  400
                         :redirect-url redirect-url})))
      redirect-url)
    (catch java.net.URISyntaxException _
      (throw (ex-info (tru "Invalid redirect URL")
                      {:status-code  400
                       :redirect-url redirect-url})))))

;;; -------------------------------------------------- Authentication --------------------------------------------------

(methodical/defmethod auth-identity/authenticate :provider/free-oidc
  [_provider request]
  (cond
    (not (sso-settings/free-oidc-enabled))
    {:success? false
     :error    :oidc-not-enabled
     :message  (tru "OIDC authentication is not enabled")}

    (not (sso-settings/free-oidc-configured))
    {:success? false
     :error    :oidc-not-configured
     :message  (tru "OIDC is not configured")}

    :else
    (let [oidc-config (build-oidc-config request)]
      (if-not oidc-config
        {:success? false
         :error    :configuration-error
         :message  (tru "Failed to build OIDC configuration")}
        (let [auth-result (next-method _provider (assoc request :oidc-config oidc-config))]
          (if (and (:success? auth-result)
                   (:user-data auth-result))
            (assoc-in auth-result [:user-data :sso_source] :oidc)
            auth-result))))))
```

- [ ] **Step 4: Register the provider at init**

Edit `src/metabase/sso/init.clj`:

```clojure
(ns metabase.sso.init
  (:require
   [metabase.sso.providers.google]
   [metabase.sso.providers.ldap]
   [metabase.sso.providers.oidc]
   [metabase.sso.providers.slack-connect]
   [metabase.sso.providers.free-oidc]
   [metabase.sso.settings]))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./bin/test-agent :only '[metabase.sso.providers.free-oidc-test]'`

Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/metabase/sso/providers/free_oidc.clj src/metabase/sso/init.clj \
        test/metabase/sso/providers/free_oidc_test.clj
git commit -m "Add :provider/free-oidc deriving from the OSS base OIDC provider

The AGPL base :provider/oidc already implements the whole
authorization-code flow -- discovery, JWKS, ID token validation,
encrypted state cookies, claim extraction. This provider only builds
config from settings and delegates via next-method, exactly as the
existing OSS slack-connect provider does.

No enterprise code is used or copied."
```

---

### Task 5: OIDC integration — initiate and callback

**Files:**
- Create: `src/metabase/sso/integrations/free_oidc.clj`
- Test: `test/metabase/sso/integrations/free_oidc_test.clj`

**Interfaces:**
- Consumes: `:provider/free-oidc` and `check-sso-redirect` from Task 4; `free-oidc-enabled` from Task 3.
- Produces:
  - `metabase.sso.integrations.free-oidc/sso-initiate` → `(fn [request] ring-response)`
  - `metabase.sso.integrations.free-oidc/sso-callback` → `(fn [request] ring-response)`
  - Callback URI is `{site-url}/auth/sso/oidc/callback` — **this exact string must be registered in Authentik.**

- [ ] **Step 1: Write the failing test**

Create `test/metabase/sso/integrations/free_oidc_test.clj`:

```clojure
(ns metabase.sso.integrations.free-oidc-test
  (:require
   [clojure.test :refer :all]
   [metabase.sso.integrations.free-oidc :as free-oidc.integration]
   [metabase.test :as mt]))

(deftest initiate-rejects-when-disabled-test
  (testing "initiating SSO when OIDC is disabled throws a 400 rather than redirecting"
    (mt/with-temporary-setting-values [free-oidc-enabled false]
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (free-oidc.integration/sso-initiate {:params {}})))]
        (is (= 400 (:status-code (ex-data e))))))))

(deftest callback-rejects-when-disabled-test
  (testing "the callback refuses when OIDC is disabled"
    (mt/with-temporary-setting-values [free-oidc-enabled false]
      (is (thrown? clojure.lang.ExceptionInfo
                   (free-oidc.integration/sso-callback {:params {:code "x" :state "y"}}))))))

(deftest redirect-uri-is-stable-test
  (testing "the callback URI matches what must be registered in Authentik"
    (mt/with-temporary-setting-values [site-url "https://analytics.waterloocap.com"]
      (is (= "https://analytics.waterloocap.com/auth/sso/oidc/callback"
             (#'free-oidc.integration/oidc-redirect-uri))
          "If this changes, the Authentik application config must change too."))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test-agent :only '[metabase.sso.integrations.free-oidc-test]'`

Expected: FAIL — namespace does not exist.

- [ ] **Step 3: Write the integration**

Create `src/metabase/sso/integrations/free_oidc.clj`:

```clojure
(ns metabase.sso.integrations.free-oidc
  "Waterloo OIDC (Authentik) SSO backend.

  Flow:
  1. User hits GET /auth/sso/oidc
  2. Metabase redirects to Authentik's authorization endpoint
  3. User authenticates with Authentik
  4. Authentik redirects to GET /auth/sso/oidc/callback?code=...&state=...
  5. Metabase exchanges the code for tokens and creates a session

  Modeled on [[metabase.sso.integrations.slack-connect]]."
  (:require
   [java-time.api :as t]
   [metabase.api.common :as api]
   [metabase.auth-identity.core :as auth-identity]
   [metabase.request.core :as request]
   [metabase.sso.core :as sso]
   [metabase.sso.providers.free-oidc :as free-oidc.provider]
   [metabase.sso.settings :as sso-settings]
   [metabase.system.core :as system]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [ring.util.response :as response]))

(set! *warn-on-reflection* true)

(defn- oidc-redirect-uri
  "The redirect URI registered with Authentik. Must match the Authentik application config."
  []
  (str (system/site-url) "/auth/sso/oidc/callback"))

(defn- check-oidc-prereqs!
  "Check that OIDC is enabled. Throws on failure."
  []
  (when-not (sso-settings/free-oidc-enabled)
    (throw (ex-info (tru "OIDC authentication is not enabled")
                    {:status-code 400}))))

(defn sso-initiate
  "Initiate the OIDC SSO flow. Redirects to Authentik's authorization endpoint."
  [request]
  (check-oidc-prereqs!)
  (let [{:keys [redirect]} (:params request)
        redirect-url (if redirect
                       (free-oidc.provider/check-sso-redirect redirect)
                       "/")
        auth-result  (auth-identity/authenticate :provider/free-oidc
                                                 (assoc request
                                                        :redirect-uri   (oidc-redirect-uri)
                                                        :final-redirect redirect-url))]
    (if (= :redirect (:success? auth-result))
      (sso/wrap-oidc-redirect auth-result
                              request
                              :free-oidc
                              redirect-url
                              {:browser-id (:browser-id request)})
      (throw (ex-info (or (:message auth-result) (tru "Failed to initiate OIDC authentication"))
                      {:status-code 500})))))

(defn sso-callback
  "Handle the OIDC callback with an authorization code."
  [request]
  (check-oidc-prereqs!)
  (let [{:keys [code state]} (:params request)
        login-result (auth-identity/login! :provider/free-oidc
                                           (assoc request
                                                  :code          code
                                                  :state         state
                                                  :oidc-provider :free-oidc
                                                  :redirect-uri  (oidc-redirect-uri)
                                                  :device-info   (request/device-info request)))]
    (if (:success? login-result)
      (let [final-redirect (or (:redirect-url login-result) "/")
            base-response  (-> (response/redirect final-redirect)
                               (sso/clear-oidc-state-cookie))]
        (log/infof "OIDC authentication successful for user %s"
                   (get-in login-result [:user-data :email]))
        (if-let [session (:session login-result)]
          (request/set-session-cookies request
                                       base-response
                                       session
                                       (t/zoned-date-time (t/zone-id "GMT")))
          base-response))
      (let [error-msg (or (:message login-result) (tru "OIDC authentication failed"))]
        (log/errorf "OIDC authentication failed: %s" error-msg)
        (throw (ex-info error-msg {:status-code 401}))))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./bin/test-agent :only '[metabase.sso.integrations.free-oidc-test]'`

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/metabase/sso/integrations/free_oidc.clj test/metabase/sso/integrations/free_oidc_test.clj
git commit -m "Add OIDC initiate/callback handlers

Mirrors the OSS slack-connect integration: wrap-oidc-redirect on
initiate to set the encrypted state cookie, set-session-cookies on
successful callback.

The callback URI /auth/sso/oidc/callback is pinned by a test because
it must match the Authentik application config."
```

---

### Task 6: Mount the OIDC routes

**Files:**
- Create: `src/metabase/sso/api/oidc.clj`
- Modify: `src/metabase/server/auth_wrapper.clj`
- Test: `test/metabase/server/auth_wrapper_test.clj`

**Interfaces:**
- Consumes: `sso-initiate` / `sso-callback` from Task 5.
- Produces: `metabase.sso.api.oidc/routes` — a Ring handler mounted at `/auth/sso/oidc`, always available in OSS.

**Why this is legitimate:** `auth_wrapper.clj` already mounts `/auth/sso/slack-connect` as an always-available OSS route and falls back to "ee-build-required" stubs for the rest. We add `/oidc` to the same always-available map. This is the established pattern with a working example.

**Accepted design note — route shadowing (decided 2026-07-17):** EE's `metabase_enterprise/sso/api/sso.clj` defines `GET /auth/sso/:key`, where `:key` is any provider name. Our always-available `/auth/sso/oidc` is mounted *first* in `handlers/routes`, so in an EE-inclusive build it would shadow an EE provider literally keyed `"oidc"`. **This is accepted, not a bug, because freebase ships `MB_EDITION=oss`:** in production `config/ee-available?` is false, EE's `/auth/sso/:key` routes are never mounted, and there is nothing to shadow. The risk exists only in a hypothetical EE build with a provider named exactly `"oidc"` — which contradicts the premise of this project. We keep the clean `/auth/sso/oidc` path (and its Authentik callback registration) rather than rename to `/auth/sso/free-oidc` for a collision that cannot occur in our deployment. Revisit only if freebase ever runs an EE-inclusive build.

- [ ] **Step 1: Write the failing test**

Create `test/metabase/server/auth_wrapper_test.clj`:

```clojure
(ns metabase.server.auth-wrapper-test
  (:require
   [clojure.test :refer :all]
   [metabase.server.auth-wrapper :as auth-wrapper]))

(defn- route-response
  "Invoke the auth-wrapper routes synchronously and return the response."
  [uri]
  (let [result (promise)]
    (auth-wrapper/routes {:request-method :get :uri uri}
                         (fn [resp] (deliver result resp))
                         (fn [e] (deliver result {:status 500 :error e})))
    (deref result 2000 {:status :timeout})))

(deftest oidc-route-is-available-in-oss-test
  (testing "/auth/sso/oidc is NOT the ee-build-required stub"
    (let [resp (route-response "/auth/sso/oidc")]
      (is (not= "ee-build-required" (get-in resp [:body :status]))
          "OIDC must be served by our OSS handler, not the EE-missing fallback"))))

(deftest saml-route-still-requires-ee-test
  (testing "routes we did NOT implement still return the EE stub"
    (let [resp (route-response "/auth/sso/saml")]
      (is (= "ee-build-required" (get-in resp [:body :status]))
          "we only added OIDC; SAML must still say EE-only"))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test-agent :only '[metabase.server.auth-wrapper-test]'`

Expected: FAIL — `/auth/sso/oidc` returns the `ee-build-required` stub.

- [ ] **Step 3: Write the route namespace**

Create `src/metabase/sso/api/oidc.clj`:

```clojure
(ns metabase.sso.api.oidc
  "API routes for Waterloo OIDC SSO authentication."
  (:require
   [metabase.api.macros :as api.macros]
   [metabase.sso.integrations.free-oidc :as free-oidc.integration]
   [metabase.util.log :as log]))

;; GET /auth/sso/oidc
;;
#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :get "/"
  "Initiate the OIDC SSO flow."
  [_route-params _query-params _body request]
  (try
    (free-oidc.integration/sso-initiate request)
    (catch Throwable e
      (log/error e "Error initiating OIDC SSO")
      (throw e))))

;; GET /auth/sso/oidc/callback
;;
#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :get "/callback"
  "OIDC callback."
  [_route-params _query-params _body request]
  (try
    (free-oidc.integration/sso-callback request)
    (catch Throwable e
      (log/error e "Error handling OIDC callback")
      (throw e))))

(def ^{:arglists '([request respond raise])} routes
  "`/auth/sso/oidc` routes."
  (api.macros/ns-handler *ns*))
```

- [ ] **Step 4: Mount it in the always-available OSS route map**

Edit `src/metabase/server/auth_wrapper.clj`. Add the require:

```clojure
   [metabase.sso.api.oidc :as oidc.api]
```

Then change the `routes` def:

```clojure
;; This needs to be injected into [[metabase.server.routes/routes]] -- not [[metabase.api-routes.core/routes]] !!!
(def routes
  "Ring routes for auth API endpoints.
   Slack Connect and OIDC (both OSS) are always available. Other SSO routes (SAML, JWT)
   require EE."
  (handlers/routes
   ;; OSS SSO routes, always available
   (handlers/route-map-handler {"/auth" {"/sso" {"/slack-connect" slack-connect.api/routes
                                                 "/oidc"          oidc.api/routes}}})
   ;; Other SSO routes require EE
   (if (and config/ee-available? (not *compile-files*))
     (requiring-resolve 'metabase-enterprise.sso.api.routes/routes)
     ee-missing-routes)))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./bin/test-agent :only '[metabase.server.auth-wrapper-test]'`

Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/metabase/sso/api/oidc.clj src/metabase/server/auth_wrapper.clj \
        test/metabase/server/auth_wrapper_test.clj
git commit -m "Mount /auth/sso/oidc as an always-available OSS route

auth_wrapper already mounts /auth/sso/slack-connect this way and falls
back to ee-build-required stubs for the rest. We add /oidc to the same
always-available map -- the established OSS pattern.

A test asserts SAML still returns the EE stub, so this does not become
a backdoor for ungating other providers."
```

---

### Task 7: Make the login page aware of OIDC

**Files:**
- Modify: `src/metabase/sso/settings.clj` (`ee-sso-configured?`, ~line 284)
- Test: `test/metabase/sso/free_oidc_settings_test.clj` (extend)

**Interfaces:**
- Consumes: `free-oidc-enabled` from Task 3.
- Produces: `(sso-enabled?)` returns `true` when OIDC is enabled in an OSS build.

**Why:** `ee-sso-configured?` is wrapped in `(when config/ee-available? ...)`, so in an OSS build it returns `nil` and the login page renders no SSO button. This is the one genuine patch the connector needs.

- [ ] **Step 1: Write the failing test**

Append to `test/metabase/sso/free_oidc_settings_test.clj`:

```clojure
(deftest sso-enabled-includes-oidc-in-oss-build-test
  (testing "sso-enabled? is true when OIDC is on, even without EE on the classpath"
    (mt/with-temporary-setting-values [free-oidc-enabled       true
                                       free-oidc-client-id     "abc"
                                       free-oidc-client-secret "shh"
                                       free-oidc-issuer-uri    "https://sso.waterloocap.com/application/o/metabase/"
                                       google-auth-client-id nil
                                       ldap-enabled          false]
      (is (true? (boolean (sso.settings/sso-enabled?)))
          "Without this, the login page renders no SSO button in an OSS build."))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test-agent :only '[metabase.sso.free-oidc-settings-test/sso-enabled-includes-oidc-in-oss-build-test]'`

Expected: FAIL — `sso-enabled?` returns `false`/`nil` because `ee-sso-configured?` short-circuits on `config/ee-available?`.

- [ ] **Step 3: Patch the enablement check**

**Leave `ee-sso-configured?` COMPLETELY UNTOUCHED.** Make exactly ONE change — add `(free-oidc-enabled)` to `sso-enabled?`, outside the `ee-available?` guard.

Why not also drop the EE `:oidc-enabled` line from `ee-sso-configured?`: it looks like dead weight (EE's OIDC, which we don't build), but it is NOT ours to remove. In an OSS build it's already inert (the whole body is guarded by `config/ee-available?`), so removing it buys nothing. In an EE-inclusive build — including the default test alias — it is load-bearing: `enable-password-login`'s getter only honors an explicit `false` when `sso-enabled?` is true (`src/metabase/session/settings.clj`), so dropping the line silently re-enables password login for an admin who disabled it in favor of EE's OIDC, and breaks the existing `enable-password-login-honors-oidc-as-sso-test`. Adding our term is sufficient and minimal; removing theirs is a regression.

In `src/metabase/sso/settings.clj`, `ee-sso-configured?` stays exactly as upstream:

```clojure
(defn- ee-sso-configured? []
  (when config/ee-available?
    (or (setting/get :other-sso-enabled?)
        (setting/get :oidc-enabled))))
```

and `sso-enabled?` gets one added line:

```clojure
(defn sso-enabled?
  "Any SSO provider is configured and enabled"
  []
  (or (google-auth-enabled)
      (ldap-enabled)
      ;; Our OIDC connector is OSS, so it must not sit behind the ee-available? guard.
      (free-oidc-enabled)
      (ee-sso-configured?)))
```

`sso-source-enabled?` was already patched in Task 3 (its `:oidc` case reads `free-oidc-enabled`); do not touch it again here.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./bin/test-agent :only '[metabase.sso.free-oidc-settings-test]'`

Expected: PASS (5 tests).

Also confirm the existing EE test is NOT regressed (it depends on `ee-sso-configured?`):

Run: `./bin/test-agent :only '[metabase-enterprise.sso.settings-test/enable-password-login-honors-oidc-as-sso-test]'`

Expected: PASS (2 assertions). If this fails, you touched `ee-sso-configured?` — revert it to upstream.

- [ ] **Step 5: Commit**

```bash
git add src/metabase/sso/settings.clj test/metabase/sso/free_oidc_settings_test.clj
git commit -m "Teach sso-enabled? about our OSS OIDC connector

ee-sso-configured? is guarded by config/ee-available?, so in an OSS
build it returns nil and the login page renders no SSO button. Our
connector is OSS, so free-oidc-enabled moves out from behind that guard.

sso-source-enabled? needs no change -- it already dispatches
:oidc -> (setting/get :free-oidc-enabled)."
```

---

### Task 8: Admin settings API

**Files:**
- Create: `src/metabase/sso/api/oidc_settings.clj`
- Modify: `src/metabase/sso/api.clj`
- Modify: `src/metabase/api_routes/routes.clj:193` (beside `/ldap`)
- Test: `test/metabase/sso/api/oidc_settings_test.clj`

**Interfaces:**
- Consumes: settings from Task 3.
- Produces:
  - `PUT /api/oidc/settings` — superuser only. Body: `{:free-oidc-enabled bool?, :free-oidc-issuer-uri str?, :free-oidc-client-id str?, :free-oidc-client-secret str?, :free-oidc-scopes str?}`. Tests the config before saving; `400` with `{:errors {...}}` if the probe fails.
  - `metabase.sso.api/oidc-settings-routes`

**Pattern:** modeled on `src/metabase/sso/api/ldap.clj` — superuser check, test connection, save only on success, and the obfuscated-secret round-trip guard.

- [ ] **Step 1: Write the failing test**

Create `test/metabase/sso/api/oidc_settings_test.clj`:

```clojure
(ns metabase.sso.api.oidc-settings-test
  (:require
   [clojure.test :refer :all]
   [metabase.sso.settings :as sso.settings]
   [metabase.test :as mt]))

(deftest requires-superuser-test
  (testing "non-admins cannot change OIDC settings"
    (is (= "You don't have permissions to do that."
           (mt/user-http-request :rasta :put 403 "oidc/settings"
                                 {:free-oidc-enabled false})))))

(deftest masked-secret-round-trip-does-not-clobber-test
  (testing "submitting the masked secret back leaves the stored secret intact"
    (mt/with-temporary-setting-values [free-oidc-client-secret "real-secret-value"
                                       free-oidc-client-id     "abc"
                                       free-oidc-issuer-uri    "https://sso.waterloocap.com/application/o/metabase/"]
      (let [masked (sso.settings/free-oidc-client-secret)]
        (#'metabase.sso.api.oidc-settings/update-secret-if-needed masked)
        (is (= "real-secret-value"
               (#'metabase.sso.api.oidc-settings/update-secret-if-needed masked))
            "A masked value must resolve back to the stored secret, not overwrite it with asterisks.")
        (is (= "brand-new-secret"
               (#'metabase.sso.api.oidc-settings/update-secret-if-needed "brand-new-secret"))
            "A genuinely new value must pass through unchanged.")))))

(deftest config-is-persisted-before-enabling-test
  (testing "enabling OIDC alongside a fresh config actually results in enabled=true"
    ;; Regression guard. `free-oidc-enabled`'s getter consults `free-oidc-configured`, so if the
    ;; endpoint writes :free-oidc-enabled in the same set-many! as the config, enabled
    ;; evaluates against the OLD (unconfigured) state and silently stays false.
    ;; metabase.sso.api.ldap has the same hazard and solves it the same way.
    (mt/with-temporary-setting-values [free-oidc-enabled false, free-oidc-client-id nil
                                       free-oidc-client-secret nil, free-oidc-issuer-uri nil]
      (with-redefs [metabase.sso.core/check-oidc-configuration
                    (fn [& _] {:ok true :discovery {:success true} :credentials {:success true}})]
        (mt/user-http-request :crowberto :put 200 "oidc/settings"
                              {:free-oidc-enabled       true
                               :free-oidc-client-id     "abc"
                               :free-oidc-client-secret "shh"
                               :free-oidc-issuer-uri    "https://sso.waterloocap.com/application/o/metabase/"})
        (is (true? (sso.settings/free-oidc-enabled))
            "If this is false, the config was not persisted before free-oidc-enabled was set.")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test-agent :only '[metabase.sso.api.oidc-settings-test]'`

Expected: FAIL — namespace and endpoint do not exist.

- [ ] **Step 3: Write the admin API**

Create `src/metabase/sso/api/oidc_settings.clj`:

```clojure
(ns metabase.sso.api.oidc-settings
  "/api/oidc endpoints for configuring the OIDC connector.

  Modeled on [[metabase.sso.api.ldap]]: superuser only, test the configuration before
  persisting it, and never clobber the stored secret with its own mask."
  (:require
   [clojure.string :as str]
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.settings.core :as setting]
   [metabase.sso.core :as sso]
   [metabase.sso.settings :as sso.settings]
   [metabase.util.i18n :refer [tru]]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn- update-secret-if-needed
  "Do not overwrite the stored secret if `new-secret` is just its obfuscated form."
  [new-secret]
  (let [current (sso.settings/unobfuscated-free-oidc-client-secret)]
    (if (= (sso.settings/free-oidc-client-secret) new-secret)
      current
      new-secret)))

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :put "/settings"
  "Update OIDC settings. You must be a superuser to do this. The configuration is probed
  against the provider before being saved; if the probe fails, nothing is persisted."
  [_route-params
   _query-params
   settings :- [:map
                [:free-oidc-enabled       {:optional true} [:maybe :boolean]]
                [:free-oidc-issuer-uri    {:optional true} [:maybe :string]]
                [:free-oidc-client-id     {:optional true} [:maybe :string]]
                [:free-oidc-client-secret {:optional true} [:maybe :string]]
                [:free-oidc-scopes        {:optional true} [:maybe :string]]]]
  (api/check-superuser)
  (let [secret     (update-secret-if-needed (:free-oidc-client-secret settings))
        issuer     (or (:free-oidc-issuer-uri settings) (sso.settings/free-oidc-issuer-uri))
        client-id  (or (:free-oidc-client-id settings) (sso.settings/free-oidc-client-id))
        scopes     (or (:free-oidc-scopes settings) (sso.settings/free-oidc-scopes))
        enabling?  (:free-oidc-enabled settings)]
    ;; Only probe when we are being asked to turn OIDC on. Probing on every save would
    ;; make it impossible to disable a provider that has gone down.
    (when enabling?
      (let [result (sso/check-oidc-configuration issuer client-id secret
                                                 (vec (remove empty? (str/split (or scopes "") #"\s+"))))]
        (when-not (:ok result)
          (throw (ex-info (tru "Could not connect to the OIDC provider")
                          {:status-code 400
                           :errors      (select-keys result [:discovery :credentials])})))))
    ;; IMPORTANT: mirror the ordering in metabase.sso.api.ldap. The `free-oidc-enabled` getter
    ;; consults `free-oidc-configured`, so the config MUST be persisted before we flip enabled
    ;; — otherwise enabling against a fresh config silently evaluates to false.
    (t2/with-transaction [_conn]
      (setting/set-many! (cond-> (dissoc settings :free-oidc-client-secret :free-oidc-enabled)
                           secret (assoc :free-oidc-client-secret secret)))
      (when (contains? settings :free-oidc-enabled)
        (setting/set-value-of-type! :boolean :free-oidc-enabled (boolean (:free-oidc-enabled settings)))))
    {:ok true}))

(def ^{:arglists '([request respond raise])} routes
  "`/api/oidc` routes."
  (api.macros/ns-handler *ns*))
```

- [ ] **Step 4: Expose and mount the routes**

Edit `src/metabase/sso/api.clj`:

```clojure
(ns metabase.sso.api
  (:require
   [metabase.api.macros :as api.macros]
   [metabase.sso.api.google]
   [metabase.sso.api.ldap]
   [metabase.sso.api.oidc-settings]))

(comment metabase.sso.api.google/keep-me
         metabase.sso.api.ldap/keep-me
         metabase.sso.api.oidc-settings/keep-me)

(def ^{:arglists '([request respond raise])} google-auth-routes
  "`/api/google/` routes."
  (api.macros/ns-handler 'metabase.sso.api.google))

(def ^{:arglists '([request respond raise])} ldap-routes
  "`/api/ldap` routes."
  (api.macros/ns-handler 'metabase.sso.api.ldap))

(def ^{:arglists '([request respond raise])} oidc-settings-routes
  "`/api/oidc` routes."
  (api.macros/ns-handler 'metabase.sso.api.oidc-settings))
```

Edit `src/metabase/api_routes/routes.clj`, immediately after the `"/ldap"` entry (~line 193):

```clojure
   "/ldap"                 (+auth metabase.sso.api/ldap-routes)
   "/oidc"                 (+auth metabase.sso.api/oidc-settings-routes)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./bin/test-agent :only '[metabase.sso.api.oidc-settings-test]'`

Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/metabase/sso/api/oidc_settings.clj src/metabase/sso/api.clj \
        src/metabase/api_routes/routes.clj test/metabase/sso/api/oidc_settings_test.clj
git commit -m "Add PUT /api/oidc/settings admin API

Modeled on sso/api/ldap.clj: superuser check, probe the config with the
AGPL check-oidc-configuration before persisting, and never clobber the
stored secret with its own mask.

Probes only when enabling, so a provider that has gone down can still
be switched off."
```

---

### Task 9: Login page OIDC button

**Files:**
- Create: `frontend/src/metabase/auth/components/OidcButton/OidcButton.tsx`
- Create: `frontend/src/metabase/auth/components/OidcButton/index.ts`
- Create: `frontend/src/metabase/plugins/builtin/auth/oidc.ts`
- Modify: `frontend/src/metabase/plugins/builtin/index.js` (register the new module)
- Test: `frontend/src/metabase/auth/components/OidcButton/OidcButton.unit.spec.tsx`

**Interfaces:**
- Consumes: the `free-oidc-enabled` setting (Task 3) exposed to the frontend; the `/auth/sso/oidc` route (Task 6).
- Produces: an entry in `PLUGIN_AUTH_PROVIDERS.providers` named `"oidc"`.

**Pattern:** `frontend/src/metabase/plugins/builtin/auth/google.ts` — 19 lines.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/metabase/auth/components/OidcButton/OidcButton.unit.spec.tsx`:

```tsx
import { render, screen } from "__support__/ui";

import { OidcButton } from "./OidcButton";

describe("OidcButton", () => {
  it("links to the OIDC SSO initiate route", () => {
    render(<OidcButton />);
    const link = screen.getByRole("link", { name: /sign in with sso/i });
    expect(link).toHaveAttribute("href", "/auth/sso/oidc");
  });

  it("passes the redirect through so users land where they were going", () => {
    render(<OidcButton redirectUrl="/dashboard/1" />);
    const link = screen.getByRole("link", { name: /sign in with sso/i });
    expect(link).toHaveAttribute(
      "href",
      "/auth/sso/oidc?redirect=%2Fdashboard%2F1",
    );
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `yarn jest frontend/src/metabase/auth/components/OidcButton --silent`

Expected: FAIL — module not found.

- [ ] **Step 3: Write the button**

Create `frontend/src/metabase/auth/components/OidcButton/OidcButton.tsx`:

```tsx
import { t } from "ttag";

import Button from "metabase/common/components/Button";

interface OidcButtonProps {
  redirectUrl?: string;
}

export const OidcButton = ({ redirectUrl }: OidcButtonProps): JSX.Element => {
  const href = redirectUrl
    ? `/auth/sso/oidc?redirect=${encodeURIComponent(redirectUrl)}`
    : "/auth/sso/oidc";

  return (
    <Button as="a" href={href} fullWidth>
      {t`Sign in with SSO`}
    </Button>
  );
};
```

Create `frontend/src/metabase/auth/components/OidcButton/index.ts`:

```ts
export * from "./OidcButton";
```

- [ ] **Step 4: Register the provider**

Create `frontend/src/metabase/plugins/builtin/auth/oidc.ts`:

```ts
import { PLUGIN_AUTH_PROVIDERS, PLUGIN_IS_PASSWORD_USER } from "metabase/plugins";
import MetabaseSettings from "metabase/utils/settings";

PLUGIN_AUTH_PROVIDERS.providers.push((providers) => {
  const oidcProvider = {
    name: "oidc",
    // circular dependencies
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    Button: require("metabase/auth/components/OidcButton").OidcButton,
  };

  return MetabaseSettings.get("free-oidc-enabled")
    ? [oidcProvider, ...providers]
    : providers;
});

PLUGIN_IS_PASSWORD_USER.push((user) => user.sso_source !== "oidc");
```

Register it in `frontend/src/metabase/plugins/builtin/index.js` alongside the other auth modules:

```js
import "./auth/oidc";
```

- [ ] **Step 5: Run test to verify it passes**

Run: `yarn jest frontend/src/metabase/auth/components/OidcButton --silent`

Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/metabase/auth/components/OidcButton/ \
        frontend/src/metabase/plugins/builtin/auth/oidc.ts \
        frontend/src/metabase/plugins/builtin/index.js
git commit -m "Add OIDC login button

Mirrors builtin/auth/google.ts. Registers an 'oidc' provider gated on
the free-oidc-enabled setting, and marks OIDC users as non-password users so
the password-reset flow behaves."
```

---

## Phase 3 — Custom branding (rewrite, not bypass)

> **Read this before starting Phase 3.** Metabase gates whitelabel two ways: `:feature :whitelabel` on the settings in `src/metabase/appearance/settings.clj`, and a `tokenFeatures["whitelabel"]` check in `frontend/src/metabase/ui/colors/colors.ts`.
>
> **You must not touch either.** Deleting them would restore branding in five minutes and would be exactly the bypass this project exists to remove. Instead we define our own `wc-brand-*` settings and feed them into the AGPL theming pipeline, which already accepts colors as a parameter. Their gate stays intact, guarding their settings, which we stop using. Task 13 enforces this with a test.

### Task 10: Branding settings

**Files:**
- Create: `src/metabase/branding/settings.clj`
- Test: `test/metabase/branding/settings_test.clj`

**Interfaces:**
- Consumes: nothing.
- Produces, in `metabase.branding.settings`:
  - `(wc-brand-name)` → `string`, default `"Metabase"`
  - `(wc-brand-logo-url)` → `string?`
  - `(wc-brand-favicon-url)` → `string?`
  - `(wc-brand-colors)` → `map?`
  - `(wc-help-link)` → `string?`
  - `(wc-help-link-destination)` → `string?`

All `:visibility :public` so they reach `MetabaseBootstrap` on the frontend. **None carry a `:feature` gate — they are ours.**

- [ ] **Step 1: Write the failing test**

Create `test/metabase/branding/settings_test.clj`:

```clojure
(ns metabase.branding.settings-test
  (:require
   [clojure.test :refer :all]
   [metabase.branding.settings :as branding.settings]
   [metabase.test :as mt]))

(deftest brand-settings-are-not-feature-gated-test
  (testing "our branding settings work without any premium token"
    (mt/with-temporary-setting-values [premium-embedding-token nil
                                       wc-brand-name           "Waterloo"]
      (is (= "Waterloo" (branding.settings/wc-brand-name))
          "Our settings are ours: no token, no gate, they just work."))))

(deftest brand-name-defaults-to-metabase-test
  (testing "unset branding falls back to stock Metabase naming"
    (mt/with-temporary-setting-values [wc-brand-name nil]
      (is (= "Metabase" (branding.settings/wc-brand-name))))))

(deftest brand-colors-round-trip-test
  (testing "colors persist as a map"
    (mt/with-temporary-setting-values [wc-brand-colors {"brand" "#3E90C5"}]
      (is (= {"brand" "#3E90C5"} (branding.settings/wc-brand-colors))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test-agent :only '[metabase.branding.settings-test]'`

Expected: FAIL — namespace does not exist.

- [ ] **Step 3: Write the settings**

Create `src/metabase/branding/settings.clj`:

```clojure
(ns metabase.branding.settings
  "Waterloo branding settings.

  These are OUR settings, not Metabase's. Metabase's own `application-*` settings in
  [[metabase.appearance.settings]] are gated behind `:feature :whitelabel` and we do not
  touch them — that gate stays intact and functional, guarding settings we simply do not
  use.

  These settings carry no `:feature` gate because they are original code, fed into the
  AGPL theming pipeline (which already accepts arbitrary colors as a parameter). See
  test/metabase/branding/anti_bypass_test.clj, which enforces this boundary."
  (:require
   [metabase.settings.core :as setting :refer [defsetting]]
   [metabase.util.i18n :refer [deferred-tru]]))

(set! *warn-on-reflection* true)

(defsetting wc-brand-name
  (deferred-tru "Product name shown throughout the app.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :default    "Metabase"
  :audit      :getter)

(defsetting wc-brand-logo-url
  (deferred-tru "URL or data URI for the logo shown in the nav bar and on the login page.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter)

(defsetting wc-brand-favicon-url
  (deferred-tru "URL or data URI for the browser tab favicon.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter)

(defsetting wc-brand-colors
  (deferred-tru "Brand color overrides, as a map of color key to hex value.")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :json
  :default    {}
  :audit      :getter)

(defsetting wc-help-link
  (deferred-tru "Help link behavior: \"metabase\", \"custom\", or \"hidden\".")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :default    "metabase"
  :audit      :getter)

(defsetting wc-help-link-destination
  (deferred-tru "Custom help link destination, used when wc-help-link is \"custom\".")
  :encryption :no
  :visibility :public
  :export?    true
  :type       :string
  :audit      :getter)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./bin/test-agent :only '[metabase.branding.settings-test]'`

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/metabase/branding/settings.clj test/metabase/branding/settings_test.clj
git commit -m "Add wc-brand-* settings for custom branding

Our own settings, ungated, feeding the AGPL theming pipeline. Metabase's
own application-* settings stay gated behind :feature :whitelabel and
untouched -- we simply stop using them.

This is the rewrite half of 'rewrite, never bypass'."
```

---

### Task 11: Migrate existing branding values

**Files:**
- Create: `src/metabase/branding/migration.clj`
- Modify: `src/metabase/branding/init.clj` (create)
- Test: `test/metabase/branding/migration_test.clj`

**Interfaces:**
- Consumes: `wc-brand-*` settings from Task 10.
- Produces: `metabase.branding.migration/migrate-application-settings!` → `(fn [] {:migrated [kw]})`. Idempotent: never overwrites an existing `wc-brand-*` value.

**Why:** production already holds `application-name="Waterloo"`, a 77KB logo, a 20KB favicon and a brand palette. Copying them means branding is identical across the cutover with nothing re-uploaded by hand. Read the raw stored values with `setting/get-value-of-type` — the gated getters would return `"Metabase"`.

- [ ] **Step 1: Write the failing test**

Create `test/metabase/branding/migration_test.clj`:

```clojure
(ns metabase.branding.migration-test
  (:require
   [clojure.test :refer :all]
   [metabase.branding.migration :as branding.migration]
   [metabase.branding.settings :as branding.settings]
   [metabase.settings.core :as setting]
   [metabase.test :as mt]))

(deftest migrates-stored-application-values-test
  (testing "existing application-* values are copied to wc-brand-*"
    (mt/with-temporary-setting-values [wc-brand-name nil, wc-brand-colors {}]
      (setting/set-value-of-type! :string :application-name "Waterloo")
      (setting/set-value-of-type! :json :application-colors {"brand" "#3E90C5"})
      (branding.migration/migrate-application-settings!)
      (is (= "Waterloo" (branding.settings/wc-brand-name))
          "Reads the RAW stored value; the gated getter would return 'Metabase'.")
      (is (= {"brand" "#3E90C5"} (branding.settings/wc-brand-colors))))))

(deftest migration-is-idempotent-test
  (testing "re-running never clobbers an already-set brand value"
    (mt/with-temporary-setting-values [wc-brand-name "Already Set"]
      (setting/set-value-of-type! :string :application-name "Waterloo")
      (branding.migration/migrate-application-settings!)
      (is (= "Already Set" (branding.settings/wc-brand-name))
          "An operator's explicit choice must win over the migration."))))

(deftest migration-skips-unset-source-values-test
  (testing "an unset application-* value does not blank out its wc-brand-* counterpart"
    (mt/with-temporary-setting-values [wc-brand-name nil]
      (setting/set-value-of-type! :string :application-name nil)
      (branding.migration/migrate-application-settings!)
      (is (= "Metabase" (branding.settings/wc-brand-name))
          "Falls back to the default, not to nil."))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test-agent :only '[metabase.branding.migration-test]'`

Expected: FAIL — namespace does not exist.

- [ ] **Step 3: Write the migration**

Create `src/metabase/branding/migration.clj`:

```clojure
(ns metabase.branding.migration
  "One-time copy of Metabase's `application-*` branding values onto our `wc-brand-*`
  settings.

  Reads the RAW stored values via `setting/get-value-of-type` rather than the public
  getters: `application-name` and friends are gated behind `:feature :whitelabel`, so
  their getters return the stock default in an OSS build even when a real value is
  stored. We are copying data out of rows the operator already owns, not defeating the
  gate — the gate keeps working, and Metabase's settings keep returning their defaults."
  (:require
   ;; Bare require, registration side-effect only: loading this namespace registers
   ;; Metabase's application-* settings so setting/get-value-of-type and db-stored-value
   ;; can resolve them when init! runs standalone (else: "Unknown setting: :application-name").
   [metabase.appearance.core]
   [metabase.settings.core :as setting]
   [metabase.util.log :as log]))

(set! *warn-on-reflection* true)

(def ^:private setting-map
  "Source (Metabase, gated) -> destination (ours, ungated), with the value type."
  [[:application-name             :wc-brand-name             :string]
   [:application-logo-url         :wc-brand-logo-url         :string]
   [:application-favicon-url      :wc-brand-favicon-url      :string]
   [:application-colors           :wc-brand-colors           :json]
   [:help-link                    :wc-help-link              :string]
   [:help-link-custom-destination :wc-help-link-destination  :string]])

(defn- blank-value? [v]
  (or (nil? v)
      (and (string? v) (empty? v))
      (and (map? v) (empty? v))))

(defn- unset?
  "True when setting `k` has no meaningful stored value.

  CRITICAL: we must NOT use `(blank-value? (get-value-of-type ... k))` alone to decide a
  destination is unset. Several `wc-brand-*` settings ship a non-blank compiled-in
  `:default` (e.g. `wc-brand-name` = \"Metabase\", `wc-help-link` = \"metabase\"), so an
  unwritten destination reads back NON-blank via its getter — which would make the
  migration wrongly conclude it is 'already set' and skip copying `application-name`
  (\"Waterloo\") entirely. `db-stored-value` returns only what is actually persisted in the
  DB/cache (never the default/env/init value), so it distinguishes 'unwritten' from
  'written to its default'."
  [value-type k]
  (or (nil? (setting/db-stored-value k))
      (blank-value? (setting/get-value-of-type value-type k))))

(defn migrate-application-settings!
  "Copy any stored `application-*` branding values onto the `wc-brand-*` settings.

  Idempotent, and never overwrites a destination that already has a value — an
  operator's explicit choice wins over the migration."
  []
  (let [migrated (atom [])]
    (doseq [[src dest value-type] setting-map]
      (when (and (not (unset? value-type src))
                 (unset? value-type dest))
        (let [source-value (setting/get-value-of-type value-type src)]
          (setting/set-value-of-type! value-type dest source-value)
          (swap! migrated conj dest)
          (log/infof "Migrated branding setting %s -> %s" src dest))))
    (when (seq @migrated)
      (log/infof "Branding migration copied %d setting(s): %s" (count @migrated) (pr-str @migrated)))
    {:migrated @migrated}))
```

Note the `unset?` helper and the `[metabase.appearance.core]` require are load-bearing corrections found during implementation: the original brief used `blank-value?` on `get-value-of-type` for the destination, which silently failed to migrate any setting whose `wc-brand-*` default is non-blank (`wc-brand-name`, `wc-help-link`) — i.e. it would NOT have copied "Waterloo" across the cutover.

Create `src/metabase/branding/init.clj`:

```clojure
(ns metabase.branding.init
  (:require
   [metabase.branding.migration :as branding.migration]
   [metabase.branding.settings]))

(defn init!
  "Run branding initialization: migrate any legacy application-* values on first boot."
  []
  (branding.migration/migrate-application-settings!))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./bin/test-agent :only '[metabase.branding.migration-test]'`

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/metabase/branding/migration.clj src/metabase/branding/init.clj \
        test/metabase/branding/migration_test.clj
git commit -m "Migrate existing application-* branding onto wc-brand-*

Prod already holds application-name=Waterloo plus a 77KB logo, 20KB
favicon and brand palette. Copying them keeps branding identical across
the cutover with nothing re-uploaded by hand.

Reads raw stored values because the application-* getters are gated and
would return 'Metabase'. Copying data out of rows the operator owns is
not defeating the gate: the gate keeps working and Metabase's settings
keep returning their defaults. Idempotent; never clobbers an explicit
wc-brand-* value."
```

---

### Task 12: Apply branding on the frontend

**Files:**
- Modify: `frontend/src/metabase/ui/colors/colors.ts:9-14`
- Modify: `frontend/src/metabase/AppThemeProvider.tsx:120-122`
- Create: `frontend/src/metabase/plugins/builtin/branding/logo.tsx`
- Modify: `frontend/src/metabase/plugins/builtin/index.js`
- Test: `frontend/src/metabase/ui/colors/colors.unit.spec.ts`

**Note:** `LogoIcon.tsx` is deliberately NOT modified — it already consumes the OSS `PLUGIN_LOGO_ICON_COMPONENTS` registry, which is the correct extension point.

**Interfaces:**
- Consumes: `wc-brand-colors`, `wc-brand-logo-url` from Task 10, delivered via `MetabaseBootstrap`.
- Produces: the app renders Waterloo colors and logo with no premium token.

**Key insight:** colors have a *single* injection point — `MetabaseSettings.applicationColors()` inside `AppThemeProvider`, which wraps the whole app at `app.js:91`. Swap what feeds it and the entire app rebrands. Do not scope this per-page.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/metabase/ui/colors/colors.unit.spec.ts`:

```ts
describe("whitelabel colors", () => {
  const originalBootstrap = window.MetabaseBootstrap;

  afterEach(() => {
    window.MetabaseBootstrap = originalBootstrap;
    jest.resetModules();
  });

  it("reads brand colors from wc-brand-colors without any token feature", async () => {
    window.MetabaseBootstrap = {
      // Deliberately NO token-features: our branding must not depend on a license.
      "wc-brand-colors": { brand: "#3E90C5" },
    } as any;

    const { colors } = await import("./colors");
    expect(colors.brand).toBe("#3E90C5");
  });

  it("falls back to stock colors when wc-brand-colors is unset", async () => {
    window.MetabaseBootstrap = {} as any;

    const { colors } = await import("./colors");
    expect(colors.brand).not.toBe("#3E90C5");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `yarn jest frontend/src/metabase/ui/colors/colors.unit.spec.ts --silent`

Expected: FAIL — `colors.brand` is the stock color, because `colors.ts` reads the gated `application-colors` behind `tokenFeatures["whitelabel"]`.

- [ ] **Step 3: Read our setting in the static palette**

Edit `frontend/src/metabase/ui/colors/colors.ts`. Replace lines 9–14:

```ts
const win = typeof window !== "undefined" ? window : ({} as Window);
const tokenFeatures = win.MetabaseBootstrap?.["token-features"] ?? {};
const shouldWhitelabel = !!tokenFeatures["whitelabel"];
const whitelabelColors =
  (shouldWhitelabel && win.MetabaseBootstrap?.["application-colors"]) || {};
```

with:

```ts
const win = typeof window !== "undefined" ? window : ({} as Window);
// Our own branding setting, not Metabase's gated `application-colors`. We never read
// their setting and never consult `token-features` -- their whitelabel gate stays
// intact and functional, guarding a setting we do not use. See
// src/metabase/branding/settings.clj.
const whitelabelColors = win.MetabaseBootstrap?.["wc-brand-colors"] || {};
```

- [ ] **Step 4: Seed the theme provider from our setting**

Edit `frontend/src/metabase/AppThemeProvider.tsx`. Replace:

```tsx
  const [whitelabelColors, setWhitelabelColors] = useState<
    ColorSettings | undefined
  >(() => MetabaseSettings.applicationColors());
```

with:

```tsx
  // Seed from our own wc-brand-colors rather than Metabase's gated application-colors.
  const [whitelabelColors, setWhitelabelColors] = useState<
    ColorSettings | undefined
  >(() => MetabaseSettings.get("wc-brand-colors") ?? undefined);
```

- [ ] **Step 5: Render our logo via the OSS plugin registry**

**Do not patch `LogoIcon.tsx`.** It already consumes an OSS plugin registry:

```tsx
// frontend/src/metabase/common/components/LogoIcon/LogoIcon.tsx:180  — leave this alone
export function LogoIcon(props: LogoIconProps) {
  const [Component = DefaultLogoIcon] = PLUGIN_LOGO_ICON_COMPONENTS;
  return <Component {...props} />;
}
```

`PLUGIN_LOGO_ICON_COMPONENTS` is defined in `frontend/src/metabase/plugins/oss/core.ts` — an OSS extension point. Register our component there instead, which is both idiomatic and leaves the component untouched.

Create `frontend/src/metabase/plugins/builtin/branding/logo.tsx`:

```tsx
import { PLUGIN_LOGO_ICON_COMPONENTS } from "metabase/plugins";
import MetabaseSettings from "metabase/utils/settings";

interface LogoIconProps {
  width?: number;
  height?: number;
  dark?: boolean;
  fill?: string;
}

/**
 * Renders the custom brand logo from our own `wc-brand-logo-url` setting.
 *
 * Registered into the OSS PLUGIN_LOGO_ICON_COMPONENTS registry, which LogoIcon already
 * consumes. We never read Metabase's gated `application-logo-url`.
 */
const WcBrandLogoIcon = ({ height = 32, width }: LogoIconProps) => {
  const logoUrl = MetabaseSettings.get("wc-brand-logo-url");
  const brandName = MetabaseSettings.get("wc-brand-name") ?? "Logo";

  if (!logoUrl) {
    return null;
  }

  return (
    <img
      src={logoUrl}
      alt={brandName}
      height={height}
      width={width}
      data-testid="main-logo"
    />
  );
};

// Only take over the registry when a custom logo is actually configured; otherwise
// LogoIcon falls back to DefaultLogoIcon.
if (MetabaseSettings.get("wc-brand-logo-url")) {
  PLUGIN_LOGO_ICON_COMPONENTS.push(WcBrandLogoIcon);
}
```

Register it in `frontend/src/metabase/plugins/builtin/index.js`:

```js
import "./branding/logo";
```

- [ ] **Step 6: Run test to verify it passes**

Run: `yarn jest frontend/src/metabase/ui/colors/colors.unit.spec.ts --silent`

Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/metabase/ui/colors/colors.ts \
        frontend/src/metabase/AppThemeProvider.tsx \
        frontend/src/metabase/plugins/builtin/branding/ \
        frontend/src/metabase/plugins/builtin/index.js \
        frontend/src/metabase/ui/colors/colors.unit.spec.ts
git commit -m "Apply wc-brand-* branding through the AGPL theming pipeline

The OSS theme pipeline already accepts whitelabel colors as a parameter
(deriveFullMetabaseTheme -> ThemeProvider -> AppThemeProvider), and
AppThemeProvider wraps the whole app. Feeding it our own setting
rebrands everything at once -- login, regular use, and admin.

We never read Metabase's gated application-colors and never consult
token-features. Their gate stays intact, guarding a setting we do not
use."
```

---

### Task 13: The anti-bypass test

**Files:**
- Create: `test/metabase/branding/anti_bypass_test.clj`

**Interfaces:**
- Consumes: `wc-brand-*` settings (Task 10); Metabase's `appearance.settings` (read-only, must stay gated).
- Produces: nothing. This test exists to fail if someone takes the shortcut.

**This is the most important test in the suite.** Note carefully: asserting `has-feature? :whitelabel` is false does **not** discriminate — deleting the gate leaves that flag false either way, so such a test passes for both the rewrite and the bypass. The assertion that works is that *Metabase's own setting is still gated and still returns the stock default*.

- [ ] **Step 1: Write the test**

Create `test/metabase/branding/anti_bypass_test.clj`:

```clojure
(ns metabase.branding.anti-bypass-test
  "Proves our branding is a REWRITE, not a re-gated bypass.

  This fork exists to stop using Metabase's enterprise features without a license.
  Branding is the one place where the shortcut is genuinely tempting: deleting
  `:feature :whitelabel` from src/metabase/appearance/settings.clj would restore the
  Waterloo branding in about five minutes, and would be exactly the same act as the
  token_check.clj bypass we removed -- just scoped to one feature.

  These tests are the executable form of that boundary. If they fail, someone took the
  shortcut. Do not 'fix' them by relaxing the assertions."
  (:require
   [clojure.test :refer :all]
   [metabase.appearance.settings :as appearance.settings]
   [metabase.branding.settings :as branding.settings]
   [metabase.premium-features.core :as premium-features]
   [metabase.settings.core :as setting]
   [metabase.test :as mt]))

(deftest metabase-whitelabel-gate-is-intact-test
  (testing "Metabase's own application-name is STILL GATED and returns the stock default"
    (mt/with-temporary-setting-values [premium-embedding-token nil]
      ;; Store a real value in THEIR setting, exactly as prod has today.
      (setting/set-value-of-type! :string :application-name "Waterloo")
      (is (= "Metabase" (appearance.settings/application-name))
          (str "Metabase's application-name must still return the stock default without a "
               "token. If this returns \"Waterloo\", someone deleted :feature :whitelabel "
               "-- that is the bypass, and it must be reverted.")))))

(deftest our-branding-works-without-a-token-test
  (testing "OUR setting carries the branding, with no token and no gate"
    (mt/with-temporary-setting-values [premium-embedding-token nil
                                       wc-brand-name           "Waterloo"]
      (is (= "Waterloo" (branding.settings/wc-brand-name))
          "Our own setting is ungated original code and must just work."))))

(deftest whitelabel-feature-remains-unlicensed-test
  (testing "we never claim the whitelabel feature"
    (mt/with-temporary-setting-values [premium-embedding-token nil]
      (is (false? (boolean (premium-features/has-feature? :whitelabel)))
          "We must not hold the whitelabel feature. Note this assertion alone does NOT
           prove a rewrite -- deleting the gate leaves this false too. The load-bearing
           test is metabase-whitelabel-gate-is-intact-test above."))))

(deftest appearance-settings-still-declare-the-feature-gate-test
  (testing "the :feature :whitelabel declarations are still present in the source"
    (let [source (slurp "src/metabase/appearance/settings.clj")
          gate-count (count (re-seq #":feature\s+:whitelabel" source))]
      (is (>= gate-count 10)
          (str "Expected the :feature :whitelabel gates to still be declared in "
               "src/metabase/appearance/settings.clj (found " gate-count "). If this "
               "dropped, someone removed Metabase's license checks. Revert it.")))))
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `./bin/test-agent :only '[metabase.branding.anti-bypass-test]'`

Expected: PASS (4 tests). This test passes because we did the rewrite correctly.

- [ ] **Step 3: Verify the test actually catches the bypass**

Temporarily delete one gate to prove the test bites:

```bash
# Temporarily remove ONE gate to prove the guard works.
sed -i.bak '0,/:feature    :whitelabel/s///' src/metabase/appearance/settings.clj
./bin/test-agent :only '[metabase.branding.anti-bypass-test]'
```

Expected: FAIL — `appearance-settings-still-declare-the-feature-gate-test` catches the missing gate.

Now restore:

```bash
mv src/metabase/appearance/settings.clj.bak src/metabase/appearance/settings.clj
./bin/test-agent :only '[metabase.branding.anti-bypass-test]'
```

Expected: PASS again. **Do not skip this step** — an anti-bypass test that cannot fail is worthless.

- [ ] **Step 4: Commit**

```bash
git add test/metabase/branding/anti_bypass_test.clj
git commit -m "Add the anti-bypass test: prove branding is a rewrite

The most important test in this project. Deleting :feature :whitelabel
would restore branding in five minutes and would be the token_check
bypass scoped to one feature. This test fails loudly if anyone does it.

Note the discriminating assertion is that Metabase's application-name
STILL returns 'Metabase' while our wc-brand-name returns 'Waterloo'.
Asserting has-feature? :whitelabel is false does NOT discriminate --
deleting the gate leaves that flag false either way.

Verified the test actually bites by removing a gate and watching it
fail."
```

---

### Task 14: Branding admin form

**Files:**
- Create: `src/metabase/branding/api.clj`
- Modify: `src/metabase/sso/api.clj` → no; modify `src/metabase/api_routes/routes.clj` (mount `/api/branding`)
- Test: `test/metabase/branding/api_test.clj`

**Interfaces:**
- Consumes: `wc-brand-*` settings (Task 10).
- Produces: `PUT /api/branding/settings` — superuser only. Body: `{:wc-brand-name str?, :wc-brand-logo-url str?, :wc-brand-favicon-url str?, :wc-brand-colors map?, :wc-help-link str?, :wc-help-link-destination str?}`.

- [ ] **Step 1: Write the failing test**

Create `test/metabase/branding/api_test.clj`:

```clojure
(ns metabase.branding.api-test
  (:require
   [clojure.test :refer :all]
   [metabase.branding.settings :as branding.settings]
   [metabase.test :as mt]))

(deftest requires-superuser-test
  (testing "non-admins cannot change branding"
    (is (= "You don't have permissions to do that."
           (mt/user-http-request :rasta :put 403 "branding/settings"
                                 {:wc-brand-name "Pwned"})))))

(deftest admin-can-set-branding-test
  (testing "an admin can set the brand name and colors"
    (mt/with-temporary-setting-values [wc-brand-name nil, wc-brand-colors {}]
      (mt/user-http-request :crowberto :put 200 "branding/settings"
                            {:wc-brand-name   "Waterloo"
                             :wc-brand-colors {"brand" "#3E90C5"}})
      (is (= "Waterloo" (branding.settings/wc-brand-name)))
      (is (= {"brand" "#3E90C5"} (branding.settings/wc-brand-colors))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test-agent :only '[metabase.branding.api-test]'`

Expected: FAIL — endpoint does not exist.

- [ ] **Step 3: Write the API**

Create `src/metabase/branding/api.clj`:

```clojure
(ns metabase.branding.api
  "/api/branding endpoints for configuring Waterloo branding.

  These write OUR `wc-brand-*` settings. Metabase's gated `application-*` settings are
  never touched here."
  (:require
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.settings.core :as setting]))

(set! *warn-on-reflection* true)

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :put "/settings"
  "Update branding settings. You must be a superuser to do this."
  [_route-params
   _query-params
   settings :- [:map
                [:wc-brand-name            {:optional true} [:maybe :string]]
                [:wc-brand-logo-url        {:optional true} [:maybe :string]]
                [:wc-brand-favicon-url     {:optional true} [:maybe :string]]
                [:wc-brand-colors          {:optional true} [:maybe [:map-of :string :string]]]
                [:wc-help-link             {:optional true} [:maybe [:enum "metabase" "custom" "hidden"]]]
                [:wc-help-link-destination {:optional true} [:maybe :string]]]]
  (api/check-superuser)
  (setting/set-many! settings)
  {:ok true})

(def ^{:arglists '([request respond raise])} routes
  "`/api/branding` routes."
  (api.macros/ns-handler *ns*))
```

- [ ] **Step 4: Mount the routes**

Edit `src/metabase/api_routes/routes.clj`. Add the require for `metabase.branding.api`, then add beside the `/oidc` entry from Task 8:

```clojure
   "/branding"             (+auth metabase.branding.api/routes)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./bin/test-agent :only '[metabase.branding.api-test]'`

Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/metabase/branding/api.clj src/metabase/api_routes/routes.clj \
        test/metabase/branding/api_test.clj
git commit -m "Add PUT /api/branding/settings admin API

Writes our wc-brand-* settings only. Metabase's gated application-*
settings are never touched."
```

---

## Cutover

### Task 15: Full-suite verification and the cutover runbook

**Files:**
- Create: `docs/superpowers/plans/2026-07-17-cutover-runbook.md`

**Interfaces:**
- Consumes: everything.
- Produces: the runbook the operator follows on cutover day.

> **The lockout risk is real and this task is where it is managed.** Four accounts authenticate via OIDC — `arose`, `abuchanan`, `ndyer` (all admins) and `tchatmas`. The moment a container starts with `MB_EDITION=oss`, EE OIDC is gone. If those accounts have no usable password, they are locked out **before** the new connector can let them back in.

- [ ] **Step 1: Run the full relevant test suite**

```bash
./bin/test-agent :only '[metabase.premium-features.oss-build-test
                        metabase.sso.free-oidc-settings-test
                        metabase.sso.providers.free-oidc-test
                        metabase.sso.integrations.free-oidc-test
                        metabase.sso.api.oidc-settings-test
                        metabase.server.auth-wrapper-test
                        metabase.branding.settings-test
                        metabase.branding.migration-test
                        metabase.branding.anti-bypass-test
                        metabase.branding.api-test]'
```

Expected: PASS, all namespaces.

- [ ] **Step 2: Run the frontend tests**

```bash
yarn jest frontend/src/metabase/auth/components/OidcButton frontend/src/metabase/ui/colors --silent
```

Expected: PASS.

- [ ] **Step 3: Verify the fork delta is small**

```bash
git diff --stat v0.62.3.3 HEAD -- src/ enterprise/ frontend/src/ deps.edn Dockerfile docker-compose.yml
```

Expected: the only `enterprise/` entry is `LICENSE.txt` **restored** (not emptied). `token_check.clj` must NOT appear. Telemetry files must NOT appear.

- [ ] **Step 4: Build and smoke-test locally against real Authentik**

```bash
export MB_FREE_OIDC_CLIENT_ID='<from Authentik>'
export MB_FREE_OIDC_CLIENT_SECRET='<from Authentik>'
docker-compose up --build
```

Verify by hand at `http://localhost:3000`:
1. The login page shows a "Sign in with SSO" button.
2. Clicking it redirects to `sso.waterloocap.com`.
3. Authenticating returns you to Metabase logged in.
4. Branding shows Waterloo colors and logo — **compare side-by-side against prod, which is still on the EE build.** This is the cheapest moment to catch a palette or logo regression.
5. Password login still works (break-glass intact).

- [ ] **Step 5: Write the cutover runbook**

Create `docs/superpowers/plans/2026-07-17-cutover-runbook.md`:

```markdown
# Cutover Runbook — Freebase OSS Conversion

**Do not skip step 1.** It is the only step that cannot be undone from a laptop.

## Pre-flight (do this while prod is STILL on the EE build)

1. **Set and VERIFY break-glass passwords** for all four OIDC accounts:
   - `arose@waterloocap.com` (admin)
   - `abuchanan@waterloocap.com` (admin)
   - `ndyer@waterloocap.com` (admin)
   - `tchatmas@waterloocap.com`

   Verify by *actually logging in* with each in a private window. Setting a password
   without testing it is not verification.

   `lmoulton@waterloocap.com` is an active password-auth admin and is the independent
   break-glass if all four fail.

2. **Register the redirect URI in Authentik:**
   `https://analytics.waterloocap.com/auth/sso/oidc/callback`
   This string is pinned by a test in `free_oidc_test.clj`. If it changes, both change.

3. **Confirm the Authentik issuer URI**, including the trailing slash:
   `https://sso.waterloocap.com/application/o/<slug>/`
   A trailing-slash mismatch breaks ID token `iss` validation. This is the most common
   Authentik gotcha.

4. **Back up the app DB.**

5. **Tell stakeholders** who own the external client relationships (lhfinancial.net ×13,
   amgwealthadvisors.com, elementconsultants.com, ironclad-wealth.com, wcfos.com) that a
   deploy is happening. Branding should be identical — but they should hear it from you
   first if it is not.

## Cutover

6. Deploy the new container: OSS + OIDC + branding, all at once. Prod never sits in a
   state with no SSO or with stock branding.

7. The branding migration runs on boot, copying `application-*` → `wc-brand-*`.

8. Clear the four stale OIDC identities so the new connector re-links cleanly:

   ```sql
   -- Inspect first.
   SELECT u.email, u.sso_source, ai.provider
     FROM core_user u
     LEFT JOIN auth_identity ai ON ai.user_id = u.id
    WHERE u.sso_source = 'oidc';

   -- Then clear.
   DELETE FROM auth_identity
    WHERE provider = 'custom-oidc';

   UPDATE core_user
      SET sso_source = NULL
    WHERE sso_source = 'oidc';
   ```

## Verify

9. Log in via OIDC as each of the four. Confirm the three admins still have superuser.
10. Confirm branding renders: Waterloo name, logo, favicon, `#3E90C5` brand color.
11. Log in as a password user (e.g. an lhfinancial.net account) and confirm their
    collections look exactly as before. **Collection permissions are OSS and unchanged,
    so this should be a no-op — verify it anyway.**
12. Confirm `/api/ee/*` returns 404.

## Rollback

Redeploy the previous image and restore the app DB backup. The `auth_identity` deletion
in step 8 is the only destructive change; the DB backup covers it.
```

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/plans/2026-07-17-cutover-runbook.md
git commit -m "Add the cutover runbook

Pre-flight is the load-bearing part: verify break-glass passwords for
all four OIDC accounts by actually logging in, while prod is still on
the EE build. Three of the four are admins.

Also pins the Authentik redirect URI and flags the trailing-slash issuer
gotcha, which is the most common Authentik failure."
```

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task |
|---|---|
| Revert `token_check.clj`, `premium_features/settings.clj`, EE `routes/common.clj`, restore `LICENSE.txt` | 1 |
| Telemetry via env vars; zero fork delta on those files | 2 |
| `MB_EDITION=oss` | 2 |
| OIDC settings named `free-oidc-*` (avoids EE collision); `sso-source-enabled?` `:oidc` case patched | 3 |
| `:provider/free-oidc` deriving from `:provider/oidc` | 4 |
| `sso-initiate` / `sso-callback`; callback URI pinned | 5 |
| Mount `/auth/sso/oidc` beside `/slack-connect` | 6 |
| Relax `ee-sso-configured?` so the login button renders | 7 |
| `PUT /api/oidc/settings` with `check-oidc-configuration` probe | 8 |
| Login button via `PLUGIN_AUTH_PROVIDERS` | 9 |
| `wc-brand-*` settings, ungated | 10 |
| Migrate `application-*` → `wc-brand-*` | 11 |
| Apply branding through the AGPL theming pipeline; whole app | 12 |
| **Anti-bypass test** | 13 |
| Branding admin form API | 14 |
| Cutover: verified passwords for 4 OIDC users; clear stale identities | 15 |

**Known gaps, deliberately deferred:**
- **Admin UI React forms.** Tasks 8 and 14 build the APIs; the React settings pages are not specified here. The APIs are usable via `curl` and env vars in the meantime, so the cutover is not blocked. Add them as a follow-up plan once the shape of the settings has settled in production.
- **Favicon and `application-name` frontend read-sites.** Task 12 covers colors and logo, which are the visible 90%. The favicon injection and the remaining `getApplicationName` call-sites should follow in the same follow-up.

**Type consistency:** `check-sso-redirect` (Task 4) is consumed by Task 5. `oidc-redirect-uri` is private to Task 5 and tested via `#'`. `update-secret-if-needed` is private to Task 8, tested via `#'`. `migrate-application-settings!` (Task 11) returns `{:migrated [kw]}`. Setting names are consistent throughout: `oidc-*` for the connector, `wc-brand-*` for branding.
