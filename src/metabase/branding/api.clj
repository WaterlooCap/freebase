(ns metabase.branding.api
  "/api/branding endpoints for configuring Waterloo branding.

  These write OUR `wc-brand-*` settings. Metabase's gated `application-*` settings are
  never touched here."
  (:require
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.settings.core :as setting]))

(set! *warn-on-reflection* true)

#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :put "/settings"
  "Update branding settings. You must be a superuser to do this."
  [_route-params
   _query-params
   settings :- [:map
                [:wc-brand-name            {:optional true} [:maybe :string]]
                [:wc-brand-logo-url        {:optional true} [:maybe :string]]
                [:wc-brand-favicon-url     {:optional true} [:maybe :string]]
                [:wc-brand-colors          {:optional true} [:maybe [:map-of :string :string]]]
                [:wc-help-link             {:optional true} [:maybe [:enum "metabase" "custom" "hidden"]]]
                [:wc-help-link-destination {:optional true} [:maybe :string]]]]
  (api/check-superuser)
  (setting/set-many! settings)
  {:ok true})

(def ^{:arglists '([request respond raise])} routes
  "`/api/branding` routes."
  (api.macros/ns-handler *ns*))
