(ns metabase.sso.api.oidc-settings-test
  (:require
   [clojure.test :refer :all]
   [metabase.sso.api.oidc-settings]
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

(deftest closed-schema-rejects-unknown-keys-test
  (testing "the endpoint's body schema is closed: unknown keys are rejected, not silently passed to set-many!"
    (mt/user-http-request :crowberto :put 400 "oidc/settings"
                          {:free-oidc-enabled false
                           :bogus-key         "x"})))
