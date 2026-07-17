(ns metabase.branding.init
  (:require
   [metabase.branding.migration :as branding.migration]
   [metabase.branding.settings]))

(defn init!
  "Run branding initialization: migrate any legacy application-* values on first boot."
  []
  (branding.migration/migrate-application-settings!))
