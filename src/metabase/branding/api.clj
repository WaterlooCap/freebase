(ns metabase.branding.api
  "/api/branding endpoints for configuring Waterloo branding.

  These write OUR `wc-brand-*` settings. Metabase's gated `application-*` settings are
  never touched here — the body schema is `{:closed true}`, so any key outside the
  `wc-brand-*`/`wc-help-link*` set (including `application-*` settings) is rejected with a
  400 before it ever reaches `set-many!`."
  (:require
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.settings.core :as setting]
   [metabase.util.malli.registry :as mr]))

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
   [:wc-brand-name            {:optional true} [:maybe :string]]
   [:wc-brand-logo-url        {:optional true} [:maybe :string]]
   [:wc-brand-favicon-url     {:optional true} [:maybe :string]]
   [:wc-brand-colors          {:optional true} [:maybe [:map-of :string :string]]]
   [:wc-help-link             {:optional true} [:maybe [:enum "metabase" "custom" "hidden"]]]
   [:wc-help-link-destination {:optional true} [:maybe :string]]])

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :put "/settings"
  "Update branding settings. You must be a superuser to do this."
  [_route-params
   _query-params
   settings :- ::settings]
  (api/check-superuser)
  (setting/set-many! settings)
  {:ok true})

(def ^{:arglists '([request respond raise])} routes
  "`/api/branding` routes."
  (api.macros/ns-handler *ns*))
