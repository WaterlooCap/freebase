(ns metabase.sso.api.oidc-settings
  "/api/oidc endpoints for configuring the OIDC connector.

  Modeled on [[metabase.sso.api.ldap]]: superuser only, test the configuration before
  persisting it, and never clobber the stored secret with its own mask."
  (:require
   [clojure.string :as str]
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.settings.core :as setting]
   [metabase.sso.core :as sso]
   [metabase.sso.settings :as sso.settings]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.malli.registry :as mr]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

;; NOTE: this schema must be registered via `mr/def` and referenced by keyword (not inlined
;; as a literal `[:map {:closed true} ...]` in the `defendpoint` binding). A literal closed
;; map schema trips a bug in `metabase.api.macros/invalid-params-errors` (the "describe the
;; failing key" error-formatting helper can't resolve an unregistered inline schema and
;; throws `:malli.core/invalid-schema`, which surfaces as an uncaught 500 instead of the
;; intended 400) when the request contains an unrecognized top-level key. Registering the
;; schema and referencing it by `::settings` avoids that path entirely — see
;; `metabase.agent-api.api/construct-query-request` for the same pattern.
(mr/def ::settings
  [:map {:closed true}
   [:free-oidc-enabled       {:optional true} [:maybe :boolean]]
   [:free-oidc-issuer-uri    {:optional true} [:maybe :string]]
   [:free-oidc-client-id     {:optional true} [:maybe :string]]
   [:free-oidc-client-secret {:optional true} [:maybe :string]]
   [:free-oidc-scopes        {:optional true} [:maybe :string]]])

(defn- update-secret-if-needed
  "Do not overwrite the stored secret if `new-secret` is just its obfuscated form."
  [new-secret]
  (let [current (sso.settings/unobfuscated-free-oidc-client-secret)]
    (if (= (sso.settings/free-oidc-client-secret) new-secret)
      current
      new-secret)))

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :put "/settings"
  "Update OIDC settings. You must be a superuser to do this. The configuration is probed
  against the provider before being saved; if the probe fails, nothing is persisted."
  [_route-params
   _query-params
   settings :- ::settings]
  (api/check-superuser)
  (let [secret     (update-secret-if-needed (:free-oidc-client-secret settings))
        issuer     (or (:free-oidc-issuer-uri settings) (sso.settings/free-oidc-issuer-uri))
        client-id  (or (:free-oidc-client-id settings) (sso.settings/free-oidc-client-id))
        scopes     (or (:free-oidc-scopes settings) (sso.settings/free-oidc-scopes))
        enabling?  (:free-oidc-enabled settings)]
    ;; Only probe when we are being asked to turn OIDC on. Probing on every save would
    ;; make it impossible to disable a provider that has gone down.
    (when enabling?
      (let [result (sso/check-oidc-configuration issuer client-id secret
                                                 (vec (remove empty? (str/split (or scopes "") #"\s+"))))]
        (when-not (:ok result)
          (throw (ex-info (tru "Could not connect to the OIDC provider")
                          {:status-code 400
                           :errors      (select-keys result [:discovery :credentials])})))))
    ;; IMPORTANT: mirror the ordering in metabase.sso.api.ldap. The `free-oidc-enabled` getter
    ;; consults `free-oidc-configured`, so the config MUST be persisted before we flip enabled
    ;; — otherwise enabling against a fresh config silently evaluates to false.
    (t2/with-transaction [_conn]
      (setting/set-many! (cond-> (dissoc settings :free-oidc-client-secret :free-oidc-enabled)
                           secret (assoc :free-oidc-client-secret secret)))
      (when (contains? settings :free-oidc-enabled)
        (setting/set-value-of-type! :boolean :free-oidc-enabled (boolean (:free-oidc-enabled settings)))))
    {:ok true}))

(def ^{:arglists '([request respond raise])} routes
  "`/api/oidc` routes."
  (api.macros/ns-handler *ns*))
