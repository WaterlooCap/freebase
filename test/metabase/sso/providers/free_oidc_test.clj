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
