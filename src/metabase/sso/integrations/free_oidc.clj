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
