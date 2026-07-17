(ns metabase.sso.oidc-settings-test
  (:require
   [clojure.test :refer :all]
   [metabase.settings.core :as setting]
   [metabase.sso.settings :as sso.settings]
   [metabase.test :as mt]))

(deftest oidc-configured-test
  (testing "oidc-configured is true only when all three mandatory settings are present"
    (mt/with-temporary-setting-values [oidc-client-id nil, oidc-client-secret nil, oidc-issuer-uri nil]
      (is (false? (sso.settings/oidc-configured))))
    (mt/with-temporary-setting-values [oidc-client-id "abc", oidc-client-secret nil, oidc-issuer-uri nil]
      (is (false? (sso.settings/oidc-configured)) "client-id alone is not enough"))
    (mt/with-temporary-setting-values [oidc-client-id     "abc"
                                       oidc-client-secret "shh"
                                       oidc-issuer-uri    "https://sso.waterloocap.com/application/o/metabase/"]
      (is (true? (sso.settings/oidc-configured))))))

(deftest oidc-enabled-requires-configured-test
  (testing "oidc-enabled cannot be true unless OIDC is configured"
    (mt/with-temporary-setting-values [oidc-enabled true, oidc-client-id nil, oidc-client-secret nil, oidc-issuer-uri nil]
      (is (false? (sso.settings/oidc-enabled))
          "enabled must be false when unconfigured, so a half-set config cannot lock anyone out"))))

(deftest client-secret-is-masked-test
  (testing "the client secret getter masks, and the unobfuscated accessor does not"
    (mt/with-temporary-setting-values [oidc-client-secret "super-secret-value"]
      (is (not= "super-secret-value" (sso.settings/oidc-client-secret))
          "the public getter must mask")
      (is (= "super-secret-value" (sso.settings/unobfuscated-oidc-client-secret))
          "the unobfuscated accessor returns the real value"))))

(deftest sso-source-enabled-recognises-oidc-test
  (testing "sso-source-enabled? :oidc reads our setting with no patch to that function"
    (mt/with-temporary-setting-values [oidc-enabled       true
                                       oidc-client-id     "abc"
                                       oidc-client-secret "shh"
                                       oidc-issuer-uri    "https://sso.waterloocap.com/application/o/metabase/"]
      (is (true? (sso.settings/sso-source-enabled? :oidc))))))
