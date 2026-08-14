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
