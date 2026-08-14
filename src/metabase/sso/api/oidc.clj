(ns metabase.sso.api.oidc
  "API routes for Waterloo OIDC SSO authentication."
  (:require
   [metabase.api.macros :as api.macros]
   [metabase.sso.integrations.free-oidc :as free-oidc.integration]
   [metabase.util.log :as log]))

;; GET /auth/sso/oidc
;;
#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :get "/"
  "Initiate the OIDC SSO flow."
  [_route-params _query-params _body request]
  (try
    (free-oidc.integration/sso-initiate request)
    (catch Throwable e
      (log/error e "Error initiating OIDC SSO")
      (throw e))))

;; GET /auth/sso/oidc/callback
;;
#_{:clj-kondo/ignore [:metabase/validate-defendpoint-has-response-schema]}
(api.macros/defendpoint :get "/callback"
  "OIDC callback."
  [_route-params _query-params _body request]
  (try
    (free-oidc.integration/sso-callback request)
    (catch Throwable e
      (log/error e "Error handling OIDC callback")
      (throw e))))

(def ^{:arglists '([request respond raise])} routes
  "`/auth/sso/oidc` routes."
  (api.macros/ns-handler *ns*))
