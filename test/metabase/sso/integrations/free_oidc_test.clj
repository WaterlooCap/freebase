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
