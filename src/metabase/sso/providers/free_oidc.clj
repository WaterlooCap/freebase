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

;; :provider/oidc already derives from :metabase.auth-identity.provider/create-user-if-not-exists
;; (see metabase.sso.providers.oidc), so :provider/free-oidc inherits that transitively. Do NOT
;; also `derive` it directly here -- that creates a diamond (two paths to the same ancestor) in
;; Clojure's *shared, global* hierarchy. `underive` rebuilds the whole global hierarchy from
;; scratch by replaying every derive pair in map-iteration order, and unrelated code elsewhere in
;; the app that calls `underive` on some other keyword can hit our diamond mid-rebuild and throw
;; ":provider/free-oidc already has :metabase.auth-identity.provider/create-user-if-not-exists as
;; ancestor" -- reproduced by running the test suite, see metabase.transforms.models.transform.
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
