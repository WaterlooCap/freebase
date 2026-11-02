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
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [methodical.core :as methodical]))

(set! *warn-on-reflection* true)

;;; -------------------------------------------------- Provider Registration --------------------------------------------------

;; :provider/oidc already derives from ::provider/provider and ::provider/create-user-if-not-exists,
;; so :provider/free-oidc inherits both.
(auth-identity/derive! :provider/free-oidc :provider/oidc)

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

(defn- safe-relative-path?
  "Whether `url` stays on this site once a browser follows it. Its path needs one leading slash and then no second
  slash, backslash or control character, at every level of percent-decoding, because browsers read a run of slashes
  or backslashes as `//host`. The query string and fragment cannot change the host, so they are not decoded."
  [^String url]
  (let [path (first (str/split url #"[?#]" 2))]
    (loop [value path, levels 3]
      (and (re-find #"^/(?![/\\])[^\\\p{Cntrl}]*$" value)
           (let [decoded (try
                           (java.net.URLDecoder/decode ^String value "UTF-8")
                           ;; a stray % cannot decode into a slash, so this is as far as it goes
                           (catch IllegalArgumentException _ value))]
             (if (or (zero? levels) (= decoded value))
               true
               (recur decoded (dec levels))))))))

(defn check-sso-redirect
  "Check if open redirect is being exploited in SSO. If so, or if the redirect-url is
  invalid, throw a 400."
  [redirect-url]
  (let [invalid (fn []
                  (throw (ex-info (tru "Invalid redirect URL")
                                  {:status-code  400
                                   :redirect-url redirect-url})))]
    (try
      (let [redirect (some-> redirect-url (java.net.URI.))
            our-host (some-> ((requiring-resolve 'metabase.system.core/site-url)) (java.net.URI.) (.getHost))]
        (when-not (or (nil? redirect-url)
                      (if (.getScheme redirect)
                        (and (#{"http" "https"} (u/lower-case-en (.getScheme redirect)))
                             our-host
                             (= (.getHost redirect) our-host))
                        (safe-relative-path? redirect-url)))
          (invalid))
        redirect-url)
      (catch java.net.URISyntaxException _
        (invalid)))))

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
