(ns metabase-enterprise.api.routes.common
  "Shared stuff used by various EE-only API routes."
  (:require
   [metabase.api.open-api :as open-api]
   [metabase.premium-features.core :as premium-features]
   [metabase.util.i18n :as i18n]))

(defn +require-premium-feature
  "Bypass: always allow handler."
  [_feature _feature-name handler]
  (open-api/handler-with-open-api-spec
   (fn [request respond raise]
     (handler request respond raise))
   (fn [prefix]
     (open-api/open-api-spec handler prefix))))

(defn ^:deprecated +when-premium-feature
  "Bypass: always allow handler."
  [_feature handler]
  (open-api/handler-with-open-api-spec
   (fn [request respond raise]
     (handler request respond raise))
   (fn [prefix]
     (open-api/open-api-spec handler prefix))))
