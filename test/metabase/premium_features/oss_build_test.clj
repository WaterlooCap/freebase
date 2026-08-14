(ns metabase.premium-features.oss-build-test
  "Proves the enterprise license bypass is gone.

  These tests fail loudly if anyone re-patches token_check.clj. That is the point:
  they are the executable form of this fork's licensing posture."
  (:require
   [clojure.test :refer :all]
   [metabase.analytics.settings :as analytics.settings]
   [metabase.premium-features.core :as premium-features]
   [metabase.premium-features.token-check :as token-check]
   [metabase.test :as mt]
   [metabase.version.settings :as version.settings]))

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
