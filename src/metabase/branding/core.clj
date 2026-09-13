(ns metabase.branding.core
  "API namespace for the `branding` module. Exposes our ungated `wc-brand-*` settings to
  other modules; see [[metabase.branding.settings]] for the boundary with Metabase's own
  gated `application-*` settings."
  (:require
   [metabase.branding.settings]
   [potemkin :as p]))

(comment metabase.branding.settings/keep-me)

(p/import-vars
 [metabase.branding.settings
  wc-brand-favicon-url
  wc-brand-name])
