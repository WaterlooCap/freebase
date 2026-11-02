(ns metabase.sso.providers.free-oidc-test
  (:require
   [clojure.test :refer :all]
   [metabase.auth-identity.core :as auth-identity]
   [metabase.sso.providers.free-oidc :as free-oidc]
   [metabase.test :as mt]))

(deftest provider-derives-from-base-oidc-test
  (testing "our provider inherits the AGPL base OIDC flow"
    (is (auth-identity/isa? :provider/free-oidc :provider/oidc)
        "must derive from the OSS base provider so we inherit discovery, token exchange, and ID-token validation")
    (is (auth-identity/isa? :provider/free-oidc :metabase.auth-identity.provider/create-user-if-not-exists)
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

(deftest check-sso-redirect-rejects-host-smuggling-test
  (mt/with-temporary-setting-values [site-url "https://analytics.example.com"]
    (testing "forms a browser reads as another host are rejected"
      (doseq [url ["//evil.example.com"
                   "///evil.example.com"
                   "////evil.example.com"
                   "/\\evil.example.com"
                   "\\\\evil.example.com"
                   "/%2F/evil.example.com"
                   "/%2f%2fevil.example.com"
                   "%2F%2Fevil.example.com"
                   "/%5Cevil.example.com"
                   "/%255Cevil.example.com"
                   "/%252F%252Fevil.example.com"
                   "/%2F/evil.example.com?q=100%25"
                   "/%09/evil.example.com"
                   "evil.example.com"
                   "javascript:alert(1)"
                   "https://evil.example.com"]]
        (testing url
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid redirect URL"
                                (free-oidc/check-sso-redirect url))))))
    (testing "paths on this site and this site's own URL still pass"
      (doseq [url ["/" "/dashboard/1" "/question/2?a=b%20c#x" "/question/5?q=100%25" "/question/5?q=%2F%2Fok"
                   "/report/100%25" "https://analytics.example.com/dashboard/1"]]
        (testing url
          (is (= url (free-oidc/check-sso-redirect url))))))))

(deftest check-sso-redirect-blocks-open-redirect-test
  (testing "relative redirects are allowed"
    (is (= "/dashboard/1" (free-oidc/check-sso-redirect "/dashboard/1"))))
  (testing "external hosts are rejected"
    (is (thrown? clojure.lang.ExceptionInfo (free-oidc/check-sso-redirect "https://evil.example.com/steal")))))
