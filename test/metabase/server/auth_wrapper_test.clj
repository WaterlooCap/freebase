(ns metabase.server.auth-wrapper-test
  (:require
   [clojure.test :refer :all]
   [metabase.config.core :as config]
   [metabase.server.auth-wrapper :as auth-wrapper]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]
   [metabase.test.http-client :as client]))

(use-fixtures
  :once
  (fixtures/initialize :web-server :test-users))

(deftest routes-test
  (when-not config/ee-available?
    (doseq [route ["auth/sso" "api/saml"]]
      (testing (str route " route returns nice error message")
        (binding [client/*url-prefix* ""] ; prevent automatic /api/auth/sso which is a 404
          ;; it's possible that a post or get is the actual route that doesn't exist. The warning handler is simple
          ;; and responds to any request with a helpful error message
          (let [response (mt/user-http-request :rasta :post 400 route)]
            (is (= {:message "The auth/sso endpoint only exists in enterprise builds"
                    :status "ee-build-required"}
                   response))))))))

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
    ;; This must hold regardless of `config/ee-available?` -- OIDC is mounted as an
    ;; always-available OSS route, ahead of the EE fallback branch.
    (let [resp (route-response "/auth/sso/oidc")]
      (is (not= "ee-build-required" (get-in resp [:body :status]))
          "OIDC must be served by our OSS handler, not the EE-missing fallback"))))

(deftest saml-route-still-requires-ee-test
  (testing "routes we did NOT implement still return the EE stub"
    ;; Only meaningful in a true OSS build (`config/ee-available?` false): when EE code
    ;; is actually on the classpath, SAML is handled by the real EE routes (and fails
    ;; its own premium-feature check) rather than by the `ee-missing-routes` stub. This
    ;; mirrors the guard already used by `routes-test` above for the same reason.
    (when-not config/ee-available?
      (let [resp (route-response "/auth/sso/saml")]
        (is (= "ee-build-required" (get-in resp [:body :status]))
            "we only added OIDC; SAML must still say EE-only")))))
